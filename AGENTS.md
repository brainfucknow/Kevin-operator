# Agent instructions

This repository runs Kevin: AI roles that pass work through task
branches, each task on its own route through the role catalogue. Read
`kevin/README.md` for the model.

- Install the runtime once per user: `./install.sh --user` in this checkout
  puts `kevin` on PATH. `kevin home` prints the runtime directory.
- The operator's session is the orchestrator. Follow
  `<kevin home>/roles/orchestrator.md` when asked to swarm, specify, or forge a
  feature, or when a task id is mentioned.
- Every role, the specifier included, is an agent of the Kevin plugin:
  `agents/*.md` for Claude Code and `.github/plugin/agents/*.agent.md` for
  Copilot CLI. `kevin adapters` generates them. Delegate to them by role
  name; do not do their work in the session. Other harnesses run roles
  through `kevin dispatch`.
- All task bookkeeping goes through `kevin`. Never write a
  `Kevin-*` trailer by hand.
- Roles that need a decision stop and ask in chat. Do not guess.

## Prose and tests

- Follow the ban on tests of Kevin prompt text in the Verification
  section of
  [the engineering article](kevin/constitution/articles/engineering.md#verification).
  Test `kevin` behavior (`test/kevin_test.sh`).
- Write tracked documentation and Kevin prompt text in simplified
  technical English: short sentences, active voice, common words, no
  filler. Use the imperative for instructions. Change a generated
  adapter through its template. This rule and the next do not apply to
  code comments, commit messages, Lore trailers, or `kevin` records.
- Keep that text dry and to the point. Say each rule once, where it
  belongs. Give no rationale or history that an agent cannot act on.
