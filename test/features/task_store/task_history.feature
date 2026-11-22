Feature: kevin task history

  kevin start prints the lines TASK, TITLE, STAGE, ROUTE, BRANCH and DIR; kevin show
  prints TASK, TITLE, STATUS (open or finished), STAGE and ROUTE. Then both
  print a "---" line and these sections, separated by one blank line: the
  request (from the created record, see kevin task request); the records, oldest first,
  each a block of "key: value" lines; and the LORE:
  lines. kevin start bounds the records and leaves out some LORE: lines, as
  start_brief.feature specifies; kevin show prints them all. In a value of several lines, each later line is indented by two
  spaces. The commit of a review or handoff record is the role's commit that
  the record was made on; files lists what changed since the commit of the
  previous created, review or handoff record, or "-". A merge commit in that
  range adds no files: files lists only what the task branch's own commits
  changed.

  Records and LORE: lines come from the first-parent history of the task
  branch only, so the commits that a merge of the base branch brings in are
  left out. The LORE: lines are those of the commits after the created record.

  kevin show also shows a finished task: one whose finished record is in the
  history of the HEAD of the main checkout, any parent included, so a finish
  into a branch that is later merged with a merge commit counts. kevin show reads
  the records and LORE: lines from that merge's second parent and ends the
  records with the finished block, whether or not the task branch still
  exists. A squash-merged task whose branch remains is open: kevin show reads
  its branch. A task with no local branch and no finished record in HEAD is
  unknown: kevin show refuses it with "no task '<argument>'". kevin start refuses a
  finished task.

  Background:
    Given a project with kevin initialized

  # kevin task history 1
  Scenario Outline: kevin task history 1: start and show print the request, the records and the LORE lines
    Given task "A" titled "Add login" with the request "Users log in.\nWith email." was started on route "specifier,coder"
    And I ran "kevin ask $A Which hash?"
    And I ran "kevin answer $A argon2id"
    And role "specifier" committed the file "features/a.feature" with "Spec\n\nConstraint: keep it short" in the worktree of task "A" as C1
    And I ran "kevin review $A spec ready"
    When I run "kevin <command> $A"
    Then the exit status is 0
    And the output has the lines "<header>" before the line "---"
    And the output after the line "---" is "Users log in.\nWith email.\n\ntype: created\nfrom: operator\nbranch: swarm/$A\ncommit: $A:created\nroute: specifier,coder\n\ntype: question\nfrom: specifier\nquestion: Which hash?\n\ntype: answer\nfrom: operator\nanswer: argon2id\n\ntype: review\nfrom: specifier\ncommit: C1\nfiles: features/a.feature\nsummary: spec ready\n\n<lore>\nLORE: C1 specifier Constraint: keep it short"

    Examples:
      | command | header                                                                                                                       | lore                                                                 |
      | start   | TASK: $A\nTITLE: Add login\nSTAGE: specifier\nROUTE: specifier,coder\nGATES: none\nBRANCH: swarm/$A\nDIR: ROOT/.worktrees/$A | LORE-OMITTED: 1 superseded or evidence lines; kevin show $A prints them |
      | show    | TASK: $A\nTITLE: Add login\nSTATUS: open\nSTAGE: specifier\nROUTE: specifier,coder                                           | LORE: C1 specifier Lore-id: L1                                       |

  # kevin task history 2
  Scenario: kevin task history 2: a value of several lines goes on indented lines
    Given task "A" titled "Add login" was started on route "specifier"
    And I ran "kevin ask $A" with the text "Which hash?\n1) bcrypt\n2) argon2id"
    When I run "kevin show $A"
    Then the exit status is 0
    And the output has the lines "type: question\nfrom: specifier\nquestion: Which hash?\n  1) bcrypt\n  2) argon2id"

  # kevin task history 3
  Scenario: kevin task history 3: a merge of the base branch adds no records and no LORE lines of other tasks
    Given task "B" titled "Fix bug" was started on route "coder"
    And task "A" titled "Add login" was started on route "specifier"
    And role "specifier" committed "Spec\n\nTested: from A" in the worktree of task "A" as C2
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was finished
    And role "coder" committed "Work\n\nTested: from B" in the worktree of task "B" as C1
    And I ran "git merge --no-edit main" in the worktree of task "B"
    When I run "kevin show $B"
    Then the exit status is 0
    And the output has no line containing "$A"
    And the output has no line containing "Tested: from A"
    And the output ends with the lines "LORE: C1 coder Lore-id: L1\nLORE: C1 coder Tested: from B"

  # kevin task history 4
  Scenario Outline: kevin task history 4: kevin show prints a finished task with its LORE lines
    Given task "A" titled "Add login" was started on route "specifier"
    And role "specifier" committed "Spec\n\nTested: x" in the worktree of task "A" as C1
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was finished
    When I run "kevin show <argument>"
    Then the exit status is 0
    And the output has the lines "TASK: $A\nTITLE: Add login\nSTATUS: finished\nSTAGE: done\nROUTE: specifier" before the line "---"
    And the output has the lines "type: finished\nfrom: operator\nmerged-into: main"
    And the output ends with the lines "LORE: C1 specifier Lore-id: L1\nLORE: C1 specifier Tested: x"

    Examples:
      | argument |
      | $A       |
      | $A:hex   |

  # kevin task history 5
  Scenario: kevin task history 5: kevin start refuses a finished task
    Given task "A" titled "Add login" was started on route "specifier"
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was finished
    When I run "kevin start $A"
    Then the exit status is 1
    And the error output has the line "kevin: task $A is finished"

  # kevin task history 6
  Scenario: kevin task history 6: kevin show of a squash-merged task whose branch remains prints it as open from its branch
    Given task "A" titled "Add login" was started on route "specifier"
    And I ran "kevin ask $A Which?"
    And I ran "kevin answer $A yes"
    And role "specifier" committed "Spec\n\nTested: x" in the worktree of task "A" as C1
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was handed off to done and squash-merged into main
    When I run "kevin show $A"
    Then the exit status is 0
    And the output has the lines "TASK: $A\nTITLE: Add login\nSTATUS: open\nSTAGE: done\nROUTE: specifier" before the line "---"
    And the output has the lines "type: question\nfrom: specifier\nquestion: Which?"
    And the output has no line containing "type: finished"
    And the output ends with the lines "LORE: C1 specifier Lore-id: L1\nLORE: C1 specifier Tested: x"

  # kevin task history 7
  Scenario: kevin task history 7: once the branch of a squash-merged task is gone, kevin show finds no task
    Given task "A" titled "Add login" was started on route "specifier"
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was handed off to done and squash-merged into main
    And the worktree and the branch of task "A" were removed
    When I run "kevin show $A"
    Then the exit status is 1
    And the error output has the line "kevin: no task '$A'"

  # kevin task history 8
  Scenario: kevin task history 8: a merge of the base branch adds no files: entries
    Given task "A" titled "Add login" was started on route "specifier"
    And task "B" titled "Fix bug" was started on route "coder"
    And role "specifier" committed the file "features/a.feature" with "Spec" in the worktree of task "A" as C1
    And I ran "kevin review $A"
    And I ran "kevin approve $A"
    And task "A" was finished
    And role "coder" committed the file "src/b.clj" with "Work" in the worktree of task "B" as C1
    And I ran "git merge --no-edit main" in the worktree of task "B"
    And role "coder" committed "Empty" in the worktree of task "B" as C2
    And I ran "kevin handoff $B"
    When I run "kevin show $B"
    Then the exit status is 0
    And the output has the lines "commit: C2\nfiles: src/b.clj"
    And the output has no line containing "features/a.feature"
