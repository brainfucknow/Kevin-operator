Feature: kevin refuses a runtime older than the project needs

  The runtime's version is the "version" of the plugin's
  .claude-plugin/plugin.json; .codex-plugin/plugin.json and
  .github/plugin/plugin.json carry the same value. kevin version prints it.
  Versions are <major>.<minor>.<patch> and compare by number.

  Every kevin command except help, version and home reads the line
  "Minimum Kevin version: <version>" of the main checkout's
  kevin/project.md and refuses a runtime older than it: exit 1, the error
  line "kevin: this project needs Kevin <minimum> or newer; this runtime is
  <version> at <kevin home>. Run install.sh --user from a Kevin plugin at
  <minimum> or newer.", and nothing changed. <kevin home> is the path that
  kevin home prints. This holds in a task worktree and for
  the kevin commit-msg run by the hook. A project without kevin/project.md,
  or without that line, sets no minimum. A value that is not
  <major>.<minor>.<patch> is refused with the error line "kevin:
  kevin/project.md: Minimum Kevin version must be <major>.<minor>.<patch>,
  not '<value>'". Outside a git repository there is no project and no
  check: kevin help, kevin version and kevin home still work there.

  Background:
    Given a git repository with one commit and no kevin

  # kevin refuses a runtime older than the project needs 1
  Scenario Outline: kevin refuses a runtime older than the project needs 1: the minimum decides
    Given a Kevin runtime at version "<runtime>" with its kevin on PATH
    And the file "kevin/project.md" in the repository holds "# Mine\nMinimum Kevin version: <minimum>"
    When I run "kevin ls"
    Then the exit status is <status>
    And the error output <has> the line "kevin: this project needs Kevin <minimum> or newer; this runtime is <runtime> at <kevin home>. Run install.sh --user from a Kevin plugin at <minimum> or newer."

    Examples:
      | runtime | minimum | status | has           |
      | 0.2.0   | 0.3.0   | 1      | has           |
      | 0.2.0   | 0.2.1   | 1      | has           |
      | 0.2.0   | 0.2.0   | 0      | does not have |
      | 0.10.0  | 0.9.1   | 0      | does not have |

  # kevin refuses a runtime older than the project needs 2
  Scenario: kevin refuses a runtime older than the project needs 2: the refusal holds in a task worktree
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And I ran "kevin init"
    And I ran "git add -A" and "git commit -m config" in the repository
    And I ran "kevin new Login -m Users --route coder"
    And the file "kevin/project.md" in the repository holds "# Mine\nMinimum Kevin version: 1.0.0"
    When I run "kevin start $A" in the worktree of task "A"
    Then the exit status is 1
    And the error output has the line "kevin: this project needs Kevin 1.0.0 or newer; this runtime is 0.2.0 at <kevin home>. Run install.sh --user from a Kevin plugin at 1.0.0 or newer."

  # kevin refuses a runtime older than the project needs 3
  Scenario: kevin refuses a runtime older than the project needs 3: a malformed minimum is refused
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And the file "kevin/project.md" in the repository holds "Minimum Kevin version: soon"
    When I run "kevin ls"
    Then the exit status is 1
    And the error output has the line "kevin: kevin/project.md: Minimum Kevin version must be <major>.<minor>.<patch>, not 'soon'"

  # kevin refuses a runtime older than the project needs 4
  Scenario Outline: kevin refuses a runtime older than the project needs 4: commands that need no project work anywhere
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And the file "kevin/project.md" in the repository holds "Minimum Kevin version: 9.0.0"
    When I run "kevin <command>" <where>
    Then the exit status is 0

    Examples:
      | command | where                      |
      | version | in the repository          |
      | help    | in the repository          |
      | version | outside any git repository |
      | help    | outside any git repository |
