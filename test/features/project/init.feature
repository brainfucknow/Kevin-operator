Feature: kevin init sets up a project

  The project commits only its configuration. kevin init, run by the kevin
  on PATH (the plugin's runtime), writes kevin/project.md when it is
  missing: a starter project article that names no project of its own,
  with the lines "Existing code: refactor freely" and
  "Minimum Kevin version: <runtime version>". When kevin/project.md exists
  without a "Minimum Kevin version:" line, kevin init appends that line
  and changes nothing else in the file; when it has one, kevin init keeps
  the file as it is, whatever version it names.

  kevin init writes no other tracked file: no kevin/*.conf, no agents, no
  skills, no AGENTS.md, CLAUDE.md, .github/copilot-instructions.md or
  .gitignore. It adds the lines ".worktrees/", ".kevin/", "tmp/" and
  ".metrics/" to the repository's shared .git/info/exclude (the file that
  "git rev-parse --git-path info/exclude" names, which every worktree
  reads), each once. It installs the commit-msg hook in the shared hooks
  directory as before, and the hook runs the runtime's kevin by the
  absolute path of the kevin that ran kevin init, so commits in task
  worktrees get their byline when kevin is not on PATH. It prints the
  identity, roles and routes lines as before. A second run ends in the
  same state.

  A project with an old committed install (the index holds kevin/bin/kevin
  and kevin/constitution/articles/project.md) is refused: exit 1, the
  error line "kevin: this project has a committed Kevin install; run kevin
  init --convert", and nothing changed.

  Background:
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And a git repository with one commit and no kevin

  # kevin init sets up a project 1
  Scenario: kevin init sets up a project 1: a fresh project gets only kevin/project.md
    When I run "kevin init"
    Then the exit status is 0
    And git add -A in the repository stages exactly "kevin/project.md"
    And the file "kevin/project.md" in the repository has the line "Minimum Kevin version: 0.2.0" once
    And the file "kevin/project.md" in the repository has the line "Existing code: refactor freely"
    And the shared exclude file has the lines ".worktrees/", ".kevin/", "tmp/" and ".metrics/"
    And the commit-msg hook has the line "# kevin commit-msg hook"
    And the output has a line that starts with "roles: specifier"

  # kevin init sets up a project 2
  Scenario: kevin init sets up a project 2: a second run ends in the same state
    When I run "kevin init" 2 times
    Then the exit status is 0
    And git add -A in the repository stages exactly "kevin/project.md"
    And the shared exclude file has no line twice
    And the file "kevin/project.md" in the repository has the line "Minimum Kevin version: 0.2.0" once

  # kevin init sets up a project 3
  Scenario Outline: kevin init sets up a project 3: an existing project.md keeps its version line
    Given the file "kevin/project.md" in the repository holds "<before>"
    When I run "kevin init"
    Then the exit status is 0
    And the file "kevin/project.md" in the repository holds "<after>"

    Examples:
      | before                                     | after                                                                  |
      | # Mine\nMinimum Kevin version: 0.1.0       | # Mine\nMinimum Kevin version: 0.1.0                                   |
      | # Mine\nExisting code: ask before refactoring | # Mine\nExisting code: ask before refactoring\nMinimum Kevin version: 0.2.0 |

  # kevin init sets up a project 4
  Scenario: kevin init sets up a project 4: the hook signs a task commit when kevin is not on PATH
    Given I ran "kevin init"
    And I ran "git add -A" and "git commit -m config" in the repository
    And I ran "kevin new Login -m Users --route coder"
    And I ran "kevin start $A"
    And no directory on PATH holds kevin
    When I commit a change in the worktree of task "A" with the message "Add login"
    Then the exit status is 0
    And the last commit message in the worktree of task "A" has the line "By coder."

  # kevin init sets up a project 5
  Scenario: kevin init sets up a project 5: a committed install is refused
    Given the repository has a committed Kevin install
    When I run "kevin init"
    Then the exit status is 1
    And the error output has the line "kevin: this project has a committed Kevin install; run kevin init --convert"
    And the repository did not change
