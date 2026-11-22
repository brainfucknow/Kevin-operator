Feature: kevin record commits

  Every kevin command that changes a task, except kevin finish, adds commits to
  the task branch that change no file (kevin task done names the one exception,
  for a task created before this change). Each is a record. Its message is
  "kevin <record>: <id>", then a blank line and the free text when the record
  has any, then a blank line and the trailers:
  Kevin-Task, Kevin-Record, Kevin-From, and the state after
  the record as Kevin-Stage, Kevin-Route and Kevin-Needs (none,
  operator or approval). A review record ends with Kevin-Reviewed, the
  full SHA of the commit it reviewed. Free text keeps its newlines. Several
  arguments are joined with spaces.

  A record never touches the files or the index of a worktree: staged and
  unstaged changes stay as they were. A command that is refused adds no
  commit.

  Background:
    Given a project with kevin initialized
    And task "A" titled "Add login" was started on route "specifier,coder,qa"

  # kevin record commits 1
  Scenario Outline: kevin record commits 1: a command adds one record commit that changes no file
    When I run "<command>"
    Then the exit status is 0
    And the tip commit of task "A" changes no file
    And the tip commit of task "A" has the message "kevin <record>: $A<text>\n\nKevin-Task: $A\nKevin-Record: <record>\nKevin-From: specifier\nKevin-Stage: specifier\nKevin-Route: specifier,coder,qa\nKevin-Needs: <needs><reviewed>"

    Examples:
      | command                 | record   | text            | needs    | reviewed                                |
      | kevin ask $A Which hash?   | question | \n\nWhich hash? | operator |                                         |
      | kevin review $A spec ready | review   | \n\nspec ready  | approval | \nKevin-Reviewed: $A:created-sha |

  # kevin record commits 2
  Scenario: kevin record commits 2: kevin answer adds an answer record from the operator
    Given I ran "kevin ask $A Which"
    When I run "kevin answer $A argon2id"
    Then the exit status is 0
    And the tip commit of task "A" changes no file
    And the tip commit of task "A" has the message "kevin answer: $A\n\nargon2id\n\nKevin-Task: $A\nKevin-Record: answer\nKevin-From: operator\nKevin-Stage: specifier\nKevin-Route: specifier,coder,qa\nKevin-Needs: none"

  # kevin record commits 3
  Scenario Outline: kevin record commits 3: a command after a review adds one record commit from the operator
    Given I ran "kevin review $A"
    When I run "<command>"
    Then the exit status is 0
    And the tip commit of task "A" changes no file
    And the tip commit of task "A" has the message "kevin <record>: $A<text>\n\nKevin-Task: $A\nKevin-Record: <record>\nKevin-From: operator\nKevin-Stage: specifier\nKevin-Route: <route>\nKevin-Needs: <needs>"

    Examples:
      | command                  | record            | text         | route              | needs    |
      | kevin reject $A split it    | changes-requested | \n\nsplit it | specifier,coder,qa | none     |
      | kevin route $A specifier,qa | route             |              | specifier,qa       | approval |

  # kevin record commits 4
  Scenario: kevin record commits 4: free text keeps its newlines
    When I run "kevin ask $A" with the text "Which hash?\n\n1) bcrypt\n2) argon2id"
    Then the exit status is 0
    And the tip commit of task "A" has the message "kevin question: $A\n\nWhich hash?\n\n1) bcrypt\n2) argon2id\n\nKevin-Task: $A\nKevin-Record: question\nKevin-From: specifier\nKevin-Stage: specifier\nKevin-Route: specifier,coder,qa\nKevin-Needs: operator"

  # kevin record commits 5
  Scenario: kevin record commits 5: kevin handoff adds a handoff record on the role's commit
    Given role "specifier" committed "Spec" in the worktree of task "A" as C1
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And role "coder" committed "Code" in the worktree of task "A" as C2
    When I run "kevin handoff $A -m code done"
    Then the exit status is 0
    And the output is "HANDOFF: $A coder -> qa at C2"
    And the parent of the tip commit of task "A" is C2
    And the tip commit of task "A" changes no file
    And the tip commit of task "A" has the message "kevin handoff: $A\n\ncode done\n\nKevin-Task: $A\nKevin-Record: handoff\nKevin-From: coder\nKevin-Stage: qa\nKevin-Route: specifier,coder,qa\nKevin-Needs: none"

  # kevin record commits 6
  Scenario: kevin record commits 6: kevin approve adds an approval record and then a handoff record
    Given role "specifier" committed "Spec" in the worktree of task "A" as C1
    And I ran "kevin review $A spec ready"
    When I run "kevin approve $A"
    Then the exit status is 0
    And the parent of the tip commit of task "A" has the message "kevin approval: $A\n\nKevin-Task: $A\nKevin-Record: approval\nKevin-From: operator\nKevin-Stage: specifier\nKevin-Route: specifier,coder,qa\nKevin-Needs: none"
    And the tip commit of task "A" has the message "kevin handoff: $A\n\nspecifier work complete\n\nKevin-Task: $A\nKevin-Record: handoff\nKevin-From: specifier\nKevin-Stage: coder\nKevin-Route: specifier,coder,qa\nKevin-Needs: none"

  # kevin record commits 7
  Scenario: kevin record commits 7: a record leaves the staged and unstaged changes of the worktree as they were
    Given the worktree of task "A" has the staged new file "s.txt" and the unstaged change "t.txt"
    And I ran "kevin ask $A Which hash?"
    And I ran "kevin answer $A argon2id"
    When I run "kevin route $A specifier,qa"
    Then the exit status is 0
    And the worktree of task "A" still has the staged new file "s.txt" and the unstaged change "t.txt"

  # kevin record commits 8
  Scenario Outline: kevin record commits 8: a refused command adds no commit
    When I run "<command>"
    Then the exit status is 1
    And the error output has the line "kevin: <error>"
    And the branch of task "A" did not move

    Examples:
      | command              | error                                                           |
      | kevin approve $A        | task is not waiting for approval                                |
      | kevin route $A coder,qa | route 'coder,qa' does not contain the current stage 'specifier' |

  # kevin record commits 9
  Scenario: kevin record commits 9: kevin reject is refused while the task does not wait for approval
    When I run "kevin reject $A no"
    Then the exit status is 1
    And the error output has the line "kevin: task is not waiting for approval"
    And the branch of task "A" did not move

  # kevin record commits 10
  Scenario: kevin record commits 10: kevin handoff is refused while the task needs the operator
    Given I ran "kevin ask $A Which"
    When I run "kevin handoff $A"
    Then the exit status is 1
    And the error output has the line "kevin: task is waiting on the operator (needs:operator); handoff refused"
    And the branch of task "A" did not move
