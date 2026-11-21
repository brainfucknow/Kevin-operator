# Project Rules

## Project Shape
- This project runs Kevin roles as harness agents. `kevin/roles.conf` is the role catalogue in canonical order; `kevin/routes.conf` names the routes a task can take through it; `kevin/tiers.conf` maps each role's tier to a model per harness.
- Project language: Babashka (Clojure) for `kevin` and the tools; Python for `kevin/workflows/` (the Mistral Workflows project).
- Existing code: refactor freely. The opt-in value is `ask before refactoring`, which requires the [existing-code refactoring policy](../existing-code.md); a project opts in by changing this line. A missing line means `refactor freely`.

## Local Configuration
- Keep Kevin configuration under `kevin/`, task worktrees under `.worktrees/`, and task state in the `swarm/*` branches and the `kevin finish` merges. The orphan branch `kevin/metrics` caches mutation manifests. Nothing else is swarm state.
- `.worktrees/` and `./tmp/` are ignored by git and never committed.

## Verification
- After installation, record the application's public interfaces and verification commands here, or link to maintained project documentation that defines them.
- Apply the engineering article's target-based verification and evidence-reuse rules. Fix applicable failures before handoff.
- Each language guide in `kevin/languages/` names a security code scanner and a dependency audit. A project without one adds a line here per absent tool, with its reason:
  - `Security code scanner absent: <reason>`
  - `Dependency audit absent: <reason>`
- The architect, hardener, and QA own property-test verification for affected behavior; keep it separate from normal unit, coverage, and mutation runs.
- `kevin finish` runs the kevin property suite (`bb --config kevin/tools/sf/bb.edn property`) on every task's merge candidate through `kevin/verify.conf`.
- Security code scanner absent for `kevin/workflows/`: no Python scanner is installed in this project; the kevin tools cover the Clojure code.
- Dependency audit absent for `kevin/workflows/`: its Python dependencies are pinned in `kevin/workflows/uv.lock`; no audit tool is configured.
- Dependency audit absent without `NVD_API_TOKEN` or network access to the NVD API and clojars.org: the security reviewer records `Not-tested: dependency audit: absent, <reason>` and continues; see the Clojure guide to provide the token.

## Handoffs
- Prefer terse, explicit handoffs that report state. Do not include verification transcripts or process narrative in the summary.

## Ownership
- Do not change another role's prompt or workflow ownership without explicit operator direction.
