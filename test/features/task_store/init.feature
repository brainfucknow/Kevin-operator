Feature: kevin init without git-bug

  kevin init needs git and Babashka, not git-bug. It prints the identity that kevin
  gives its record commits: git's user.name and user.email, or, when git has
  none, KEVIN_USER (default operator) and KEVIN_EMAIL (default
  <user>@kevin.local).

  # kevin init without git-bug 1
  Scenario Outline: kevin init without git-bug 1: kevin init succeeds without git-bug and prints the identity of record commits
    Given a git repository with a copy of kevin and no git-bug on PATH
    And the git identity of the project is "<identity>"
    When I run "kevin init"
    Then the exit status is 0
    And the output has the line "identity: <printed>"
    And the output has the line "roles: specifier architecture-design-reviewer coder cleaner architect hardener security-reviewer pr-reviewer qa"

    Examples:
      | identity                    | printed                     |
      | tester <tester@example.com> | tester <tester@example.com> |

  # kevin init without git-bug 2
  Scenario: kevin init without git-bug 2: kevin init prints the default identity when git has none
    Given a git repository with a copy of kevin and no git-bug on PATH
    And the git identity of the project is "-"
    When I run "kevin init"
    Then the exit status is 0
    And the output has the line "identity: operator <operator@kevin.local>"
    And the output has the line "roles: specifier architecture-design-reviewer coder cleaner architect hardener security-reviewer pr-reviewer qa"
