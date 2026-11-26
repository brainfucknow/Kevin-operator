(ns kevincore.dotnet
  "The IO side of kevincore.pin: asks dotnet and git whether a committed
  manifest at the repository root pins a local dotnet tool."
  (:require [babashka.fs :as fs]
            [kevincore.git :as git]
            [kevincore.outcome :as outcome]
            [kevincore.pin :as pin]
            [kevincore.shell :as shell]))

(defn- committed?
  "Whether `relative` is in HEAD with no uncommitted changes."
  [root relative]
  (and (git/succeeds? ["cat-file" "-e" (str "HEAD:" relative)] :dir root)
       (git/succeeds? ["diff" "--quiet" "HEAD" "--" relative] :dir root)))

(defn- pin-refusal
  "Why the pin of `tool` that `dotnet tool list` reports cannot be used, or nil."
  [tool wrapper root listed]
  (if-let [listed-path (pin/listed-manifest tool listed)]
    (let [manifest (str (fs/real-path listed-path))
          relative (pin/relative-to-root root manifest)]
      (cond
        (nil? relative) (pin/outside tool root manifest)
        (not (contains? pin/root-manifests relative)) (pin/below-root tool wrapper root manifest)
        (not (committed? root relative)) (pin/not-committed tool manifest)))
    (pin/not-found tool root (:exit listed))))

(defn require-pinned-tool
  "Stops unless a committed root manifest pins `tool` and its probe command
  starts; `wrapper` names the kevin-* command in the advice."
  [tool wrapper]
  (let [root (str (fs/real-path (git/repo-root)))]
    (when-let [refusal (pin-refusal tool wrapper root (shell/capture "dotnet" (pin/list-args tool)))]
      (throw (outcome/usage-error refusal))))
  (let [{:keys [exit]} (shell/capture "dotnet" (:probe tool))]
    (when-not (zero? exit)
      (throw (outcome/usage-error (pin/not-runnable tool wrapper exit))))))
