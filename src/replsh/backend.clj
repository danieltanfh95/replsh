(ns replsh.backend)

(defmulti open!
  "Open transport handles for a session config. Returns live-state map."
  (fn [session-config] (:backend session-config)))

(defmulti close!
  "Close transport handles. Returns nil."
  (fn [live-state] (:backend live-state)))

(defmulti destroy!
  "Clean up server-side resources (nREPL: close session, Jupyter: delete kernel).
   Returns nil."
  (fn [session-config] (:backend session-config)))

(defmulti eval!
  "Evaluate code. Returns vector of response chunks."
  (fn [request live-state] (:backend request)))

(defmulti interrupt!
  "Interrupt a running eval. Returns :ok or error map."
  (fn [live-state] (:backend live-state)))
