Feature: kevin finish protected

  kevin finish never commits on a protected branch. A branch is protected
  when its name is main, master, develop or release, or when it is the
  repository's default branch. The default branch is the branch that the
  first remote's HEAD names (git remote lists the remotes; the ref
  refs/remotes/<remote>/HEAD must exist), else the value of
  init.defaultBranch when it is set and not blank, else master.

  The target of kevin finish is the branch that --into names, or the branch
  the main checkout has checked out when --into is missing or blank. When
  --into names a protected branch, kevin finish exits 1 with the protected
  refusal and changes nothing. There is no override.

  If the target is a protected branch that the main checkout has checked
  out, kevin finish does what HEAD allows:

  - HEAD does not contain the tip of the task branch (the work is not merged,
    or a host squashed or rebased it): kevin finish exits 1 with the
    protected refusal and changes nothing. The task stays open at done.
  - HEAD contains the tip (a host merged the pull request with a merge
    commit and the operator pulled it): kevin finish verifies HEAD as kevin
    finish verify describes, removes the worktree and the task branch, and
    prints the VERIFY lines, then "FINISHED: <id> merged into <branch>". It
    writes no commit: HEAD stays as it was. A failed verification exits 1
    and leaves HEAD, the task branch and the worktree as they were.

  The protected refusal is the error line "kevin: refusing to merge
  swarm/<id> into <branch>: <branch> is a protected branch; push
  swarm/<id>, open a pull request into <branch>, merge it with a merge
  commit, pull <branch>, then run: kevin finish <id>". kevin checks the
  task, its stage, a merge in progress and uncommitted worktree changes
  first, with the errors that kevin finish verify and kevin finish resume
  describe.

  A task is finished when HEAD's history has its finished record, or when
  the task has no local branch and its newest record that HEAD reaches is at
  stage done. kevin show prints "STATUS: finished" for it, kevin ls does not
  list it, and kevin start and kevin finish refuse it with "kevin: task <id>
  is finished". A history without a local branch whose newest record is at
  any other stage stays unclosed.

  On a branch that is not protected, kevin finish works as before.

  Background:
    Given a project with kevin initialized
    And task "A" titled "Add login" was started on route "coder"
    And role "coder" committed the file "src/a.clj" with "Code" in the worktree of task "A" as C1

  # kevin finish protected 1
  Scenario Outline: kevin finish protected 1: a finish on a protected branch without the task is refused
    Given I ran "kevin handoff $A"
    And I ran "<checkout>"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A"
    Then the exit status is 1
    And the output is ""
    And the error output has the line "kevin: refusing to merge swarm/$A into <branch>: <branch> is a protected branch; push swarm/$A, open a pull request into <branch>, merge it with a merge commit, pull <branch>, then run: kevin finish $A"
    And the HEAD of the main checkout is M1
    And the main checkout is on the branch "<branch>"
    And the main checkout has no file "src/a.clj" and no uncommitted change
    And the branch of task "A" did not move
    And the worktree of task "A" exists

    Examples:
      | checkout                   | branch  |
      | true                       | main    |
      | git checkout -q -b master  | master  |
      | git checkout -q -b develop | develop |
      | git checkout -q -b release | release |

  # kevin finish protected 2
  Scenario Outline: kevin finish protected 2: --into a protected branch is refused
    Given I ran "kevin handoff $A"
    And I ran "git branch <branch>"
    And I ran "git checkout -q -b work"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A --into <branch>"
    Then the exit status is 1
    And the output is ""
    And the error output has the line "kevin: refusing to merge swarm/$A into <branch>: <branch> is a protected branch; push swarm/$A, open a pull request into <branch>, merge it with a merge commit, pull <branch>, then run: kevin finish $A"
    And the HEAD of the main checkout is M1
    And the main checkout is on the branch "work"
    And the branch "<branch>" did not move
    And the branch of task "A" did not move
    And the worktree of task "A" exists

    Examples:
      | branch  |
      | main    |
      | master  |
      | develop |
      | release |

  # kevin finish protected 3
  Scenario: kevin finish protected 3: a blank --into on a protected branch is refused like a bare finish
    Given I ran "kevin handoff $A"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A --into" with the text ""
    Then the exit status is 1
    And the error output has the line "kevin: refusing to merge swarm/$A into main: main is a protected branch; push swarm/$A, open a pull request into main, merge it with a merge commit, pull main, then run: kevin finish $A"
    And the HEAD of the main checkout is M1
    And the branch of task "A" did not move

  # kevin finish protected 4
  Scenario: kevin finish protected 4: init.defaultBranch names a protected branch when there is no remote
    Given I ran "kevin handoff $A"
    And I ran "git config init.defaultBranch trunk"
    And I ran "git checkout -q -b trunk"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: refusing to merge swarm/$A into trunk: trunk is a protected branch; push swarm/$A, open a pull request into trunk, merge it with a merge commit, pull trunk, then run: kevin finish $A"
    And the HEAD of the main checkout is M1
    And the branch of task "A" did not move

  # kevin finish protected 5
  Scenario Outline: kevin finish protected 5: the remote's HEAD names the default branch over init.defaultBranch
    Given I ran "kevin handoff $A"
    And I ran "git config init.defaultBranch work"
    And I ran "git branch trunk"
    And I ran "git update-ref refs/remotes/origin/trunk HEAD"
    And I ran "git symbolic-ref refs/remotes/origin/HEAD refs/remotes/origin/trunk"
    And I ran "git remote add origin ."
    And I ran "git checkout -q -b <branch>"
    When I run "kevin finish $A"
    Then the exit status is <status>
    And the output is "<output>"

    Examples:
      | branch | status | output                                                  |
      | trunk  | 1      |                                                         |
      | work   | 0      | VERIFY: none configured\nFINISHED: $A merged into work |

  # kevin finish protected 6
  Scenario: kevin finish protected 6: init.defaultBranch counts when the remote has no HEAD ref
    Given I ran "kevin handoff $A"
    And I ran "git config init.defaultBranch trunk"
    And I ran "git remote add origin ."
    And I ran "git checkout -q -b trunk"
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: refusing to merge swarm/$A into trunk: trunk is a protected branch; push swarm/$A, open a pull request into trunk, merge it with a merge commit, pull trunk, then run: kevin finish $A"

  # kevin finish protected 7
  Scenario: kevin finish protected 7: a branch that is not protected still gets the merge and the finished record
    Given I ran "kevin handoff $A"
    And the tip of the branch of task "A" is named T1
    And I ran "git checkout -q -b trunk"
    When I run "kevin finish $A"
    Then the exit status is 0
    And the output is "VERIFY: none configured\nFINISHED: $A merged into trunk"
    And the HEAD of the main checkout is a merge whose second parent is T1
    And the HEAD of the main checkout has the message "kevin finished: $A\n\nKevin-Task: $A\nKevin-Record: finished\nKevin-From: operator\nKevin-Stage: done\nKevin-Route: coder\nKevin-Needs: none\nKevin-Merged-Into: trunk"
    And the branch "swarm/$A" does not exist

  # kevin finish protected 8
  Scenario: kevin finish protected 8: uncommitted worktree changes are refused before the protected branch
    Given I ran "kevin handoff $A"
    And the file "notes.txt" in the worktree of task "A" is written with "draft"
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: uncommitted changes in ROOT/.worktrees/$A; commit first"
    And the file "notes.txt" in the worktree of task "A" still holds "draft"

  # kevin finish protected 9
  Scenario Outline: kevin finish protected 9: after a host's merge commit kevin finish cleans up and commits nothing
    Given I ran "kevin handoff $A"
    And I ran "git merge -q --no-ff --no-edit swarm/$A"
    And I ran "<after>"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A"
    Then the exit status is 0
    And the output is "VERIFY: none configured\nFINISHED: $A merged into main"
    And the HEAD of the main checkout is M1
    And the branch "swarm/$A" does not exist
    And the worktree of task "A" does not exist

    Examples:
      | after                                |
      | true                                 |
      | git commit -q --allow-empty -m Later |

  # kevin finish protected 10
  Scenario: kevin finish protected 10: a failed verification after a host's merge keeps the task
    Given the main checkout committed the file "kevin/verify.conf" with "false"
    And I ran "kevin handoff $A"
    And I ran "git merge -q --no-ff --no-edit swarm/$A"
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: verification failed on the merge of swarm/$A into main: false exited 1"
    And the HEAD of the main checkout is M1
    And the branch of task "A" did not move
    And the worktree of task "A" exists

  # kevin finish protected 11
  Scenario: kevin finish protected 11: a squash-merged task stays open on a protected branch
    Given task "A" was handed off to done and squash-merged into main
    And the HEAD of the main checkout is named M1
    When I run "kevin finish $A"
    Then the exit status is 1
    And the error output has the line "kevin: refusing to merge swarm/$A into main: main is a protected branch; push swarm/$A, open a pull request into main, merge it with a merge commit, pull main, then run: kevin finish $A"
    And the HEAD of the main checkout is M1
    And the branch of task "A" did not move

  # kevin finish protected 12
  Scenario Outline: kevin finish protected 12: a merged task without a branch is finished when its newest record is at done
    Given I ran "kevin handoff $A"
    And I ran "git merge -q --no-ff --no-edit swarm/$A"
    And the worktree and the branch of task "A" were removed
    When I run "<command>"
    Then the exit status is <status>
    And the <stream> has the line "<line>"

    Examples:
      | command         | status | stream       | line                       |
      | kevin show $A   | 0      | output       | STATUS: finished           |
      | kevin start $A  | 1      | error output | kevin: task $A is finished |
      | kevin finish $A | 1      | error output | kevin: task $A is finished |

  # kevin finish protected 13
  Scenario: kevin finish protected 13: a task finished through a host's merge is not listed
    Given I ran "kevin handoff $A"
    And I ran "git merge -q --no-ff --no-edit swarm/$A"
    And I ran "kevin finish $A"
    When I run "kevin ls"
    Then the exit status is 0
    And the output is ""

  # kevin finish protected 14
  Scenario: kevin finish protected 14: a merged history without a branch whose newest record is not at done stays unclosed
    Given I ran "git merge -q --no-ff --no-edit swarm/$A"
    And the worktree and the branch of task "A" were removed
    When I run "kevin show $A"
    Then the exit status is 0
    And the output has the line "STATUS: unclosed"
