(ns chemistry.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [chemistry.governor :as governor]
            [chemistry.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :title "Organic Synthesis Study"})
    (store/register-sample! st {:sample-id "s-1" :project-id "proj-1" :description "Synthesis product"})
    st))

(deftest rejects-unregistered-project-hard
  (let [st (fresh-store)
        request {:project-id "no-project"}
        proposal {:op :analyze-sample :effect :propose :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (seq (:violations verdict)))
    (is (some #(= :no-project (:rule %)) (:violations verdict)))))

(deftest rejects-non-propose-effect-hard
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :analyze-sample :effect :commit :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-actuation (:rule %)) (:violations verdict)))))

(deftest rejects-missing-sample-for-analyze-hard
  (let [st (fresh-store)
        request {:project-id "proj-1" :sample-id "no-sample"}
        proposal {:op :analyze-sample :effect :propose :confidence 0.9}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-sample (:rule %)) (:violations verdict)))))

(deftest rejects-finalized-report-claim-hard
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-report :effect :propose :confidence 0.9 :finalized? true}
        verdict (governor/check request {} proposal st)]
    (is (:hard? verdict))
    (is (not (:ok? verdict)))
    (is (some #(= :no-finalized-claims (:rule %)) (:violations verdict)))))

(deftest escalates-flag-hazardous-result
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :flag-hazardous-result :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest escalates-draft-report-with-hazardous-finding
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-report :effect :propose :confidence 0.9 :hazardous? true}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        request {:project-id "proj-1" :sample-id "s-1"}
        proposal {:op :analyze-sample :effect :propose :confidence 0.4}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))
    (is (not (:ok? verdict)))))

(deftest approves-clean-low-stake-analysis
  (let [st (fresh-store)
        request {:project-id "proj-1" :sample-id "s-1"}
        proposal {:op :analyze-sample :effect :propose :confidence 0.95 :stake :low}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))
    (is (:ok? verdict))))

(deftest approves-draft-report-without-hazardous-finding
  (let [st (fresh-store)
        request {:project-id "proj-1"}
        proposal {:op :draft-report :effect :propose :confidence 0.85 :hazardous? false}
        verdict (governor/check request {} proposal st)]
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))
    (is (:ok? verdict))))
