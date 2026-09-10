(ns chemistry.governor
  "ChemistryGovernor — the independent safety/traceability layer for
  the ISCO-08 2113 research support actor (chemists). Wired as its own
  `:govern` node in `chemistry.actor`'s StateGraph, downstream of
  `:advise` — the Advisor has no notion of project provenance or lab
  safety risk, so this MUST be a separate system able to reject a
  proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md
  Actors section).

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. project provenance  — the request's project must be registered.
    2. sample verification — :analyze-sample ops must reference a
                             registered sample.
    3. no-actuation — proposal :effect must be :propose.
    4. no-finalized-claims — :draft-report proposals can never claim
                             a result as finalized/novel/publishable
                             (draft is draft-for-review only, not the
                             final-report proposal).

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    5. :flag-hazardous-result — always escalates (lab safety
                                safeguard, never silently dismissed).
    6. :draft-report with :hazardous? true — hazardous findings require
                                             human review before proceeding.
    7. low confidence (< `confidence-floor`)."
  (:require [chemistry.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:flag-hazardous-result :draft-report})

(defn- hard-violations [{:keys [proposal request]} project-record sample-record]
  (cond-> []
    (nil? project-record)
    (conj {:rule :no-project :detail "未登録 project"})

    (and (= :analyze-sample (:op proposal))
         (nil? sample-record))
    (conj {:rule :no-sample :detail "analyze-sample 前に sample は要登録"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (and (= :draft-report (:op proposal))
         (:finalized? proposal))
    (conj {:rule :no-finalized-claims :detail "report 最終化は draft 提案では不可（draft は査読用のみ）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `chemistry.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [project-record (store/project store (:project-id request))
        sample-record (when (:sample-id request) (store/sample store (:sample-id request)))
        hard (hard-violations {:proposal proposal :request request} project-record sample-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        is-flag? (= :flag-hazardous-result (:op proposal))
        is-draft-hazardous? (and (= :draft-report (:op proposal)) (:hazardous? proposal))
        risky-op? (and (contains? escalating-ops (:op proposal))
                       (or is-flag? is-draft-hazardous?))]
    {:ok? (and (not hard?) (not low?) (not risky-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? risky-op?))}))
