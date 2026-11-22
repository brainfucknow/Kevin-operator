Feature: kevin metrics cache on the legacy branch

  The mutation manifest cache lives on the branch kevin/metrics. kevin start
  loads it from kevin/metrics, or, when that branch does not exist, from
  swarmforge/metrics. kevin handoff saves to kevin/metrics only. When
  kevin/metrics does not exist yet, the save starts from the tree of the tip
  of swarmforge/metrics, so no cached manifest is lost. kevin never changes
  or deletes swarmforge/metrics.

  Background:
    Given a project with kevin initialized and .metrics/ ignored

  # kevin metrics cache on the legacy branch 1
  Scenario Outline: kevin metrics cache on the legacy branch 1: kevin start loads from the branch that exists
    Given the branch "<branch>" holds the file "mutate/x.edn" with "v1"
    And task "A" titled "Add login" was created on route "coder"
    When I run "kevin start $A"
    Then the exit status is 0
    And the file ".metrics/mutate/x.edn" in the worktree of task "A" holds "v1"

    Examples:
      | branch             |
      | kevin/metrics      |
      | swarmforge/metrics |

  # kevin metrics cache on the legacy branch 2
  Scenario: kevin metrics cache on the legacy branch 2: kevin/metrics wins over swarmforge/metrics
    Given the branch "kevin/metrics" holds the file "mutate/x.edn" with "new"
    And the branch "swarmforge/metrics" holds the file "mutate/x.edn" with "old"
    And task "A" titled "Add login" was created on route "coder"
    When I run "kevin start $A"
    Then the file ".metrics/mutate/x.edn" in the worktree of task "A" holds "new"

  # kevin metrics cache on the legacy branch 3
  Scenario: kevin metrics cache on the legacy branch 3: the first save copies the legacy cache to kevin/metrics
    Given the branch "swarmforge/metrics" holds the file "mutate/keep.edn" with "kept"
    And task "A" titled "Add login" was started on route "coder,qa"
    And the file ".metrics/mutate/a.edn" in the worktree of task "A" is written with "a1"
    And task "A" has a new commit in its worktree
    When I run "kevin handoff $A -m done"
    Then the exit status is 0
    And the branch "kevin/metrics" holds the file "mutate/keep.edn" with "kept"
    And the branch "kevin/metrics" holds the file "mutate/a.edn" with "a1"
    And the branch "swarmforge/metrics" did not move
