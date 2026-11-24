Feature: a project changes the models of its role agents

  kevin/roles.conf and kevin/tiers.conf of the main checkout stay the
  source of truth, read at run time. "kevin agent <role> <harness>" is the
  single resolver: it tells the orchestrator how to run a role in the
  harness claude, copilot, codex or vibe. It prints "AGENT: <name>", then
  "MODEL: <alias>" when the orchestrator must pass that model on the
  delegation call, then one "WARNING:" line when the project agent it
  names is missing or stale. It exits 0 with a warning. "AGENT: none"
  means no agent: run the role through kevin dispatch. "AGENT: session"
  means a role that runs as session; the next line is "PROMPT: <path>",
  the absolute path of the prompt that the orchestrator reads: the
  runtime's roles/<role>.md for a runtime role, the main checkout's
  kevin/roles/<role>.md for an added role. An unknown harness or role is
  refused with exit 1.

  A role has a project agent for a harness when the agent that kevin
  generates for it from the project's configuration differs from the one
  it generates from the runtime's configuration. One difference needs no
  file: in claude, when only the model differs and the project's model is
  one of sonnet, opus, haiku and fable, kevin agent names the plugin
  agent and prints that model. The names are:

    claude   plugin agent kevin:<role>; project agent kevin-<role> in
             .claude/agents/kevin-<role>.md
    copilot  agent <role> in both cases; the project agent is
             .github/agents/<role>.agent.md
    codex    no plugin agent (none); project agent <role> in
             .codex/agents/<role>.toml
    vibe     plugin agent kevin:<role> for a runtime role, whatever the
             project's rows say; no project agent, no MODEL and no
             WARNING

  "kevin adapters" and "kevin init" write the project agents into the
  main checkout, from any worktree, and remove the project agents that
  are no longer needed. Each project agent has one line that holds
  "kevin-generated: <runtime version>". A project agent is stale when its
  text is not the text kevin adapters would write now. kevin adapters
  keeps each project agent path as one line in the shared exclude file,
  and removes the line with the file. It never changes a file without the
  kevin-generated line: when it needs that path it exits 1 and writes
  nothing. A second run ends in the same state. In the Kevin source
  checkout kevin adapters also regenerates the plugin agents as before.

  kevin check refuses a tiers.conf value that does not start with a letter
  or a digit, or that holds a character other than letters, digits and
  ". _ : / @ [ ] -".

  Background:
    Given a Kevin runtime at version "0.3.0" with its kevin on PATH
    And the file "kevin/tiers.conf" in the runtime holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast sonnet low gpt-b"
    And a git repository with one commit and no kevin
    And I ran "kevin init"

  # a project changes the models of its role agents 1
  Scenario Outline: a project changes the models of its role agents 1: kevin agent names the agent and the model to pass
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast <fast>"
    And I ran "kevin adapters"
    When I run "kevin agent cleaner <harness>"
    Then the exit status is 0
    And the output is "<printed>"

    Examples:
      | fast                       | harness | printed                           |
      | sonnet low gpt-b           | claude  | AGENT: kevin:cleaner              |
      | haiku low gpt-b            | claude  | AGENT: kevin:cleaner MODEL: haiku |
      | claude-haiku-5-5 low gpt-b | claude  | AGENT: kevin-cleaner              |
      | inherit low gpt-b          | claude  | AGENT: kevin-cleaner              |
      | sonnet high gpt-z          | claude  | AGENT: kevin:cleaner              |
      | sonnet low gpt-b           | copilot | AGENT: cleaner                    |
      | sonnet low gpt-z           | copilot | AGENT: cleaner                    |
      | sonnet low gpt-b           | codex   | AGENT: none                       |
      | sonnet high gpt-b          | codex   | AGENT: cleaner                    |

  # a project changes the models of its role agents 2
  Scenario Outline: a project changes the models of its role agents 2: a project roles.conf row changes the role
    Given the project roles.conf holds the runtime's with the row of "cleaner" as "<row>"
    When I run "kevin agent cleaner claude"
    Then the exit status is 0
    And the output is "<printed>"

    Examples:
      | row                     | printed                          |
      | cleaner strong subagent | AGENT: kevin:cleaner MODEL: opus |

  # a project changes the models of its role agents 3
  Scenario Outline: a project changes the models of its role agents 3: kevin adapters writes only the needed project agents, untracked
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast <fast>"
    When I run "kevin adapters" <where>
    Then the exit status is 0
    And the project agents are "<agents>"
    And git status in the repository shows no project agent

    Examples:
      | fast                       | where                         | agents                                                           |
      | haiku low gpt-b            | in the repository             | none                                                             |
      | claude-haiku-5-5 low gpt-b | in the repository             | .claude/agents/kevin-cleaner.md, .claude/agents/kevin-hardener.md |
      | sonnet low gpt-z           | in the repository             | .github/agents/cleaner.agent.md, .github/agents/hardener.agent.md |
      | sonnet high gpt-b          | in the repository             | .codex/agents/cleaner.toml, .codex/agents/hardener.toml           |
      | sonnet high gpt-b          | in the worktree of a new task | .codex/agents/cleaner.toml, .codex/agents/hardener.toml           |

  # a project changes the models of its role agents 4
  Scenario Outline: a project changes the models of its role agents 4: a project agent carries the project's model and the stamp
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-5 high gpt-z"
    When I run "kevin adapters"
    Then the exit status is 0
    And the file "<path>" in the repository has the line "<line>"
    And the file "<path>" in the repository has one line that holds "kevin-generated: 0.3.0"

    Examples:
      | path                            | line                            |
      | .claude/agents/kevin-cleaner.md | name: kevin-cleaner             |
      | .claude/agents/kevin-cleaner.md | model: claude-haiku-5-5         |
      | .github/agents/cleaner.agent.md | name: cleaner                   |
      | .github/agents/cleaner.agent.md | model: gpt-z                    |
      | .codex/agents/cleaner.toml      | name = "cleaner"                |
      | .codex/agents/cleaner.toml      | model_reasoning_effort = "high" |

  # a project changes the models of its role agents 5
  Scenario Outline: a project changes the models of its role agents 5: kevin agent warns about a missing or stale project agent
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-5 low gpt-b"
    And I ran "kevin adapters"
    And <event>
    When I run "kevin agent cleaner claude"
    Then the exit status is 0
    And the output is "AGENT: kevin-cleaner WARNING: .claude/agents/kevin-cleaner.md is <state>; run kevin adapters"

    Examples:
      | event                                                                                                                                                 | state   |
      | the file ".claude/agents/kevin-cleaner.md" in the repository is deleted                                                                               | missing |
      | the file ".claude/agents/kevin-cleaner.md" in the repository holds "mine"                                                                             | stale   |
      | the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-6 low gpt-b"           | stale   |
      | the runtime moves to version "0.3.1"                                                                                                                  | stale   |

  # a project changes the models of its role agents 6
  Scenario: a project changes the models of its role agents 6: kevin adapters brings a stale project agent up to date
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-5 low gpt-b"
    And I ran "kevin adapters"
    And the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-6 low gpt-b"
    And I ran "kevin adapters"
    When I run "kevin agent cleaner claude"
    Then the exit status is 0
    And the output is "AGENT: kevin-cleaner"
    And the file ".claude/agents/kevin-cleaner.md" in the repository has the line "model: claude-haiku-5-6"

  # a project changes the models of its role agents 7
  Scenario: a project changes the models of its role agents 7: kevin adapters keeps a file that kevin did not write
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast sonnet low gpt-z"
    And the file ".github/agents/hardener.agent.md" in the repository holds "mine"
    When I run "kevin adapters"
    Then the exit status is 1
    And the error output has the line "kevin: .github/agents/hardener.agent.md exists and kevin did not write it"
    And the repository has the file ".github/agents/hardener.agent.md" holding "mine"
    And the repository has no path ".github/agents/cleaner.agent.md"

  # a project changes the models of its role agents 8
  Scenario: a project changes the models of its role agents 8: kevin adapters removes a project agent that is no longer needed
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast sonnet low gpt-z"
    And I ran "kevin adapters"
    And the file "kevin/tiers.conf" in the repository is deleted
    And I ran "kevin adapters"
    And the file ".github/agents/cleaner.agent.md" in the repository holds "mine"
    When I run "kevin adapters"
    Then the exit status is 0
    And the project agents are "none"
    And the repository has the file ".github/agents/cleaner.agent.md" holding "mine"
    And git status in the repository shows the path ".github/agents/cleaner.agent.md"

  # a project changes the models of its role agents 9
  Scenario Outline: a project changes the models of its role agents 9: kevin init and a second run end in the same state
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast claude-haiku-5-5 low gpt-b"
    When I run "<command>" 2 times
    Then the exit status is 0
    And the project agents are ".claude/agents/kevin-cleaner.md, .claude/agents/kevin-hardener.md"
    And the shared exclude file has no line twice
    And git status in the repository shows no project agent

    Examples:
      | command        |
      | kevin init     |
      | kevin adapters |

  # a project changes the models of its role agents 10
  Scenario Outline: a project changes the models of its role agents 10: kevin agent refuses an unknown role or harness
    When I run "kevin agent <role> <harness>"
    Then the exit status is 1
    And the error output has the line "<refusal>"

    Examples:
      | role    | harness | refusal                                                      |
      | cleaner | kilo    | kevin: unknown harness 'kilo' (claude, copilot, codex, vibe) |
      | nobody  | claude  | kevin: unknown role 'nobody' (see kevin/roles.conf)          |
      | nobody  | vibe    | kevin: unknown role 'nobody' (see kevin/roles.conf)          |

  # a project changes the models of its role agents 11
  Scenario Outline: a project changes the models of its role agents 11: kevin check refuses an unsafe tiers.conf value
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast <fast>"
    When I run "kevin check"
    Then the exit status is <status>
    And the error output <has> the line "CHECK: tiers.conf: 'fast' has the unsafe value '<value>'"

    Examples:
      | fast                               | status | has           | value   |
      | --x low gpt-b                      | 1      | has           | --x     |
      | sonnet low gpt;b                   | 1      | has           | gpt;b   |
      | sonnet low $(id)                   | 1      | has           | $(id)   |
      | opus[1m] low provider/gpt-b@1:0    | 0      | does not have | opus[1m] |

  # a project changes the models of its role agents 12
  Scenario Outline: a project changes the models of its role agents 12: kevin agent names the prompt of a session role
    Given the project roles.conf holds the runtime's with the row of "cleaner" as "cleaner fast session"
    And the file "kevin/roles/cleaner.md" in the repository holds "# cleaner\n\nProject text."
    When I run "kevin agent cleaner <harness>"
    Then the exit status is 0
    And the output is "AGENT: session PROMPT: " and the absolute path of "kevin/roles/cleaner.md" in the runtime

    Examples:
      | harness |
      | claude  |
      | codex   |
      | vibe    |

  # a project changes the models of its role agents 13
  Scenario Outline: a project changes the models of its role agents 13: kevin agent names the plugin agent of a runtime role in vibe
    Given the file "kevin/tiers.conf" in the repository holds "frontier inherit high gpt-a\nstrong opus medium gpt-a\nfast <fast>"
    And I ran "kevin adapters"
    When I run "kevin agent cleaner vibe"
    Then the exit status is 0
    And the output is "AGENT: kevin:cleaner"
    And the repository has no path ".vibe/agents"

    Examples:
      | fast                        |
      | sonnet low gpt-b            |
      | claude-haiku-5-5 high gpt-z |
