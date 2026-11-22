Feature: kevin task fixes

  kevin new takes --fixes <target> and --found-by <detector>. The target names
  what the new task repairs. It is a task, named as kevin show takes it (a full
  id, a hex part or a prefix; open, finished, or an unclosed history), or a
  pull request, given as # and a number that does not start with 0. kevin new
  does not check the pull request. The detector says how the defect was
  found after the repaired task left the swarm: pr-review, ci or operator.

  The created record keeps them in the trailers Kevin-Fixes and
  Kevin-Found-By, after Kevin-Needs. Kevin-Fixes holds the
  full task id, or the pull request as given. kevin show and kevin start print
  them in the created block as "fixes:" and "found-by:" after "route:". A
  task created without the options has neither trailer and neither line.

  kevin harvest prints two keys on every line, after "gates" and before
  "records": "fixes" and "found_by", the stored values or null. A reader of
  the harvest maps a pull request to its tasks.

  kevin new checks in this order and prints the first refusal: the operator
  check, the title, the options left to right (an unknown option, then a
  missing value, then --fixes or --found-by given twice; a -F file that
  cannot be read fails here), the route, the detector value, --found-by
  without --fixes, the target form (an empty target fails it), the task
  named. The word after an option is its value, also when it starts with
  "--". -m, -F and --route given twice keep the last value. A refused kevin
  new prints no id and makes no branch.

  Background:
    Given a project with kevin initialized
    And task "A" titled "Add login" was created on route "coder"

  # kevin task fixes 1
  Scenario Outline: kevin task fixes 1: kevin new keeps the target and the detector in the created record
    When I run kevin new with the title "Fix login", the request "x", the route "coder" and the options "<options>"
    Then the exit status is 0
    And the tip commit of task NEW has the message "kevin created: NEW\n\nFix login\n\nx\n\nKevin-Task: NEW\nKevin-Record: created\nKevin-From: operator\nKevin-Stage: coder\nKevin-Route: coder\nKevin-Needs: none\n<trailers>"

    Examples:
      | options                         | trailers                                             |
      | --fixes $A --found-by pr-review | Kevin-Fixes: $A\nKevin-Found-By: pr-review |
      | --fixes $A:hex --found-by ci    | Kevin-Fixes: $A\nKevin-Found-By: ci        |
      | --found-by operator --fixes add | Kevin-Fixes: $A\nKevin-Found-By: operator  |
      | --fixes #115 --found-by ci      | Kevin-Fixes: #115\nKevin-Found-By: ci      |
      | --fixes #7                      | Kevin-Fixes: #7                                 |

  # kevin task fixes 2
  Scenario: kevin task fixes 2: a finished task can be the target
    Given task "A" was finished
    And I ran "kevin new Fix -m x --route coder --fixes $A:hex"
    And task "B" is the task that command created
    When I run "kevin show $B"
    Then the exit status is 0
    And the output has the line "fixes: $A"

  # kevin task fixes 3
  Scenario: kevin task fixes 3: a task merged without kevin finish can be the target
    Given I ran "git merge -q --no-ff swarm/$A -m" with the text "Merge the login branch"
    And I ran "git branch -D swarm/$A"
    And I ran "kevin new Fix -m x --route coder --fixes $A:hex"
    And task "B" is the task that command created
    When I run "kevin show $B"
    Then the exit status is 0
    And the output has the line "fixes: $A"

  # kevin task fixes 4
  Scenario Outline: kevin task fixes 4: kevin show and kevin start print the target and the detector in the created block
    Given I ran "kevin new Fix -m x --route coder <options>"
    And task "B" is the task that command created
    When I run "kevin <command> $B"
    Then the exit status is 0
    And the output has the lines "type: created\nfrom: operator\nbranch: swarm/$B\ncommit: $B:created\nroute: coder\n<lines>"

    Examples:
      | command | options                         | lines                          |
      | show    | --fixes $A --found-by pr-review | fixes: $A\nfound-by: pr-review |
      | start   | --fixes #115 --found-by ci      | fixes: #115\nfound-by: ci      |

  # kevin task fixes 5
  Scenario: kevin task fixes 5: kevin show prints no detector line for a task without one
    Given I ran "kevin new Fix -m x --route coder --fixes #7"
    And task "B" is the task that command created
    When I run "kevin show $B"
    Then the output ends with the lines "route: coder\nfixes: #7"

  # kevin task fixes 6
  Scenario Outline: kevin task fixes 6: kevin harvest prints the target and the detector of every task
    Given I ran "kevin new Fix -m x --route coder --fixes <target> --found-by <detector>"
    And task "B" is the task that command created
    When I run "kevin harvest"
    Then the exit status is 0
    And the output matches "\"id\":\"$B\"[^\n]*\"route\":\[\"coder\"\],\"gates\":\[\],\"fixes\":\"<stored>\",\"found_by\":\"<detector>\",\"records\":"
    And the output matches "\"id\":\"$A\"[^\n]*\"route\":\[\"coder\"\],\"gates\":\[\],\"fixes\":null,\"found_by\":null,\"records\":"

    Examples:
      | target | detector  | stored |
      | $A:hex | pr-review | $A     |
      | #115   | operator  | #115   |

  # kevin task fixes 7
  Scenario Outline: kevin task fixes 7: kevin new prints the first refusal in the check order
    When I run "kevin new Fix -m x --route coder <options>"
    Then the exit status is 1
    And the output is ""
    And the error output has the line "<refusal>"

    Examples:
      | options                                     | refusal                                                    |
      | --fixes #0 -F nosuch-file --found-by lint   | kevin: nosuch-file (No such file or directory)                |
      | --fixes                                     | kevin: --fixes needs a value                                  |
      | --fixes #7 --found-by ci --found-by         | kevin: --found-by needs a value                               |
      | --fixes  --found-by ci                      | kevin: --fixes needs a task id or '#<number>', got ''         |
      | --fixes #7 --fixes #8                       | kevin: --fixes given twice                                    |
      | --fixes nosuch --found-by ci --found-by ci  | kevin: --found-by given twice                                 |
      | --fixes --found-by ci                       | kevin: unknown new option 'ci'                                |
      | --route bogus --found-by lint               | kevin: unknown role 'bogus' in route 'bogus' (see kevin/roles.conf) |
      | --fixes #7 --found-by review                | kevin: --found-by needs pr-review, ci or operator, got 'review' |
      | --found-by lint                             | kevin: --found-by needs pr-review, ci or operator, got 'lint' |
      | --fixes nosuch --found-by lint              | kevin: --found-by needs pr-review, ci or operator, got 'lint' |
      | --found-by ci                               | kevin: --found-by needs --fixes                               |
      | --fixes #0                                  | kevin: --fixes needs a task id or '#<number>', got '#0'       |
      | --fixes #12a                                | kevin: --fixes needs a task id or '#<number>', got '#12a'     |
      | --fixes #                                   | kevin: --fixes needs a task id or '#<number>', got '#'        |
      | --fixes nosuch                              | kevin: no task 'nosuch'                                       |

  # kevin task fixes 8
  Scenario: kevin task fixes 8: kevin new refuses a target that names two tasks
    Given task "B" titled "Add logout" was created on route "coder"
    When I run "kevin new Fix -m x --route coder --fixes add-log"
    Then the exit status is 1
    And the error output has the line "kevin: task id 'add-log' is ambiguous: $A $B"

  # kevin task fixes 9
  Scenario: kevin task fixes 9: kevin start prints the target and the detector after ROUTE: also when the brief leaves out the created record
    Given I ran "kevin new Fix -m x --route coder --fixes #115 --found-by ci"
    And task "B" is the task that command created
    And the route of task "B" was set to "coder" 11 times
    When I run "kevin start $B"
    Then the exit status is 0
    And the output has the lines "ROUTE: coder\nGATES: none\nFIXES: #115\nFOUND-BY: ci\nBRANCH: swarm/$B"
    And the output has no line containing "type: created"

  # kevin task fixes 10
  Scenario: kevin task fixes 10: kevin start prints no FIXES: or FOUND-BY: line for a task without them
    When I run "kevin start $A"
    Then the exit status is 0
    And the output has no line containing "FIXES:"
    And the output has no line containing "FOUND-BY:"
