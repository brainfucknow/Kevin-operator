Feature: kevin reads project configuration and runtime text apart

  Configuration comes from the project, role text from the runtime. For
  each of roles.conf, routes.conf, tiers.conf and tools.conf, kevin reads
  the main checkout's kevin/<file> when the project has it, else the
  runtime's kevin/<file>. kevin/verify.conf comes only from the project;
  without one, kevin finish runs no checks, and the runtime's verify.conf
  is never run. Role prompts, the constitution and its articles other than
  the project article, the language guides and the tool sources come only
  from the runtime; a project's kevin/roles/ is never read. The project
  article is the project's kevin/project.md.

  A project roles.conf may narrow the roles but not add one: kevin check
  (and every command that runs it) refuses a role the runtime's roles.conf
  lacks with the line "CHECK: roles.conf: '<role>' is not a role of the
  Kevin runtime".

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
    And the main checkout was put on the new branch "work"
    When I run "kevin finish $A"
    Then the exit status is 0
    And the branch "work" has the commit of task "A"

  # kevin reads project configuration and runtime text apart 3
  Scenario: kevin reads project configuration and runtime text apart 3: a project role prompt is not read
    Given the file "kevin/roles.conf" in the repository holds the runtime's roles.conf with the row "designer strong subagent"
    And the file "kevin/roles/designer.md" in the repository holds "# designer"
    When I run "kevin check"
    Then the exit status is 1
    And the error output has the line "CHECK: roles.conf: 'designer' is not a role of the Kevin runtime"

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
