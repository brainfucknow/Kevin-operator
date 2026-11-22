Feature: kevin hook record trailers

  Only kevin writes Kevin-* trailers. In a task worktree, at every stage,
  the commit-msg hook (kevin commit-msg) refuses a commit whose trailer block has
  a key that starts with "Kevin-" in any letter case. It prints "kevin:
  <key> is a Kevin record trailer; only kevin writes those" on stderr,
  exits 1, and the commit is not made. A "Kevin-" line outside the
  trailer block is allowed. Commits in the main checkout are not checked.

  Background:
    Given a project with kevin initialized
    And task "A" titled "Add login" was started on route "coder"

  # kevin hook record trailers 1
  Scenario Outline: kevin hook record trailers 1: a task worktree commit with a Kevin trailer is refused
    When I commit "Fix\n\n<key>: done" in the worktree of task "A" with KEVIN_ROLE "coder"
    Then the exit status is 1
    And the error output has the line "kevin: <printed> is a Kevin record trailer; only kevin writes those"
    And the branch of task "A" did not move

    Examples:
      | key              | printed          |
      | Kevin-Stage | Kevin-Stage |
      | kevin-needs | kevin-needs |
      | KEVIN-TASK  | KEVIN-TASK  |

  # kevin hook record trailers 2
  Scenario: kevin hook record trailers 2: a Kevin line outside the trailer block is allowed
    When I commit "Fix\n\nKevin-Stage: done\n\nClosing text" in the worktree of task "A"
    Then the exit status is 0

  # kevin hook record trailers 3
  Scenario: kevin hook record trailers 3: kevin commit-msg refuses the message file and leaves it unchanged
    Given the file "tmp/msg" in the worktree of task "A" is written with "Fix\n\nKevin-Stage: done"
    When I run "kevin commit-msg coder tmp/msg" in the worktree of task "A"
    Then the exit status is 1
    And the error output has the line "kevin: Kevin-Stage is a Kevin record trailer; only kevin writes those"
    And the file "tmp/msg" in the worktree of task "A" still holds "Fix\n\nKevin-Stage: done"

  # kevin hook record trailers 4
  Scenario: kevin hook record trailers 4: a Kevin trailer after another trailer is refused
    When I commit "Fix\n\nTested: x\nkevin-needs: none" in the worktree of task "A" with KEVIN_ROLE "coder"
    Then the exit status is 1
    And the error output has the line "kevin: kevin-needs is a Kevin record trailer; only kevin writes those"
    And the branch of task "A" did not move

  # kevin hook record trailers 5
  Scenario: kevin hook record trailers 5: the refusal holds at stage done
    When I commit "Fix\n\nKevin-Task: x" in the worktree of task "A" with KEVIN_ROLE "done"
    Then the exit status is 1
    And the error output has the line "kevin: Kevin-Task is a Kevin record trailer; only kevin writes those"
    And the branch of task "A" did not move

  # kevin hook record trailers 6
  Scenario: kevin hook record trailers 6: a commit in the main checkout is not checked
    When I commit "Fix\n\nKevin-Stage: done" in the main checkout
    Then the exit status is 0
