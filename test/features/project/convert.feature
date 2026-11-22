Feature: kevin init --convert turns a committed install into configuration

  A committed install is a project whose index holds kevin/bin/kevin and
  kevin/constitution/articles/project.md, as the removed "install.sh
  <project>" left it. kevin init --convert, run by the kevin on PATH:

  - refuses, with exit 1, the error line "kevin: commit or stash your
    changes before kevin init --convert" and nothing changed, when a
    tracked file has staged or unstaged changes;
  - moves kevin/constitution/articles/project.md to kevin/project.md;
  - removes from the index and the working tree the committed runtime:
    kevin/bin/, kevin/tools/, kevin/roles/, kevin/languages/,
    kevin/constitution/ and the files kevin/*.md other than
    kevin/project.md;
  - removes the lines "!/bin/" and "!/bin/**" from kevin/.gitignore;
  - removes each committed agent under .claude/agents/, .github/agents/,
    .codex/agents/, .kilo/agent/ and .vibe/agents/ that is Kevin's: its text
    equals what this runtime generates for a role of the project's
    committed roles.conf and tiers.conf, or its digest is recorded in
    .kevin/adapters.edn. Every other agent stays, and for each one under a
    role name of roles.conf it prints "kevin: kept <path>; it is not
    Kevin's generated agent";
  - removes the project swarm skill and command: .claude/skills/swarm/,
    .agents/skills/swarm/, .vibe/skills/swarm/ and .kilo/command/swarm.md;
  - removes the Kevin swarm line (and its old swarmforge form) from
    AGENTS.md. When that leaves AGENTS.md empty, it deletes AGENTS.md and
    removes the line "@AGENTS.md" from CLAUDE.md and the line "Follow
    AGENTS.md at the repository root." from .github/copilot-instructions.md,
    deleting each file that this leaves empty;
  - keeps every other file, kevin/*.conf and kevin/verify.conf included, and
    in kept kevin/*.conf files other than tools.conf, and in
    kevin/project.md, replaces "kevin/bin/kevin-<name>" with "kevin <name>"
    and "kevin/bin/kevin" with "kevin". The replaced text includes a "./"
    before the path, and the word "bash", "sh" or "bb" when only spaces
    stand between that word and the path. It prints "kevin: rewrote <path>"
    for each file it changed;
  - then does what kevin init does: the minimum version line, the shared
    exclude lines and the hook.

  It stages its changes and does not commit; HEAD does not move. Run again
  on the converted project, it ends in the same state, as kevin init does.

  Background:
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And a git repository with a committed Kevin install

  # kevin init --convert turns a committed install into configuration 1
  Scenario: kevin init --convert turns a committed install into configuration 1: only configuration stays
    Given the committed file "kevin/routes.conf" holds "mine coder,qa"
    And the committed file "kevin/constitution/articles/project.md" holds "# Mine"
    When I run "kevin init --convert"
    Then the exit status is 0
    And HEAD did not move
    And the index has no path under "kevin/bin", "kevin/tools", "kevin/roles", "kevin/languages" or "kevin/constitution"
    And the index has no path "kevin/README.md", "kevin/constitution.md" or "kevin/handoff-protocol.md"
    And the index has no path under ".claude/agents", ".github/agents", ".codex/agents", ".kilo/agent" or ".vibe/agents"
    And the index has no path under ".claude/skills/swarm", ".agents/skills/swarm" or ".vibe/skills/swarm"
    And the index has no path ".kilo/command/swarm.md"
    And the file "kevin/routes.conf" in the repository holds "mine coder,qa"
    And the file "kevin/project.md" in the repository holds "# Mine\nMinimum Kevin version: 0.2.0"
    And git add -A in the repository stages nothing more

  # kevin init --convert turns a committed install into configuration 2
  Scenario Outline: kevin init --convert turns a committed install into configuration 2: agents that are not Kevin's stay
    Given the committed file "<path>" holds "<text>"
    When I run "kevin init --convert"
    Then the exit status is 0
    And the file "<path>" in the repository holds "<text>"
    And the output <has> the line "kevin: kept <path>; it is not Kevin's generated agent"

    Examples:
      | path                      | text              | has           |
      | .claude/agents/coder.md   | my own coder      | has           |
      | .claude/agents/helper.md  | my own helper     | does not have |

  # kevin init --convert turns a committed install into configuration 3
  Scenario Outline: kevin init --convert turns a committed install into configuration 3: instruction files lose the Kevin lines
    Given the committed file "AGENTS.md" holds "<agents>"
    When I run "kevin init --convert"
    Then the exit status is 0
    And the repository <agents-state>
    And the repository <claude-state>

    Examples:
      | agents                                  | agents-state                                 | claude-state                           |
      | SWARM                                   | has no path "AGENTS.md"                      | has no path "CLAUDE.md"                |
      | Project notes.\nSWARM                   | has the file "AGENTS.md" holding "Project notes." | has the file "CLAUDE.md" holding "@AGENTS.md" |

  # kevin init --convert turns a committed install into configuration 4
  Scenario Outline: kevin init --convert turns a committed install into configuration 4: config that names kevin/bin is rewritten
    Given the committed file "kevin/verify.conf" holds "<before>"
    When I run "kevin init --convert"
    Then the exit status is 0
    And the file "kevin/verify.conf" in the repository holds "<after>"
    And the output has the line "kevin: rewrote kevin/verify.conf"

    Examples:
      | before                                         | after                             |
      | kevin/bin/kevin check\n./kevin/bin/kevin-js crap | kevin check\nkevin js crap         |
      | bash kevin/bin/kevin-cs test                   | kevin cs test                     |
      | sh  ./kevin/bin/kevin-rb crap                  | kevin rb crap                     |
      | bb kevin/bin/kevin help                        | kevin help                        |
      | make && bash kevin/bin/kevin-cs test           | make && kevin cs test             |
      | bash test/all.sh && kevin/bin/kevin check      | bash test/all.sh && kevin check   |
      | rebash kevin/bin/kevin-cs test                 | rebash kevin cs test              |

  # kevin init --convert turns a committed install into configuration 5
  Scenario: kevin init --convert turns a committed install into configuration 5: uncommitted changes are refused
    Given the committed file "kevin/routes.conf" was changed and not committed
    When I run "kevin init --convert"
    Then the exit status is 1
    And the error output has the line "kevin: commit or stash your changes before kevin init --convert"
    And the repository did not change

  # kevin init --convert turns a committed install into configuration 6
  Scenario: kevin init --convert turns a committed install into configuration 6: a second run changes nothing more
    When I run "kevin init --convert" 2 times
    Then the exit status is 0
    And the shared exclude file has no line twice
    And the file "kevin/project.md" in the repository has the line "Minimum Kevin version: 0.2.0" once

  # kevin init --convert turns a committed install into configuration 7
  Scenario: kevin init --convert turns a committed install into configuration 7: a task started before the conversion still finishes
    Given task "A" titled "Add login" was started on route "coder" before the conversion
    And task "A" has a new commit in its worktree that does not touch kevin/
    When I run "kevin init --convert"
    And I commit every change in the main checkout
    And I run "kevin handoff $A -m done"
    And I run "kevin finish $A"
    Then the exit status is 0
    And the base branch has the commit of task "A"
    And the index has no path under "kevin/bin"
