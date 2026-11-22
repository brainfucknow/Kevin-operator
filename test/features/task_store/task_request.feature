Feature: kevin task request

  kevin new makes the branch swarm/<id> at the HEAD of the main checkout and adds
  one commit to it that changes no file. That commit is the task's created
  record and holds the task: its message is "kevin created: <id>", a blank line,
  the title, a blank line, the request, a blank line, and the trailers
  Kevin-Task, Kevin-Record, Kevin-From, Kevin-Stage,
  Kevin-Route and Kevin-Needs. The stage is the first role of the
  route. Without -m the request is the title. Each run of whitespace in the
  title, line breaks included, becomes one space. The main checkout gets no
  commit and no file.

  kevin start, kevin show and kevin ls read the title and the request back from that
  commit. A task created before this change has a created commit that adds
  one task file; kevin reads the title and the request from that file as the
  created commit has it.

  Background:
    Given a project with kevin initialized

  # kevin task request 1
  Scenario Outline: kevin task request 1: kevin new keeps the title and the request in the created record commit
    When I run kevin new with the title "Add login", the request "<request>" and the route "<route>"
    Then the exit status is 0
    And the output matches "^add-login-[0-9a-f]{7}$"
    And the tip commit of task NEW changes no file
    And the parent of the tip commit of task NEW is the HEAD of the main checkout
    And the tip commit of task NEW has the message "kevin created: NEW\n\nAdd login\n\n<request>\n\nKevin-Task: NEW\nKevin-Record: created\nKevin-From: operator\nKevin-Stage: <stage>\nKevin-Route: <route>\nKevin-Needs: none"
    And the main checkout has no file "tasks/NEW.md" and no uncommitted change

    Examples:
      | request                      | route              | stage     |
      | Users log in.                | specifier,coder,qa | specifier |
      | Users log in.\n\nWith email. | coder,qa           | coder     |

  # kevin task request 2
  Scenario Outline: kevin task request 2: the author of a record is git's identity
    Given the git identity of the project is "<identity>"
    When I run kevin new with the title "Add login", the request "x" and the route "coder"
    Then the exit status is 0
    And the tip commit of task NEW has the author "<author>"

    Examples:
      | identity                    | author                      |
      | tester <tester@example.com> | tester <tester@example.com> |

  # kevin task request 3
  Scenario: kevin task request 3: the author of a record is the Kevin user when git has no identity
    Given the git identity of the project is "-"
    When I run kevin new with the title "Add login", the request "x" and the route "coder"
    Then the exit status is 0
    And the tip commit of task NEW has the author "operator <operator@kevin.local>"

  # kevin task request 4
  Scenario: kevin task request 4: kevin show prints the title and the request from the created record
    Given task "A" titled "Add login" with the request "Users log in.\n\nWith email." was created on route "coder"
    When I run "kevin show $A"
    Then the exit status is 0
    And the output has the lines "TASK: $A\nTITLE: Add login\nSTATUS: open" before the line "---"
    And the output has the lines "---\nUsers log in.\n\nWith email.\n\ntype: created"
