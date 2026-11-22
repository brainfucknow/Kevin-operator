# Project Rules

## Project Shape
- Project language: name the language and build tool here.
- Existing code: refactor freely, or `ask before refactoring` to require
  the existing-code refactoring policy. Keep the value on its own line:

Existing code: refactor freely

## Local Configuration
- `kevin init` wrote this file. Commit it. A project may also commit its
  own `kevin/roles.conf`, `kevin/routes.conf`, `kevin/tiers.conf`,
  `kevin/tools.conf` and `kevin/verify.conf`; kevin reads the runtime's
  copy of each catalogue file the project leaves out.
- Task worktrees live under `.worktrees/`. `kevin init` keeps `.worktrees/`,
  `.kevin/`, `tmp/` and `.metrics/` out of git through `.git/info/exclude`.

## Verification
- Record the application's public interfaces and verification commands
  here, or link to maintained project documentation that defines them.
- List in `kevin/verify.conf` the commands `kevin finish` runs on each
  merge candidate.
- Each language guide names a security code scanner and a dependency
  audit. Add a line here per absent tool, with its reason:
  - `Security code scanner absent: <reason>`
  - `Dependency audit absent: <reason>`

## Handoffs
- Prefer terse, explicit handoffs that report state.

## Ownership
- Do not change another role's prompt or workflow ownership without
  explicit operator direction.

## Kevin Runtime
- `kevin` refuses a runtime older than this version:

