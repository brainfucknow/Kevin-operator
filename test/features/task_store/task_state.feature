Feature: kevin task state

  The state of a task is the Kevin-Stage, Kevin-Route and
  Kevin-Needs trailers of its newest record: the newest commit with
  "Kevin-Task: <id>" in the first-parent history of its branch. kevin ls,
  kevin next and kevin stage read it from the local swarm/<id> branches; there is
  no other store.

  kevin ls prints one line per task that is not finished, sorted by id: id,
  stage, needs (empty when none), route, gates (none when the task has none)
  and title, separated by tabs. --stage
  <role> keeps the tasks at that stage, --needs keeps the tasks that need the
  operator or an approval. kevin next <role> prints the ids of the tasks at that
  stage that need nothing, or NO_TASK.

  A task is finished when the history of the HEAD of the main checkout, any
  parent included, has its finished record (the merge of kevin finish), whatever
  branch still has swarm/<id>, or when it has no local branch and its newest
  record that HEAD reaches is at done (see kevin finish protected). kevin ls and kevin next skip a finished task; kevin
  start and every command that writes a record refuse it with "task <id> is
  finished" and exit status 1. kevin reads only local branches: a remote
  swarm/<id> that could not be deleted never lists or reopens a task. A task
  that was squash-merged while its branch remains is not finished; it stays
  at its stage until kevin finish merges it.

  kevin handoff --to <role> names where the task goes: its stage or a later role
  on its route, or done when the stage is the route's last role. Any other
  --to exits 1 with "kevin: --to <role> is not on route '<route>' at or after
  stage:<stage>", or for done "kevin: --to done skips the rest of route
  '<route>' after stage:<stage>; hand off to the next role", and records
  nothing. Only kevin route, the operator's, changes which roles a task visits.

  If the role of the stage has the send-back flag in roles.conf, --to may
  also name an earlier role on the route: a send-back. kevin records an ordinary
  handoff record, and the task moves forward along the route again from that
  role. --to specifier backwards exits 1 with "kevin: --to specifier: a task
  cannot go back to the specifier". A send-back is a handoff record whose
  target comes before its sender on the route; kevin refuses the third one of a
  task with "kevin: --to <role>: task was sent back 2 times; ask the operator"
  and exit status 1. A refusal records nothing. The shipped pr-reviewer has
  the flag.

  Background:
    Given a project with kevin initialized
    And task "A" titled "Add login" was created on route "specifier,coder"
    And task "B" titled "Fix bug" was started on route "coder,qa"

  # kevin task state 1
  Scenario Outline: kevin task state 1: kevin ls prints the state of each task branch
    When I run "kevin ls <options>"
    Then the exit status is 0
    And the output is "<output>"

    Examples:
      | options       | output                                                                                  |
      |               | $A\tspecifier\t\tspecifier,coder\tnone\tAdd login\n$B\tcoder\t\tcoder,qa\tnone\tFix bug |
      | --stage coder | $B\tcoder\t\tcoder,qa\tnone\tFix bug                                                    |

  # kevin task state 2
  Scenario Outline: kevin task state 2: kevin ls shows a task that needs the operator
    Given I ran "kevin ask $B Which"
    When I run "kevin ls <options>"
    Then the exit status is 0
    And the output is "<output>"

    Examples:
      | options | output                                                                                          |
      |         | $A\tspecifier\t\tspecifier,coder\tnone\tAdd login\n$B\tcoder\toperator\tcoder,qa\tnone\tFix bug |
      | --needs | $B\tcoder\toperator\tcoder,qa\tnone\tFix bug                                                    |

  # kevin task state 3
  Scenario: kevin task state 3: kevin ls --needs shows a task that waits for approval
    Given I ran "kevin review $A"
    When I run "kevin ls --needs"
    Then the exit status is 0
    And the output is "$A\tspecifier\tapproval\tspecifier,coder\tnone\tAdd login"

  # kevin task state 4
  Scenario: kevin task state 4: kevin next lists the tasks at the stage that need nothing
    When I run "kevin next coder"
    Then the exit status is 0
    And the output is "$B"

  # kevin task state 5
  Scenario: kevin task state 5: kevin next skips a task that needs the operator
    Given I ran "kevin ask $B Which"
    When I run "kevin next coder"
    Then the exit status is 0
    And the output is "NO_TASK"

  # kevin task state 6
  Scenario: kevin task state 6: a finished task is not listed
    Given task "B" was finished
    When I run "kevin ls"
    Then the exit status is 0
    And the output is "$A\tspecifier\t\tspecifier,coder\tnone\tAdd login"

  # kevin task state 7
  Scenario Outline: kevin task state 7: a squash-merged task whose branch remains is still listed at done
    Given task "B" was handed off to done and squash-merged into main
    When I run "<command>"
    Then the exit status is 0
    And the output is "<output>"

    Examples:
      | command       | output                                                                                 |
      | kevin ls         | $A\tspecifier\t\tspecifier,coder\tnone\tAdd login\n$B\tdone\t\tcoder,qa\tnone\tFix bug |
      | kevin next coder | NO_TASK                                                                                |

  # kevin task state 8
  Scenario Outline: kevin task state 8: a command that writes a record refuses a finished task
    Given task "B" was finished
    When I run "<command>"
    Then the exit status is 1
    And the error output has the line "kevin: task $B is finished"
    And the branch "swarm/$B" does not exist

    Examples:
      | command           |
      | kevin start $B       |
      | kevin route $B coder |
      | kevin handoff $B     |

  # kevin task state 9
  Scenario: kevin task state 9: kevin ask refuses a finished task
    Given task "B" was finished
    When I run "kevin ask $B Which"
    Then the exit status is 1
    And the error output has the line "kevin: task $B is finished"
    And the branch "swarm/$B" does not exist

  # kevin task state 10
  Scenario: kevin task state 10: kevin answer refuses a finished task
    Given task "B" was finished
    When I run "kevin answer $B yes"
    Then the exit status is 1
    And the error output has the line "kevin: task $B is finished"
    And the branch "swarm/$B" does not exist

  # kevin task state 11
  Scenario Outline: kevin task state 11: kevin handoff --to stays on the rest of the route
    When I run "kevin handoff $B --to <to>"
    Then the exit status is 1
    And the error output has the line "<error>"
    And the branch of task "B" did not move

    Examples:
      | to        | error                                                                                         |
      | done      | kevin: --to done skips the rest of route 'coder,qa' after stage:coder; hand off to the next role |
      | cleaner   | kevin: --to cleaner is not on route 'coder,qa' at or after stage:coder                           |
      | specifier | kevin: --to specifier is not on route 'coder,qa' at or after stage:coder                         |

  # kevin task state 12
  Scenario Outline: kevin task state 12: a send-back stage hands the task back to an earlier role
    Given task "C" titled "Review" was started on route "coder,cleaner,pr-reviewer,qa"
    And task "C" was sent back from "pr-reviewer" to "coder" <sent> times
    And task "C" was handed off to stage "pr-reviewer"
    When I run "kevin handoff $C --to <to>"
    Then the exit status is 0
    And the output matches "^HANDOFF: $C pr-reviewer -> <to> at "

    Examples:
      | sent | to    |
      | 0    | coder |
      | 1    | coder |
      | 0    | qa    |

  # kevin task state 13
  Scenario: kevin task state 13: kevin refuses the third send-back of a task
    Given task "C" titled "Review" was started on route "coder,cleaner,pr-reviewer,qa"
    And task "C" was sent back from "pr-reviewer" to "coder" 2 times
    And task "C" was handed off to stage "pr-reviewer"
    When I run "kevin handoff $C --to coder"
    Then the exit status is 1
    And the error output has the line "kevin: --to coder: task was sent back 2 times; ask the operator"
    And the branch of task "C" did not move

  # kevin task state 14
  Scenario: kevin task state 14: a stage without the send-back flag cannot send a task back
    Given task "C" titled "Review" was started on route "coder,cleaner,pr-reviewer,qa"
    And task "C" was handed off to stage "cleaner"
    When I run "kevin handoff $C --to coder"
    Then the exit status is 1
    And the error output has the line "kevin: --to coder is not on route 'coder,cleaner,pr-reviewer,qa' at or after stage:cleaner"
    And the branch of task "C" did not move

  # kevin task state 15
  Scenario: kevin task state 15: a sent-back task moves forward along its route again
    Given task "C" titled "Review" was started on route "coder,cleaner,pr-reviewer,qa"
    And task "C" was sent back from "pr-reviewer" to "coder" 1 times
    When I run "kevin handoff $C"
    Then the exit status is 0
    And the output matches "^HANDOFF: $C coder -> cleaner at "

  # kevin task state 16
  Scenario: kevin task state 16: a send-back to the specifier is refused
    Given task "C" titled "Review" was started on route "specifier,coder,pr-reviewer"
    And task "C" was handed off to stage "pr-reviewer"
    When I run "kevin handoff $C --to specifier"
    Then the exit status is 1
    And the error output has the line "kevin: --to specifier: a task cannot go back to the specifier"
    And the branch of task "C" did not move
