#!/usr/bin/env bash
# Install the Kevin harness into a project.
#
#   install.sh [target-dir]
#
# target-dir  the project to install into (default: current directory)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
target="$(cd "${1:-.}" && pwd)"

[ -d "$target/.git" ] || { echo "$target is not a git repository" >&2; exit 1; }

# Run from $HERE, which has no bb.edn, so the caller's bb.edn cannot affect it.
minimum=""
min_pattern=':min-bb-version "([^"]*)"'
while IFS= read -r line; do
  [[ $line =~ $min_pattern ]] && minimum="${BASH_REMATCH[1]}"
done < "$HERE/kevin/tools/kevin/bb.edn"
needs="install.sh: Kevin needs Babashka $minimum or newer: https://babashka.org"
command -v bb >/dev/null || { echo "$needs" >&2; exit 1; }
if ! check_err="$(cd "$HERE" && bb "$HERE/kevin/bin/kevin" help 2>&1 >/dev/null)"; then
  [ -z "$check_err" ] || echo "$check_err" >&2
  if echo "$check_err" | grep -q '^kevin: Babashka .* is too old; kevin needs '; then
    echo "$needs" >&2
  else
    echo "install.sh: the Babashka check failed" >&2
  fi
  exit 1
fi

# A legacy install keeps the harness in swarmforge/; install.sh moves it to
# kevin/ and rewrites the old names in the project's own files there.
migrating=""
if [ -e "$target/swarmforge" ] || [ -L "$target/swarmforge" ]; then
  if [ -e "$target/kevin" ] || [ -L "$target/kevin" ]; then
    echo "install.sh: both swarmforge/ and kevin/ exist; keep one" >&2
    exit 1
  fi
  migrating=1
fi

config_sources=("$HERE/kevin/constitution/articles/project.md")
for file in "$HERE"/kevin/*.conf; do
  [ "$(basename "$file")" = verify.conf ] || config_sources+=("$file")
done

check_config_destination() {
  if [ -L "$1" ] || { [ -e "$1" ] && [ ! -f "$1" ]; }; then
    echo "install.sh: project configuration conflict: $1 is not a regular file" >&2
    exit 1
  fi
}

for file in "${config_sources[@]}"; do
  relative="${file#"$HERE/"}"
  if [ -n "$migrating" ]; then
    check_config_destination "$target/swarmforge/${relative#kevin/}"
  else
    check_config_destination "$target/$relative"
  fi
done

if [ -n "$migrating" ]; then
  mv "$target/swarmforge" "$target/kevin"
  # The tool sources an old install shipped under their old names.
  rm -rf "$target/kevin/tools/sf" "$target/kevin/tools/core/src/sfcore" \
    "$target/kevin/tools/csharp/src/sfcs" "$target/kevin/tools/csharp/src/sffs" \
    "$target/kevin/tools/csharp/src/sfvb" "$target/kevin/tools/java/src/sfjava" \
    "$target/kevin/tools/javascript/src/sfjs" "$target/kevin/tools/ruby/src/sfrb" \
    "$target/kevin/tools/ruby/resources/sfrb"
fi

shipped=()
copy_file() {
  local src="$1" dst="$2" config
  shipped+=("${src#"$HERE/"}")
  for config in "${config_sources[@]}"; do
    if [ "$src" = "$config" ]; then
      check_config_destination "$dst"
      [ ! -e "$dst" ] || return 0
      break
    fi
  done
  mkdir -p "$(dirname "$dst")"
  cp -P "$src" "$dst"
}

copy_tree() {
  local src="$1" dst="$2" entry
  # A payload directory the source checkout does not have (yet) ships nothing
  # and must not leave an empty directory in the target.
  [ -d "$src" ] || return 0
  mkdir -p "$dst"
  for entry in "$src"/* "$src"/.[!.]* "$src"/..?*; do
    [ -e "$entry" ] || [ -L "$entry" ] || continue
    if [ -d "$entry" ] && [ ! -L "$entry" ]; then
      copy_tree "$entry" "$dst/$(basename "$entry")"
    else
      copy_file "$entry" "$dst/$(basename "$entry")"
    fi
  done
}

mkdir -p "$target/kevin"
# verify.conf holds this repository's own checks; a project writes its own.
for file in "$HERE"/kevin/*.md "$HERE"/kevin/*.conf; do
  case "$(basename "$file")" in
    verify.conf) ;;
    *) copy_file "$file" "$target/kevin/$(basename "$file")" ;;
  esac
done
for directory in bin constitution languages roles; do
  copy_tree "$HERE/kevin/$directory" "$target/kevin/$directory"
done
for module in "$HERE"/kevin/tools/*; do
  for directory in src resources; do
    if [ -d "$module/$directory" ]; then
      copy_tree "$module/$directory" "$target/kevin/tools/$(basename "$module")/$directory"
    fi
  done
done
for module in csharp javascript ruby java; do
  copy_file "$HERE/kevin/tools/$module/bb.edn" "$target/kevin/tools/$module/bb.edn"
done
copy_tree "$HERE/.claude/skills" "$target/.claude/skills"
copy_tree "$HERE/.agents/skills" "$target/.agents/skills"
copy_tree "$HERE/.kilo/command" "$target/.kilo/command"
copy_tree "$HERE/.vibe/skills" "$target/.vibe/skills"

append_once() {
  local file="$1" line="$2"
  touch "$file"
  grep -qxF "$line" "$file" && return 0
  [ ! -s "$file" ] || [ "$(tail -c 1 "$file")" = "" ] || echo >> "$file"
  echo "$line" >> "$file"
}

# The old shipped commands; a project's own bin/sf-* files stay.
for name in sf sf-cs sf-fs sf-vb sf-js sf-rb sf-java sf-mistral sf-tool; do
  if [ -e "$target/kevin/bin/$name" ] || [ -L "$target/kevin/bin/$name" ]; then
    rm -f "$target/kevin/bin/$name"
  fi
done

old_swarm_line="Follow swarmforge/README.md and swarmforge/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in swarmforge/bin/sf."
swarm_line="Follow kevin/README.md and kevin/roles/orchestrator.md when asked to swarm a feature; delegate subagent roles by name and keep all task bookkeeping in kevin/bin/kevin."
if [ -f "$target/AGENTS.md" ] && grep -qxF "$old_swarm_line" "$target/AGENTS.md"; then
  if grep -qxF "$swarm_line" "$target/AGENTS.md"; then
    replacement=""
  else
    replacement="$swarm_line"
  fi
  rewritten="$(awk -v old="$old_swarm_line" -v new="$replacement" '$0 == old { if (new != "") print new; next } { print }' "$target/AGENTS.md")"
  printf '%s\n' "$rewritten" > "$target/AGENTS.md"
fi
append_once "$target/AGENTS.md" "$swarm_line"
# A project whose AGENTS.md links to CLAUDE.md (or the reverse) already loads
# the instructions; importing AGENTS.md there would import the file into itself.
[ "$target/CLAUDE.md" -ef "$target/AGENTS.md" ] || append_once "$target/CLAUDE.md" "@AGENTS.md"
mkdir -p "$target/.github"
append_once "$target/.github/copilot-instructions.md" "Follow AGENTS.md at the repository root."
append_once "$target/.gitignore" ".worktrees/"
append_once "$target/.gitignore" ".kevin/"
append_once "$target/.gitignore" "tmp/"
append_once "$target/.gitignore" ".metrics/"
append_once "$target/.gitignore" ".kilo/worktrees/"
append_once "$target/.gitignore" ".kilo/agent-manager.json"
# Keeps kevin/bin tracked when the project ignores every bin/ directory,
# as Visual Studio's "[Bb]in/" does. A file inside kevin/bin is never read
# under such a rule; "!/bin/**" also covers rules like "**/bin/**". The leading
# slash leaves other bin/ directories inside kevin/ ignored.
append_once "$target/kevin/.gitignore" "!/bin/"
append_once "$target/kevin/.gitignore" "!/bin/**"

(cd "$target" && ./kevin/bin/kevin adapters >/dev/null && ./kevin/bin/kevin init)

# Compare only payload destinations; unknown provenance never permits deletion.
# On a migration, rewrite the old names in every kept file under kevin/: the
# project-owned configuration and every unshipped file.
printf '%s\0' "${shipped[@]}" | (cd "$HERE" && bb -e '
  (require (quote [babashka.fs :as fs]) (quote [clojure.string :as str]))
  (let [root (fs/path (first *command-line-args*))
        expected (set (str/split (slurp *in*) #"\u0000"))
        caches #{".git" ".kevin" ".swarmforge" ".metrics" ".worktrees" ".cpcache" "node_modules" "tmp"}
        found (atom [])
        remember (fn [path _] (swap! found conj (str (fs/relativize root path))) :continue)
        walk (fn [directory] (fs/walk-file-tree directory
                               {:pre-visit-dir (fn [path _] (if (caches (fs/file-name path)) :skip-subtree :continue))
                                :visit-file remember}))
        migrating? (seq (second *command-line-args*))
        managed ["kevin/bin" "kevin/constitution" "kevin/languages"
                 "kevin/roles" "kevin/tools" ".claude/skills" ".agents/skills"
                 ".kilo/command" ".vibe/skills"]]
    (doseq [relative managed :let [directory (fs/path root relative)] :when (fs/exists? directory)]
      (walk directory))
    (doseq [path (fs/list-dir (fs/path root "kevin")) :when (not (fs/directory? path))]
      (remember path nil))
    (let [config? #(or (= % "kevin/.gitignore")
                       (re-matches #"kevin/[^/]+\.conf" %)
                       (= % "kevin/constitution/articles/project.md"))
          unshipped (sort (distinct @found))
          _ (when migrating? (walk (fs/path root "kevin")))
          kept (filter #(or (not (expected %)) (config? %)) (sort (distinct @found)))
          renames [[#"swarmforge/tools/sf/" "kevin/tools/kevin/"]
                   [#"swarmforge/bin/sf-" "kevin/bin/kevin-"]
                   [#"swarmforge/bin/sf\b" "kevin/bin/kevin"]
                   [#"\bsf-(cs|fs|vb|js|rb|java|mistral|tool)\b" "kevin-$1"]
                   [#"\bsf (init|new|route|ls|show|next|stage|harvest|start|handoff|ask|answer|review|approve|reject|spec-verdict|spec-revisions|finish|roles|pipeline|gates|routes|dispatch|run|adapters|check|commit-msg|tool|property)\b" "kevin $1"]
                   [#"`sf`" "`kevin`"]
                   [#"\bSwarmForge\b" "Kevin"]
                   [#"SWARMFORGE_" "KEVIN_"]
                   [#"swarmforge/" "kevin/"]
                   [#"(?<![\w./$-])sf(?![\w/-])(?!\.[A-Za-z0-9])" "kevin"]]]
      (when migrating?
        (doseq [path kept
                :when (str/starts-with? path "kevin/")
                :let [file (fs/path root path)]
                :when (and (fs/regular-file? file) (not (fs/sym-link? file)))
                :let [text (slurp (str file))
                      renamed (reduce (fn [t [from to]] (str/replace t from to)) text renames)]
                :when (not= text renamed)]
          (spit (str file) renamed)
          (println "install.sh: rewrote the old names in" path)))
      (doseq [path unshipped :when (not (or (expected path) (config? path)))]
        (println "UNSHIPPED (manual review; may be a user file):" path))))
  ' "$target" "$migrating")

cat <<EOF
Installed Kevin into $target.
  kevin/roles.conf            role catalogue (edit, then: kevin/bin/kevin adapters)
  kevin/routes.conf           named routes a task can take
  kevin/tiers.conf            model per tier per harness
  .claude/agents, .codex/agents, .github/agents, .kilo/agent, .vibe/agents   generated role agents
  /swarm <request>            start a task from Kilo, Claude Code, Codex, Copilot, or Vibe
EOF
