Feature: kevin hook refuses legacy record trailers

  In a task worktree the commit-msg hook (kevin commit-msg) refuses a commit
  whose trailer block has a key that starts with "Kevin-" or "SwarmForge-",
  in any letter case. It prints "kevin: <key> is a Kevin record trailer; only
  kevin writes those" on stderr, exits 1, and the commit is not made.
  kevin init replaces a commit-msg hook that an old sf wrote (it holds the
  line "# swarmforge commit-msg hook") and leaves any other hook alone.

  # kevin hook refuses legacy record trailers 1
  Scenario Outline: kevin hook refuses legacy record trailers 1: Kevin and SwarmForge trailers are refused
    Given a project with kevin initialized
    And task "A" titled "Add login" was started on route "coder"
    When I commit "Fix\n\n<key>: done" in the worktree of task "A" with KEVIN_ROLE "coder"
    Then the exit status is 1
    And the error output has the line "kevin: <key> is a Kevin record trailer; only kevin writes those"
    And the branch of task "A" did not move

    Examples:
      | key              |
      | Kevin-Stage      |
      | KEVIN-TASK       |
      | SwarmForge-Stage |
      | swarmforge-needs |

  # kevin hook refuses legacy record trailers 2
  Scenario: kevin hook refuses legacy record trailers 2: kevin init replaces the hook an old sf wrote
    Given a project with kevin installed
    And the commit-msg hook is the one an old sf wrote
    When I run "kevin init"
    Then the exit status is 0
    And the commit-msg hook has the line "# kevin commit-msg hook"
    And the commit-msg hook runs "kevin/bin/kevin"
    And the commit-msg hook does not mention "swarmforge"
