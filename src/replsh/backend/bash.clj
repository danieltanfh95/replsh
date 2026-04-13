(ns replsh.backend.bash
  "Bash REPL backend. Uses the identical NDJSON protocol as :python —
   only the bridge script differs (--backend bash flag drives a persistent
   bash subprocess).  All Clojure-side protocol handling delegates to
   the :python multimethod implementations."
  (:require [replsh.backend :as backend]))

(defn- as-python [m] (assoc m :backend :python))

(defmethod backend/open!      :bash [sc]      (backend/open!      (as-python sc)))
(defmethod backend/close!     :bash [ls]      (backend/close!     (as-python ls)))
(defmethod backend/destroy!   :bash [sc]      (backend/destroy!   (as-python sc)))
(defmethod backend/eval!      :bash [req ls]  (backend/eval!      (as-python req) ls))
(defmethod backend/interrupt! :bash [ls]      (backend/interrupt! (as-python ls)))
