Feature: kevin tool write guard

  Before it writes anything, kevin tool ensure checks the kind and the rewrite
  of the tool and of every tool in its needs chain (the tools it needs, the
  tools those need, and so on). When one of them has a kind or a rewrite that
  kevin tool does not know, ensure prints the error on stderr and exits 1. It
  writes no wrapper, clones no source, and writes no coverage config: nothing
  under the main checkout's .kevin/ changes, so wrappers left by an
  earlier install stay byte-for-byte as they were and require still reports
  them. This is what happens when a task worktree's tools.conf has a kind or a
  rewrite that its kevin does not know.

  # kevin tool write guard 1
  Scenario Outline: kevin tool write guard 1: an unknown kind or rewrite in the needs chain writes nothing
    Given a project with kevin initialized
    And no tool source override
    And a task worktree
    And the task worktree's tools.conf adds the line "weird java github.com/x/weird 0123456789abcdef0123456789abcdef01234567 crap - cloverage"
    And the task worktree's tools.conf adds the line "odd bb github.com/x/odd 0123456789abcdef0123456789abcdef01234567 crap rot13 cloverage"
    And the task worktree's tools.conf adds the line "outer bb github.com/x/outer 0123456789abcdef0123456789abcdef01234567 crap coverage weird"
    And an earlier install left a wrapper for "cloverage"
    And an earlier install left a wrapper for "weird"
    And an earlier install left a wrapper for "<tool>"
    And a snapshot of "MAIN/.kevin"
    When I run "kevin tool ensure <tool>" in the task worktree
    Then the exit status is 1
    And the error output contains "<error>"
    And "MAIN/.kevin" matches the snapshot
    And "kevin tool require <tool>" in the task worktree prints "OK: <tool> MAIN/.kevin/bin/<tool>"

    Examples:
      | tool  | error                                                     |
      | weird | kevin tool: unknown kind 'java' for weird                    |
      | odd   | kevin tool: unknown rewrite 'rot13' in kevin/tools.conf |
      | outer | kevin tool: unknown kind 'java' for weird                    |
