(ns chemistry.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [chemistry.actor :as actor]
            [chemistry.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-project! st {:project-id "proj-1" :title "Organic Synthesis Study"})
    (store/register-sample! st {:sample-id "s-1" :project-id "proj-1" :description "Synthesis product"})
    (store/register-equipment! st {:equipment-id "eq-1" :name "Gas Chromatograph"})
    st))

(deftest commits-a-clean-low-risk-analysis-request
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "proj-1" :sample-id "s-1" :op :analyze-sample :stake :low}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "proj-1"))))))

(deftest holds-on-unregistered-project-without-committing
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "no-such-project" :op :analyze-sample :stake :low :sample-id "s-1"}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "no-such-project")))
    (is (= :hold (:disposition (:state result))))))

(deftest holds-on-missing-sample-for-analyze-op
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:project-id "proj-1" :sample-id "no-such-sample" :op :analyze-sample :stake :low}
        result (actor/run-request! graph request {} "thread-3")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "proj-1")))
    (is (= :hold (:disposition (:state result))))))

(deftest interrupts-then-commits-on-human-approval-for-hazardous-flag
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; flag-hazardous-result always escalates (governor invariant)
        request {:project-id "proj-1" :op :flag-hazardous-result :stake :high}
        interrupted (actor/run-request! graph request {} "thread-4")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "proj-1")))
    (let [resumed (actor/approve! graph "thread-4")]
      (is (= :done (:status resumed)))
      (is (some? (get-in resumed [:state :record])))
      (is (= 1 (count (store/records-of st "proj-1")))))))

(deftest holds-on-finalized-report-claim-in-draft
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        ;; report proposals claiming finalization are hard-rejected
        request {:project-id "proj-1" :op :draft-report :stake :high :finalized? true}
        result (actor/run-request! graph request {} "thread-5")]
    (is (= :done (:status result)))
    (is (nil? (get-in result [:state :record])))
    (is (empty? (store/records-of st "proj-1")))
    (is (= :hold (:disposition (:state result))))))
