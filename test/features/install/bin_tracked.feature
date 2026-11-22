Feature: install.sh keeps kevin/bin tracked

  A project's .gitignore may ignore every bin/ directory, as the standard
  Visual Studio .gitignore does with "[Bb]in/". install.sh writes
  kevin/.gitignore so that git still sees every file it installs under
  kevin/bin. The project's own .gitignore gains no negation rule, and
  other bin/ directories stay ignored. A rule that ignores kevin/
  itself is out of reach of a file inside it and is not covered.

  Background:
    Given a git repository with one commit and no kevin

  # install.sh keeps kevin/bin tracked 1
  Scenario Outline: install.sh keeps kevin/bin tracked 1: files under kevin/bin are staged under a bin/ ignore rule
    Given the file ".gitignore" in the repository holds "<rule>"
    And the file "src/App/bin/App.dll" in the repository holds "x"
    When I run install.sh into it 1 times
    Then the exit status is 0
    And git add -A in the repository stages every file under "kevin/bin"
    And git add -A in the repository stages no file under "src/App/bin"
    And the file ".gitignore" in the repository has no line that starts with "!"

    Examples:
      | rule        |
      | [Bb]in/     |
      | bin/        |
      | bin         |
      | **/[Bb]in/* |
      | **/bin/**   |

  # install.sh keeps kevin/bin tracked 2
  Scenario: install.sh keeps kevin/bin tracked 2: a second run adds no line to kevin/.gitignore twice
    Given the file ".gitignore" in the repository holds "[Bb]in/"
    When I run install.sh into it 2 times
    Then the exit status is 0
    And the file "kevin/.gitignore" in the repository has no line twice
    And git add -A in the repository stages every file under "kevin/bin"

  # install.sh keeps kevin/bin tracked 3
  Scenario: install.sh keeps kevin/bin tracked 3: lines already in kevin/.gitignore are kept
    Given the file ".gitignore" in the repository holds "[Bb]in/"
    And the file "kevin/.gitignore" in the repository holds "scratch/"
    When I run install.sh into it 1 times
    Then the exit status is 0
    And the file "kevin/.gitignore" in the repository has the line "scratch/"
    And git add -A in the repository stages every file under "kevin/bin"
