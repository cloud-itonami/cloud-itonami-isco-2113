(ns chemistry.store
  "SSoT for the ISCO-08 2113 research support actor (chemists).
  Store is a protocol injected into the `chemistry.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep
  backend; a Datomic/kotoba-server-backed implementation can be
  swapped in without touching the actor or governor (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    project  — a registered research project (:project-id, :title)
    sample   — a recorded chemical sample associated with a project
               (:sample-id, :project-id, :description)
    equipment — a lab equipment resource (:equipment-id, :name)
    record   — a committed lab operation under a project
               (analysis result, report draft, hazardous flag,
               equipment time request) — written ONLY via commit-record!,
               never mutated in place
    ledger   — an append-only audit trail of every proposal/verdict/
               disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (project [s project-id])
  (sample [s sample-id])
  (equipment [s equipment-id])
  (records-of [s project-id])
  (ledger [s])
  (register-project! [s project])
  (register-sample! [s sample])
  (register-equipment! [s equipment])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (project [_ project-id] (get-in @a [:projects project-id]))
  (sample [_ sample-id] (get-in @a [:samples sample-id]))
  (equipment [_ equipment-id] (get-in @a [:equipment equipment-id]))
  (records-of [_ project-id] (filter #(= project-id (:project-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-project! [s project]
    (swap! a assoc-in [:projects (:project-id project)] project) s)
  (register-sample! [s sample]
    (swap! a assoc-in [:samples (:sample-id sample)] sample) s)
  (register-equipment! [s equipment]
    (swap! a assoc-in [:equipment (:equipment-id equipment)] equipment) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:projects {} :samples {} :equipment {} :records [] :ledger []} seed)))))
