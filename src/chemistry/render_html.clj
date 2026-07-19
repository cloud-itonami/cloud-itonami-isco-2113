(ns chemistry.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for the ISCO-08 cluster: this repo previously had NO demo page and no
  generator at all (`:item2/classification \"unknown-no-demo\"` in the
  fleet-wide scan). This namespace drives the REAL actor stack
  (`chemistry.actor` -> `chemistry.governor` -> `chemistry.store`) through
  a scenario built from real, exercised store data and renders the result
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify by
  diffing two consecutive runs before shipping).

  Adapted from the ISCO-08 1211/1111 build-time-console precedents
  (`90-docs/business/cloud-itonami-maturity-loop.md` iterations 9/10 in
  com-junkawasaki/root) using this repo's OWN real fixture, not a copy of
  theirs: `project-1` (\"Organic Synthesis Study\") + sample `s-1`
  (\"Synthesis product\") + equipment `eq-1` (\"Gas Chromatograph\") are
  lifted VERBATIM from `chemistry.actor-test`/`chemistry.governor-test`'s
  `fresh-store` fixture (ground truth, not invented). `project-2`
  (\"Catalytic Reaction Kinetics\") + sample `s-2` (\"Catalyst recovery
  batch\") are ADDITIONAL demo data registered via the SAME real protocol
  calls (`register-project!`/`register-sample!`) this actor's own store
  exposes -- disclosed here plainly, not presented as pre-existing
  fixture, so the console can show more than one concurrent research
  project. Every other field this page displays (statuses, remaining
  sample/report state, hold reasons) is real output read after
  `run-demo!` actually executed the graph -- none of it is hand-typed.

  Unlike the ISCO-08 1211/1111 precedents, THIS repo's `chemistry.advisor`
  mock (`infer`) explicitly preserves `:finalized?`/`:hazardous?` from the
  incoming request through to the emitted proposal (see its own
  docstring: \"Preserves optional fields like finalized? or hazardous?
  for testing edge cases\") -- so, unlike finmgmt/legislature, THIS demo
  can reach `chemistry.governor`'s `:no-finalized-claims` hard-hold and
  the `:draft-report`+`:hazardous?` escalation through the real advisor,
  not just through `chemistry.governor-test`'s direct `governor/check`
  calls.

  Known architectural gaps, honestly noted rather than papered over (both
  confirmed by reading `chemistry.governor` itself, not assumed):
  - `:no-actuation` (proposal `:effect` must be `:propose`) is NOT
    reachable through this demo, because the real `mock-advisor`
    (`chemistry.advisor/infer`) unconditionally sets `:effect :propose`
    on every proposal it emits -- by design, the advisor can never itself
    emit a raw store write. Covered instead by
    `chemistry.governor-test/rejects-non-propose-effect-hard` (which
    calls `governor/check` directly with a hand-built proposal).
  - low-confidence escalation (`confidence < 0.6`) is NOT reachable
    either, because `chemistry.advisor/infer`'s stake-derived confidence
    (`:high` 0.7, `:medium` 0.85, `:low` 0.95) never drops below the
    governor's `confidence-floor` (0.6). Covered instead by
    `chemistry.governor-test/escalates-low-confidence`.
  Both gaps are the SAME shape as the ISCO-08 1211 precedent's
  `:no-actuation` gap (`finmgmt.render-html`'s own docstring) -- this
  demo, like that one, only ever drives the real actor/graph the way an
  operator actually would, and does not hand-construct proposals to force
  unreachable paths.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [chemistry.store :as store]
            [chemistry.actor :as actor]))

;; ----------------------------- harness --------------------------------

(defn- run-op!
  "Drives one real lab operation request through the actual compiled
  graph for `tid` (thread-id). If the graph escalates (interrupts before
  `:request-approval`), immediately approves it (this demo's scenario
  never demonstrates an UNAPPROVED escalation -- every escalation here
  reaches a human who signs off). Returns a map describing exactly what
  really happened -- no field is invented."
  [graph tid project-id op extra]
  (let [request (merge {:project-id project-id :op op} extra)
        r1 (actor/run-request! graph request {} tid)]
    (if (= :interrupted (:status r1))
      (let [r2 (actor/approve! graph tid)]
        {:thread-id tid :project-id project-id :op op :request request
         :outcome :approved-and-committed
         :record (get-in r2 [:state :record])})
      (let [disposition (get-in r1 [:state :disposition])]
        (if (= :hold disposition)
          {:thread-id tid :project-id project-id :op op :request request
           :outcome :hard-hold
           :verdict (get-in r1 [:state :verdict])
           :rule (-> r1 :state :verdict :violations first :rule)}
          {:thread-id tid :project-id project-id :op op :request request
           :outcome :auto-committed
           :record (get-in r1 [:state :record])})))))

(def ^:private op-specs
  "The scenario: covers every disposition this actor can genuinely reach
  through its real graph (auto-commit, escalate-then-approve, and 3 of
  the 4 distinct HARD-hold reasons plus 2 of the 3 escalation reasons in
  `chemistry.governor` -- the unreachable ones (`:no-actuation`,
  low-confidence) are architecturally unreachable via the real advisor,
  see namespace docstring). Every `:op` keyword and violation rule name
  below is copied from `chemistry.governor`'s own `hard-violations`/
  `check`, not invented."
  [;; project-1 / \"Organic Synthesis Study\" / s-1 (real fixture from chemistry.actor-test)
   ["p1-analyze-clean"      "project-1" :analyze-sample        {:sample-id "s-1" :stake :low}]
   ["p1-missing-sample"     "project-1" :analyze-sample        {:sample-id "no-such-sample" :stake :low}]
   ["p1-draft-report-clean" "project-1" :draft-report          {:stake :low}]
   ["p1-draft-finalized"    "project-1" :draft-report          {:stake :high :finalized? true}]
   ["p1-flag-hazardous"     "project-1" :flag-hazardous-result {:stake :high}]
   ["p1-draft-hazardous"    "project-1" :draft-report          {:stake :medium :hazardous? true}]
   ["p1-equipment-time"     "project-1" :request-equipment-time {:stake :low}]
   ;; unregistered project entirely
   ["ghost-no-project"      "no-such-project" :analyze-sample  {:sample-id "s-1" :stake :low}]
   ;; project-2 / \"Catalytic Reaction Kinetics\" / s-2 (additional demo data,
   ;; registered via the same real register-project!/register-sample!
   ;; calls -- see namespace docstring)
   ["p2-analyze-clean"      "project-2" :analyze-sample        {:sample-id "s-2" :stake :low}]
   ["p2-draft-report-clean" "project-2" :draft-report          {:stake :low}]])

(defn run-demo!
  "Runs a fresh store through `op-specs` (see above) via the real
  compiled `chemistry.actor` graph. Returns `{:store :runs}` -- `:runs`
  is the ordered vector of real per-request outcomes; every field in
  `render` below is read from this or from `store` after the graph
  actually executed, never hand-typed."
  []
  (let [db (store/mem-store)]
    (store/register-project! db {:project-id "project-1" :title "Organic Synthesis Study"})
    (store/register-sample! db {:sample-id "s-1" :project-id "project-1" :description "Synthesis product"})
    (store/register-equipment! db {:equipment-id "eq-1" :name "Gas Chromatograph"})
    (store/register-project! db {:project-id "project-2" :title "Catalytic Reaction Kinetics"})
    (store/register-sample! db {:sample-id "s-2" :project-id "project-2" :description "Catalyst recovery batch"})
    (let [graph (actor/build-graph {:store db})
          runs (mapv (fn [[tid project-id op extra]]
                       (run-op! graph tid project-id op extra))
                     op-specs)]
      {:store db :runs runs})))

;; ----------------------------- rendering -------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- outcome-cell [{:keys [outcome rule]}]
  (case outcome
    :auto-committed "<span class=\"ok\">committed</span>"
    :approved-and-committed "<span class=\"ok\">approved &amp; committed</span>"
    :hard-hold (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>")
    "<span class=\"muted\">in progress</span>"))

(defn- project-row [store {:keys [project-id title sample-id sample-desc]} runs]
  (let [record-count (count (store/records-of store project-id))
        last-run (last (filter #(= project-id (:project-id %)) runs))]
    (format "        <tr><td>%s</td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%d</td><td>%s</td></tr>"
            (esc project-id) (esc title) (esc sample-id) (esc sample-desc) record-count
            (if last-run (outcome-cell last-run) "<span class=\"muted\">no activity</span>"))))

(defn- run-row [{:keys [thread-id project-id op request outcome rule]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc thread-id) (esc project-id) (esc (name op))
          (esc (cond
                 (:sample-id request) (str (:sample-id request))
                 (:finalized? request) "finalized?=true"
                 (:hazardous? request) "hazardous?=true"
                 :else ""))
          (outcome-cell {:outcome outcome :rule rule})))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README.md
  ;; \"Robotics premise\"/governor section, `chemistry.governor`'s own
  ;; docstring) -- documentation of fixed behavior, not runtime
  ;; telemetry, so it is legitimately hand-described rather than derived
  ;; from a live run.
  ["        <tr><td><code>:analyze-sample</code></td><td><span class=\"ok\">auto-commit when clean, sample must be registered</span></td></tr>"
   "        <tr><td><code>:draft-report</code></td><td><span class=\"warn\">auto-commit UNLESS hazardous (then human sign-off) or finalized-claim (then HARD hold)</span></td></tr>"
   "        <tr><td><code>:flag-hazardous-result</code></td><td><span class=\"warn\">ALWAYS human approval &middot; lab safety safeguard</span></td></tr>"
   "        <tr><td><code>:request-equipment-time</code></td><td><span class=\"ok\">auto-commit when clean, no capital risk</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from `{:store :runs}`
  as produced by `run-demo!` (or any other real scenario)."
  [{:keys [store runs]}]
  (let [projects [{:project-id "project-1" :title "Organic Synthesis Study"
                    :sample-id "s-1" :sample-desc "Synthesis product"}
                   {:project-id "project-2" :title "Catalytic Reaction Kinetics"
                    :sample-id "s-2" :sample-desc "Catalyst recovery batch"}]
        project-rows (str/join "\n" (map #(project-row store % runs) projects))
        run-rows (str/join "\n" (map run-row runs))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isco-2113 &middot; chemistry research support</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Chemistry Research Support (ISCO-08 2113) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · hazardous findings always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Registered projects &amp; samples</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>chemistry.store</code> via <code>chemistry.render-html</code> (<code>clojure -M:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Title</th><th>Sample</th><th>Description</th><th>Records committed</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     project-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Chemistry Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Hazardous findings and finalized-claim manuscripts always require human review or are rejected outright.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit trail (this run)</h2>\n"
     "    <p class=\"muted\">Every request this scenario drove through the real compiled graph, in order — thread-id, project, op, the request's own sample/flag fields, and the real disposition (auto-commit, approved-after-escalation, or the specific HARD-hold rule).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Thread</th><th>Project</th><th>Op</th><th>Field</th><th>Disposition</th></tr></thead>\n"
     "      <tbody>\n"
     run-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        html (render result)]
    (spit out html)
    (println "wrote" out "("
             (count (:runs result)) "requests driven through the real graph,"
             (count (store/ledger (:store result))) "ledger facts )")))
