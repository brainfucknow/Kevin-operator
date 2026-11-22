Feature: kevin metrics cache save

  kevin handoff saves the manifests of the task to kevin/metrics when it
  records the handoff. The manifests are the files under .metrics/mutate/ and
  .metrics/gherkin/ of the task worktree (.worktrees/<id>), whatever directory
  kevin handoff runs in. Other files under .metrics/ are not saved.

  A manifest is saved when the tip of kevin/metrics has no file at its
  path under .metrics/, or a file with other content. kevin saves them in one
  commit whose tree is the tip's tree with those files, whose message is
  "kevin metrics: <id>" and the trailer "Kevin-Task: <id>", and whose parent
  is the tip. The first save makes the branch with a commit that has no
  parent, so the branch shares no history with main. A file deleted from
  .metrics/ stays on the branch. With nothing to save, kevin makes no commit.
  kevin moves the branch without checking it out, so no worktree changes, the
  main checkout stays on its branch, and no worktree is added.

  A handoff that kevin refuses saves nothing. kevin approve saves, since it hands
  off. The output of kevin handoff and its record commit do not change.

  Background:
    Given a project with kevin initialized and .metrics/ ignored

  # kevin metrics cache save 1
  Scenario: kevin metrics cache save 1: the first save makes the metrics branch with no parent
    Given task "A" titled "Add login" was started on route "coder,qa"
    And role "coder" committed "Work" in the worktree of task "A" as C1
    And the file ".metrics/mutate/kevin/task.edn" in the worktree of task "A" is written with "v1"
    When I run "kevin handoff $A" in the worktree of task "A"
    Then the exit status is 0
    And the output is "HANDOFF: $A coder -> qa at C1"
    And the file "mutate/kevin/task.edn" on the metrics branch is "v1"
    And the metrics branch is one commit with no parent
    And the tip commit of the metrics branch has the message "kevin metrics: $A\n\nKevin-Task: $A"
    And the tip commit of task "A" changes no file
    And the worktree of task "A" has no uncommitted change
    And the main checkout has no file ".metrics/mutate/kevin/task.edn" and no uncommitted change
    And the main checkout is on the branch "main"
    And the only worktrees are the main checkout and the worktree of task "A"

  # kevin metrics cache save 2
  Scenario Outline: kevin metrics cache save 2: only new and changed manifests are saved, on top of the tip
    Given the metrics branch holds the file "mutate/a.edn" with "v1"
    And the metrics branch holds the file "mutate/b.edn" with "w1"
    And task "A" titled "Add login" was started on route "coder,qa"
    And the tip of the metrics branch is named M1
    And the file "<file>" in the worktree of task "A" is written with "w2"
    When I run "kevin handoff $A"
    Then the exit status is 0
    And the parent of the tip commit of the metrics branch is M1
    And the tip commit of the metrics branch changes the files "<changes>"

    Examples:
      | file                            | changes                    |
      | .metrics/mutate/b.edn           | M mutate/b.edn             |
      | .metrics/gherkin/f/x.feature    | A gherkin/f/x.feature      |

  # kevin metrics cache save 3
  Scenario: kevin metrics cache save 3: manifests the same as the cached copy do not move the branch
    Given the metrics branch holds the file "mutate/a.edn" with "v1"
    And task "A" titled "Add login" was started on route "coder,qa"
    When I run "kevin handoff $A"
    Then the exit status is 0
    And the metrics branch did not move

  # kevin metrics cache save 4
  Scenario: kevin metrics cache save 4: a task without manifests makes no metrics branch
    Given task "A" titled "Add login" was started on route "coder,qa"
    And the file ".metrics/crap.edn" in the worktree of task "A" is written with "c1"
    When I run "kevin handoff $A"
    Then the exit status is 0
    And the branch "kevin/metrics" does not exist

  # kevin metrics cache save 5
  Scenario: kevin metrics cache save 5: only files under mutate/ and gherkin/ are saved
    Given task "A" titled "Add login" was started on route "coder,qa"
    And the file ".metrics/crap.edn" in the worktree of task "A" is written with "c1"
    And the file ".metrics/mutate/a.edn" in the worktree of task "A" is written with "v1"
    When I run "kevin handoff $A"
    Then the exit status is 0
    And the tip commit of the metrics branch changes the files "A mutate/a.edn"

  # kevin metrics cache save 6
  Scenario: kevin metrics cache save 6: a manifest deleted from the worktree stays on the branch
    Given the metrics branch holds the file "mutate/a.edn" with "v1"
    And task "A" titled "Add login" was started on route "coder,qa"
    And the file ".metrics/mutate/a.edn" in the worktree of task "A" was deleted
    And the file ".metrics/mutate/b.edn" in the worktree of task "A" is written with "w1"
    When I run "kevin handoff $A"
    Then the exit status is 0
    And the tip commit of the metrics branch changes the files "A mutate/b.edn"
    And the file "mutate/a.edn" on the metrics branch is "v1"

  # kevin metrics cache save 7
  Scenario: kevin metrics cache save 7: a refused handoff saves nothing
    Given task "A" titled "Add login" was started on route "coder,qa"
    And the file ".metrics/mutate/a.edn" in the worktree of task "A" is written with "v1"
    And I ran "kevin ask $A Which"
    When I run "kevin handoff $A"
    Then the exit status is 1
    And the branch "kevin/metrics" does not exist

  # kevin metrics cache save 8
  Scenario: kevin metrics cache save 8: kevin approve saves the manifests as it hands off
    Given task "A" titled "Add login" was started on route "specifier,coder"
    And the file ".metrics/gherkin/f/x.feature" in the worktree of task "A" is written with "g1"
    And I ran "kevin review $A"
    When I run "kevin approve $A"
    Then the exit status is 0
    And the file "gherkin/f/x.feature" on the metrics branch is "g1"
