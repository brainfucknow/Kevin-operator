Feature: kevin reads legacy task records

  kevin writes task records with Kevin-* trailers and "kevin <record>: <id>"
  subjects. Every command that reads records (start, show, next, list, stage,
  approve, handoff, finish, and the commit-msg hook's role lookup) also reads
  a SwarmForge-<name> trailer as Kevin-<name>, in the same letter cases it
  accepts for Kevin-<name>, and the legacy SwarmForge-Verdict as
  Kevin-Spec-Verdict. The subject of a record is not read. A task branch may
  hold legacy and new records together. New records use Kevin-* trailers
  only.

  Background:
    Given a project with kevin initialized

  # kevin reads legacy task records 1
  Scenario Outline: kevin reads legacy task records 1: kevin start reads a legacy task
    Given task "A" titled "Add login" on route "coder,qa" has legacy records up to stage "<stage>"
    When I run "kevin start $A"
    Then the exit status is 0
    And the output has the line "STAGE: <stage>"
    And the output has the line "ROUTE: coder,qa"
    And the output has the line "TITLE: Add login"

    Examples:
      | stage |
      | coder |
      | qa    |

  # kevin reads legacy task records 2
  Scenario: kevin reads legacy task records 2: a handoff on a legacy task writes a Kevin record
    Given task "A" titled "Add login" on route "coder,qa" has legacy records up to stage "coder"
    And task "A" has a new commit in its worktree
    When I run "kevin handoff $A -m done"
    Then the exit status is 0
    And the newest record of task "A" has the trailer "Kevin-Stage: qa"
    And the newest record of task "A" has no trailer that starts with "SwarmForge-"
    And "kevin next qa" prints "$A"

  # kevin reads legacy task records 3
  Scenario: kevin reads legacy task records 3: a legacy finished record still marks the task finished
    Given task "A" titled "Add login" on route "coder" was finished with legacy records
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: task $A is finished"

  # kevin reads legacy task records 4
  Scenario: kevin reads legacy task records 4: a legacy task id stays taken
    Given task "A" titled "Add login" on route "coder" was finished with legacy records
    When I run "kevin new 'Add login' --route coder"
    Then the exit status is 0
    And the new task id is not "$A"
