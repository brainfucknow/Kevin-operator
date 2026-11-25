Feature: kevin task done

  The handoff that moves a task to stage done (kevin handoff from the route's
  last role, with or without --to done, or kevin approve on a route whose last
  role is the specifier) is a record commit like any other and changes no
  file.
  For a task created before this change, it removes every file named
  <id>.md from the branch, so no task file reaches the merge.

  kevin finish merges the task branch into the target with --no-ff. The merge
  commit is the finished record: "kevin finished: <id>" and the snapshot
  trailers, then Kevin-Merged-Into. It brings no task file into the
  target. kevin finish removes the worktree and the local branch; it never
  touches a remote. Without kevin/verify.conf it prints "VERIFY: none
  configured" before "FINISHED: <id> merged into <target>" (see kevin finish
  verify).

  Background:
    Given a project with kevin initialized

  # kevin task done 1
  Scenario Outline: kevin task done 1: a handoff that reaches done is a record that changes no file
    Given task "A" titled "Add login" was started on route "<route>"
    When I run "<command>"
    Then the exit status is 0
    And the tip commit of task "A" changes no file
    And the tip commit of task "A" has the message "kevin handoff: $A\n\ncoder work complete\n\nKevin-Task: $A\nKevin-Record: handoff\nKevin-From: coder\nKevin-Stage: done\nKevin-Route: <route>\nKevin-Needs: none"
    And the worktree of task "A" has no uncommitted change

    Examples:
      | route    | command                 |
      | coder    | kevin handoff $A           |
      | coder    | kevin handoff $A --to done |

  # kevin task done 2
  Scenario: kevin task done 2: kevin approve on a route that ends at the specifier changes no file
    Given task "A" titled "Add login" was started on route "specifier"
    And I ran "kevin review $A"
    When I run "kevin approve $A"
    Then the exit status is 0
    And the output matches "^HANDOFF: $A specifier -> done at "
    And the tip commit of task "A" changes no file

  # kevin task done 3
  Scenario: kevin task done 3: kevin finish merges the task with a finished record and leaves no task file
    Given task "A" titled "Add login" was started on route "coder"
    And role "coder" committed the file "src/a.clj" with "Code" in the worktree of task "A" as C1
    And I ran "kevin handoff $A"
    And the tip of the branch of task "A" is named T1
    And I ran "git checkout -q -b work"
    When I run "kevin finish $A"
    Then the exit status is 0
    And the output is "VERIFY: none configured\nFINISHED: $A merged into work"
    And the HEAD of the main checkout is a merge whose second parent is T1
    And the HEAD of the main checkout has the message "kevin finished: $A\n\nKevin-Task: $A\nKevin-Record: finished\nKevin-From: operator\nKevin-Stage: done\nKevin-Route: coder\nKevin-Needs: none\nKevin-Merged-Into: work"
    And the main checkout has the file "src/a.clj" and no file "tasks/$A.md"
    And the main checkout has no file "tasks/done/$A.md" and no uncommitted change
    And the branch "swarm/$A" does not exist
    And the worktree of task "A" does not exist
