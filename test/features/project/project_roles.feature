Feature: a project adds roles

  A project adds a role with a row in its kevin/roles.conf and the prompt
  kevin/roles/<role>.md. An added role is a stage or a gate. Its row
  follows the rules of every roles.conf row, and file order sets its place
  in a route. kevin check refuses an added role without a prompt, an added
  role whose prompt lacks a title line and a summary line, and an added
  role flagged spec-judge. kevin check reads the prompt in the checkout
  whose roles.conf it validates; every other command reads the main
  checkout's.

  A project cannot replace the prompt of a runtime role: for a role that
  the runtime's roles.conf has, kevin reads only the runtime's prompt, and
  a project file of that name is never read.

  kevin dispatch runs an added role like a runtime role; its prompt names
  the absolute path of the main checkout's kevin/roles/<role>.md. An added
  subagent role has a project agent in every harness (see the project
  agents feature). The agent of an added gate lists the same tools as the
  agent of a runtime gate. The summary line of the prompt becomes part of
  the agent's description as the same text, whatever characters it holds.

  Background:
    Given a Kevin runtime at version "0.3.0" with its kevin on PATH
    And a git repository with one commit and no kevin
    And I ran "kevin init"

  # a project adds roles 1
  Scenario: a project adds roles 1: an added stage joins the catalogue and a route
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "designer strong subagent"
    And the file "kevin/roles/designer.md" in the repository holds "# designer\n\nDraws the screens."
    And I ran "kevin check"
    And I ran "git add -A" and "git commit -m config" in the repository
    And I ran "kevin new Login -m Users --route coder,designer"
    When I run "kevin route $A"
    Then the exit status is 0
    And the output is "coder,designer"

  # a project adds roles 2
  Scenario Outline: a project adds roles 2: kevin check refuses a broken added role
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "<row>"
    And the file "kevin/roles/<file>.md" in the repository holds "<prompt>"
    When I run "kevin check"
    Then the exit status is 1
    And the error output has the line "CHECK: <problem>"

    Examples:
      | row                                 | file     | prompt                | problem                                                                    |
      | designer strong subagent            | other    | # other\n\nDraws.     | kevin/roles/designer.md: missing role prompt for 'designer'                |
      | designer strong subagent            | designer | # designer            | kevin/roles/designer.md: needs a title line and a summary line             |
      | designer strong subagent spec-judge | designer | # designer\n\nJudges. | roles.conf: 'designer' is a spec-judge; only a runtime role can be         |

  # a project adds roles 3
  Scenario Outline: a project adds roles 3: kevin dispatch names the prompt of the role
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "designer strong subagent"
    And the file "kevin/roles/designer.md" in the repository holds "# designer\n\nDraws the screens."
    And the file "kevin/roles/coder.md" in the repository holds "# coder\n\nProject text."
    And I ran "git add -A" and "git commit -m config" in the repository
    And I ran "kevin new Login -m Users --route coder,designer"
    And a claude on PATH that records its arguments
    When I run "kevin dispatch <role> $A claude"
    Then the exit status is 0
    And the recorded prompt names the absolute path of "kevin/roles/<role>.md" in the <place>

    Examples:
      | role     | place      |
      | designer | repository |
      | coder    | runtime    |

  # a project adds roles 4
  Scenario Outline: a project adds roles 4: an added role has a project agent in every harness
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "<role> strong subagent<flag>"
    And the file "kevin/roles/<role>.md" in the repository holds "# <role>\n\nDraws the screens."
    And I ran "kevin adapters"
    When I run "kevin agent <role> <harness>"
    Then the exit status is 0
    And the output is "AGENT: <agent>"
    And the project agents are ".claude/agents/kevin-<role>.md, .github/agents/<role>.agent.md, .codex/agents/<role>.toml"

    Examples:
      | role     | flag  | harness | agent          |
      | designer |       | claude  | kevin-designer |
      | designer |       | copilot | designer       |
      | designer |       | codex   | designer       |
      | critic   |  gate | claude  | kevin-critic   |

  # a project adds roles 5
  Scenario: a project adds roles 5: an added gate is a gate a task can choose, with the tools of a gate
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "critic strong subagent gate"
    And the file "kevin/roles/critic.md" in the repository holds "# critic\n\nAudits the specification."
    And I ran "kevin adapters"
    When I run "kevin gates"
    Then the exit status is 0
    And the output has the line "critic"
    And the file ".claude/agents/kevin-critic.md" in the repository has the line "tools: Read, Grep, Glob, Bash"

  # a project adds roles 6
  Scenario Outline: a project adds roles 6: the summary of the prompt reaches the description unchanged
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "designer strong subagent"
    And the file "kevin/roles/designer.md" in the repository holds "# designer\n\n<summary>"
    When I run "kevin adapters"
    Then the exit status is 0
    And the "description" of the project agent "<path>" contains "<summary>"

    Examples:
      | summary                             | path                             |
      | Draws: screens # and 'flows'.       | .claude/agents/kevin-designer.md |
      | Draws: screens # and 'flows'.       | .github/agents/designer.agent.md |
      | Draws \ screens. model: opus        | .codex/agents/designer.toml      |
      | - [x] {draws}                       | .claude/agents/kevin-designer.md |
