Feature: kevin reads project configuration and runtime text apart

  Configuration comes from the project, role text from the runtime. For
  each of roles.conf, routes.conf, tiers.conf and tools.conf, kevin reads
  the main checkout's kevin/<file> when the project has it, else the
  runtime's kevin/<file>. kevin/verify.conf comes only from the project;
  without one, kevin finish runs no checks, and the runtime's verify.conf
  is never run. The constitution and its articles other than the project
  article, the language guides, the tool sources and the prompts of the
  runtime's roles come only from the runtime. The project article is the
  project's kevin/project.md. A project adds a role with its own prompt
  (see project_roles.feature).

  kevin home prints the absolute path of the runtime's kevin/ directory,
  in the main checkout, in a task worktree, and outside any git
  repository. Agents and skills find the constitution and role prompts
  through it.

  Background:
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And a git repository with one commit and no kevin
    And I ran "kevin init"

  # kevin reads project configuration and runtime text apart 1
  Scenario Outline: kevin reads project configuration and runtime text apart 1: a project file overrides the runtime's
    Given the file "kevin/routes.conf" in the runtime holds "<runtime>"
    And the project routes.conf <state>
    When I run "kevin routes"
    Then the exit status is 0
    And the output is "<printed>"

    Examples:
      | runtime                  | state                          | printed           |
      | feature specifier,coder,qa | is missing                   | feature    specifier,coder,qa |
      | feature specifier,coder,qa | holds "quick coder,qa"       | quick      coder,qa |

  # kevin reads project configuration and runtime text apart 2
  Scenario: kevin reads project configuration and runtime text apart 2: the runtime's verify.conf is never run
    Given the file "kevin/verify.conf" in the runtime holds "false"
    And I ran "git add -A" and "git commit -m config" in the repository
    And task "A" titled "Add login" is at stage "done" with one commit
    When I run "kevin finish $A"
    Then the exit status is 0
    And the base branch has the commit of task "A"

  # kevin reads project configuration and runtime text apart 4
  Scenario Outline: kevin reads project configuration and runtime text apart 4: kevin home names the runtime
    When I run "kevin home" <where>
    Then the exit status is 0
    And the output is the runtime's absolute "kevin" directory

    Examples:
      | where                          |
      | in the repository              |
      | in the worktree of a new task  |
      | outside any git repository     |
