Feature: kevin tool entry point guard

  Before it writes a wrapper, kevin tool ensure checks that the tool's source has
  the entry point the catalogue names. For kind bb, the source must have a
  bb.edn whose :tasks map has the task. For kind bbmain, src/ in the source
  must hold a file for the namespace: dots become slashes, dashes become
  underscores, and the extension is .clj, .cljc or .bb. When the entry point
  is missing, ensure prints an error naming the tool on stderr, exits 1, and
  writes no wrapper for that tool. A wrapper left by an earlier install stays
  byte-for-byte as it was, so require still reports the tool; a failing ensure
  never replaces or removes a wrapper. Tools the tool needs that were
  installed first stay installed. ensure does not try another way to install
  the tool. How ensure puts a clone at its pinned commit is in
  pinned_refs.feature.

  Background:
    Given a project with kevin initialized
    And a fake bb on PATH

  # kevin tool entry point guard 1
  Scenario Outline: kevin tool entry point guard 1: a missing entry point fails the install and keeps the earlier wrapper
    Given a tool source whose bb.edn defines the tasks "<tasks>"
    And an earlier install left a wrapper for "<tool>"
    When I run "kevin tool ensure <tool>" in the main checkout
    Then the exit status is 1
    And the error output contains "<error>"
    And the wrapper for "<tool>" is unchanged
    And "kevin tool require <tool>" in the main checkout prints "OK: <tool> MAIN/.kevin/bin/<tool>"

    Examples:
      | tool       | tasks           | error                                                   |
      | clj-mutate | spec clj-mutate | kevin tool: clj-mutate: SRC/bb.edn has no task 'mutate'    |
      | crap4clj   | crap4clj int    | kevin tool: crap4clj: SRC/bb.edn has no task 'crap'        |
      | dry4clj    | deintroverter   | kevin tool: dry4clj: SRC/src has no namespace dry4clj.core |

  # kevin tool entry point guard 2
  Scenario Outline: kevin tool entry point guard 2: a source without bb.edn fails the install
    Given a tool source without bb.edn
    When I run "kevin tool ensure <tool>" in the main checkout
    Then the exit status is 1
    And the error output contains "kevin tool: <tool>: no bb.edn in SRC"
    And there is no wrapper for "<tool>"

    Examples:
      | tool      |
      | crap4go   |
      | mutate4go |

  # kevin tool entry point guard 4
  Scenario: kevin tool entry point guard 4: a bb.edn that cannot be read fails the install and keeps the earlier wrapper
    Given a tool source whose bb.edn is "{:tasks {crap {}"
    And an earlier install left a wrapper for "crap4clj"
    When I run "kevin tool ensure crap4clj" in the main checkout
    Then the exit status is 1
    And the error output contains "kevin tool: crap4clj: SRC/bb.edn could not be read: "
    And the wrapper for "crap4clj" is unchanged
    And "kevin tool require crap4clj" in the main checkout prints "OK: crap4clj MAIN/.kevin/bin/crap4clj"
