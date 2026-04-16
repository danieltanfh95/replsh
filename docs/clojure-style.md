# Clojure Style Guide — replsh

Rules derived from real defects found in this codebase. Follow them before submitting changes.

---

## Rule 1 — Always alias namespace requires

Every namespace used in the body must appear in `ns :require` with an alias. Never call `clojure.string/split-lines` inline without a require alias.

```clojure
;; Bad
(clojure.string/split-lines s)

;; Good
(ns foo (:require [clojure.string :as str]))
(str/split-lines s)
```

---

## Rule 2 — No mutable accumulation for pure aggregation

Don't use `atom` + `swap!` when there are no concurrent callers. Use loop accumulators or `reduce`. Reserve `atom` for genuine concurrency or state that outlives the function.

```clojure
;; Bad — sequential single-threaded accumulation
(let [results (atom [])]
  (doseq [x xs]
    (swap! results conj (process x)))
  @results)

;; Good
(reduce (fn [acc x] (conj acc (process x))) [] xs)
```

Violation example fixed: `collect-responses` in `backend/jupyter.clj`.

---

## Rule 3 — Don't duplicate; check `replsh.util` and `replsh.process` first

Before writing a new helper, check `util.clj` and `process.clj`. If logic needs sharing, make a private `defn-` public rather than copy-pasting.

Key shared utilities:
- `util/log-dir` — path to `~/.replsh/logs/`
- `util/read-edn-file` — read EDN if file exists, else nil
- `util/gen-id`, `util/timestamp`, `util/find-free-port`, `util/parse-address`
- `process/read-log-tail` — last N lines from a log file
- `process/spawn!`, `process/kill!`, `process/alive?`

---

## Rule 4 — Use named `fn` for multi-expression anonymous functions

Prefer `(fn [x] ...)` over `#(...)` when the body contains more than one expression or needs a type hint.

```clojure
;; Bad
(map #(let [v (process %)] (when v (transform v))) xs)

;; Good
(map (fn [x] (let [v (process x)] (when v (transform v)))) xs)
```

---

## Rule 5 — Extract repeated guards to named helpers

When a guard/throw pattern appears three or more times identically, extract it to a named function.

```clojure
;; Bad — copy-pasted in seven command functions
(when-not session
  (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                  {:code :session-not-found})))

;; Good — extracted once in command.clj
(defn- require-session! [session name]
  (when-not session
    (throw (ex-info (str "Session not found: " (or name "(no active session)"))
                    {:code :session-not-found}))))
```

---

## Rule 6 — Use `(random-uuid)` instead of `java.util.UUID/randomUUID`

Babashka provides `random-uuid` as a built-in; prefer it over the Java interop call.

```clojure
;; Bad
(str (java.util.UUID/randomUUID))

;; Good
(str (random-uuid))
```

---

## Rule 7 — Use explicit `StandardCharsets/UTF_8` for all socket I/O

When creating `BufferedReader`/`PrintWriter` over sockets, always pass `StandardCharsets/UTF_8`. Never rely on the platform default charset. Use `transport.tcp/open-text-socket` for NDJSON/raw text connections — it handles this correctly.

```clojure
;; Bad — platform default charset
(BufferedReader. (InputStreamReader. (.getInputStream sock)))

;; Good — explicit UTF-8 via shared helper
(tcp/open-text-socket host port)
```
