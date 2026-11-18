# Clojure and Babashka tooling

The Clojure tools come from `swarmforge/tools.conf` and run through the
wrappers `sf tool ensure` writes to `.swarmforge/bin/`: clj-mutate for
mutation, crap4clj for CRAP, dry4clj for DRY. This file holds what a role
needs to know to run them on Babashka code.

## Mutation with clj-mutate

- Run clj-mutate from the worktree root. It keeps its manifest under
  `.metrics/mutate/` of the directory it runs from, and `sf start` and
  `sf handoff` load and save only the worktree root's.
- Do not give clj-mutate the test command `bb --config <path>/bb.edn spec`
  (or any `bb --config` test command). bb resolves the worker's symlinked
  `bb.edn` back to the real checkout, so it tests the unmutated sources and
  every mutant survives.
- Pass a `--test-command` that puts the worker's own copy of each `:paths`
  entry of the module's `bb.edn` on the classpath instead (for example `src`
  and `spec`, or `src`, `test`, `../core/src` and `../core/test`), with
  `--no-coverage`, which clj-mutate requires for a custom test command.

## Security scanners

- Code scanner: clj-holmes (`sf tool ensure clj-holmes`), run on each `.clj`
  file changed since the merge base, committed or not. It is unmaintained
  since 2022, has 9 security rules and reads `.clj` only, so changed `.cljc`,
  `.cljs` and `.bb` files are Not-tested. Fetching rules reaches
  api.github.com. Its defaults write to `/tmp` and the current directory;
  keep rules and output under `./tmp`:

  ```sh
  mkdir -p tmp
  clj-holmes fetch-rules -o tmp/clj-holmes-rules \
    -r git://clj-holmes/clj-holmes-rules#fbf5e2eeebe9a85e2dc568c04b482a50fc482a02
  clj-holmes scan -p <file> -d tmp/clj-holmes-rules -t json -o tmp/clj-holmes.json --fail-on-result
  ```

- Extra, encouraged but not blocking (not the code scanner): Semgrep on the
  changed `.clj`, `.cljc`, `.cljs` and `.edn` files (it skips `.bb`). Read and
  follow the [shared Semgrep setup](semgrep.md) with
  `--config tmp/semgrep-rules/clojure`.

- Dependency audit: nvd-clojure (`sf tool ensure nvd-clojure`). Run it only
  when the task changes a dependency declaration in a `deps.edn`, `bb.edn`
  or `project.clj` at any depth. List the dependency files changed since the
  merge base with `git diff --name-only <merge-base> -- <files>`, plus the
  untracked ones with `git ls-files --others --exclude-standard -- <files>`
  (`<files>` are pathspecs for the dependency files, for example `'*deps.edn'
  '*bb.edn' '*project.clj'`; `<merge-base>` is `git merge-base <base> HEAD`
  with `<base>` from `--base`, else `SWARMFORGE_BASE`, else `main`, else
  `master`). Compare each changed file with its merge-base version
  (`git show <merge-base>:<path>`) as parsed EDN (`clojure.edn/read-string`):
  for a `deps.edn` or `bb.edn` map, `:deps` merged with every alias's
  `:extra-deps`; for a `project.clj`, the `:dependencies` and `:plugins`
  vectors of the `defproject` form. The audit runs when those declarations
  differ from the merge-base version: any added, removed or
  version/coordinate change triggers. A new untracked dependency file always
  triggers. A file that does not parse on either side counts as changed.
  Edits that touch only other keys (`:paths`, `:extra-paths`, `:description`,
  `:main`, ...) do not trigger.
- The audit needs `NVD_API_TOKEN` and network access to the NVD API
  (services.nvd.nist.gov) and clojars.org. Check the prerequisites before
  running. When the audit is required but `NVD_API_TOKEN` is unset or empty,
  or either service is unreachable, record `Not-tested: dependency audit:
  absent, <reason>` and continue the review. The reason names the missing
  prerequisite, never the tool. Do not `sf ask`; do not block the task.
- Give a cloud or CI environment the token as the `NVD_API_TOKEN` environment
  variable; never commit it to Git. In GitHub Actions, add a repository or
  organization secret named `NVD_API_TOKEN` and expose it to the reviewer's
  step with `env: NVD_API_TOKEN: ${{ secrets.NVD_API_TOKEN }}`.
- Run the audit from `./tmp`, which keeps the project's `deps.edn` out of
  its `-Sdeps` and its config and report in `./tmp`. Pass the project
  classpath (`clojure -Spath`, `bb print-deps --format classpath` or
  `lein classpath`):

  ```sh
  mkdir -p tmp && cd tmp && nvd-clojure nvd-clojure.edn "$(cd .. && clojure -Spath)"
  ```

## Tool compatibility checks

When changing wrappers or upgrading their upstream tools, use disposable
projects under `tmp/`, real GitHub sources and Maven dependencies. An upgrade
edits the ref column of `swarmforge/tools.conf` (`git ls-remote
https://github.com/<owner>/<repo>.git HEAD` prints a SHA); run these checks at
the new refs before the upgrade commit:

- Install and require `dry4clj`, `clj-mutate`, `crap4clj` and the APS tools
  through `sf tool`. Confirm DRY output, mutation scan counts and parser
  output with real inputs; fixture tests alone cannot detect upstream drift.
  A failed ensure from an older task catalogue must leave previously installed
  shared wrappers usable with their real upstream sources.
- Check Babashka Cloverage with both `clojure.test` and Speclj projects,
  including the fallback without Clojure CLI. Require a nonzero test count,
  successful tests, LCOV and a CRAP report for the intended source namespace.
- Compare `crap4clj` from the module directory with a repository-root run
  using `--source-root <module>/src`. Test counts and CRAP results must agree;
  coverage and `.metrics/crap.edn` belong to the module, with no unrelated
  root tests or root outputs. An invalid source root must fail before coverage.
- Explicit `--lcov` and `--use-existing-coverage` choices must not trigger
  another coverage run. Keep generated output and verification detail out of
  commits; record each tool's `@<ref>`, outcomes and unavailable
  checks in Lore.
