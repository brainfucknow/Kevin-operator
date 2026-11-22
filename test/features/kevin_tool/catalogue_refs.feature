Feature: kevin tool catalogue refs

  A row of kevin/tools.conf is
  "<name> <kind> <source> <ref> <task> <rewrite> <needs>". The ref of a row of
  kind bb or bbmain is a full commit SHA: 40 lowercase hexadecimal
  digits. Its source is github.com/<owner>/<repo>, where owner and repo use
  only letters, digits, ".", "_" and "-", and neither is "." or "..". The ref
  of a row of kind clj is a Maven version: it starts with a digit, uses only
  letters, digits, ".", "_", "+" and "-", and does not end in "-SNAPSHOT" (so
  RELEASE and LATEST are refused). The ref of a row of kind local is "-". Rows with the same source pin the same ref.

  kevin check reports each broken rule as "CHECK: tools.conf: <problem>" on
  stderr and exits 1. kevin tool ensure checks the same rules for the tool and
  its needs chain before it writes anything or runs git, also when
  KEVIN_TOOL_SRC is set: on a problem it prints
  "kevin tool: tools.conf: <problem>" on stderr, exits 1, and changes nothing
  under the main checkout's .kevin/. kevin tool list prints each row's name,
  kind, source and ref.

  # kevin tool catalogue refs 1
  Scenario Outline: kevin tool catalogue refs 1: kevin check rejects a ref or source that breaks the rules
    Given a project with kevin initialized
    And the main checkout's tools.conf adds the line "<line>"
    When I run "kevin check" in the main checkout
    Then the exit status is 1
    And the error output contains "CHECK: tools.conf: <problem>"

    Examples:
      | line                                                                          | problem                                                                                     |
      | x bb github.com/a/x 0123abc t - -                                             | 'x' needs a 40-digit lowercase hex commit SHA as its ref, not '0123abc'                     |
      | x bb github.com/a/x 0123456789ABCDEF0123456789ABCDEF01234567 t - -            | 'x' needs a 40-digit lowercase hex commit SHA as its ref, not '0123456789ABCDEF0123456789ABCDEF01234567' |
      | x bbmain github.com/a/x --upload-pack=touch${IFS}pwned t - -                  | 'x' needs a 40-digit lowercase hex commit SHA as its ref, not '--upload-pack=touch${IFS}pwned' |
      | x clj g/x RELEASE t - -                                                       | 'x' needs a pinned Maven version as its ref, not 'RELEASE'                                  |
      | x clj g/x 1.0-SNAPSHOT t - -                                                  | 'x' needs a pinned Maven version as its ref, not '1.0-SNAPSHOT'                             |
      | x clj g/x 1.0"} t - -                                                         | 'x' needs a pinned Maven version as its ref, not '1.0"}'                                    |
      | x local kevin/bin/x 1.0 - - -                                            | 'x' is local and needs '-' as its ref, not '1.0'                                            |
      | x bb github.com/a/.. 0123456789abcdef0123456789abcdef01234567 t - -           | 'x' needs a source github.com/<owner>/<repo>, not 'github.com/a/..'                         |
      | x bb example.com/a/x 0123456789abcdef0123456789abcdef01234567 t - -           | 'x' needs a source github.com/<owner>/<repo>, not 'example.com/a/x'                         |
      | x bbmain github.com/a/x/y 0123456789abcdef0123456789abcdef01234567 t - -      | 'x' needs a source github.com/<owner>/<repo>, not 'github.com/a/x/y'                        |
      | x bb github.com/a/x t - -                                                     | 'x' needs exactly <name> <kind> <source> <ref> <task> <rewrite> <needs>                     |

  # kevin tool catalogue refs 2
  Scenario: kevin tool catalogue refs 2: kevin check rejects rows of one source with different refs
    Given a project with kevin initialized
    And the main checkout's tools.conf pins "ir-dry-checker" at "0123456789abcdef0123456789abcdef01234567"
    When I run "kevin check" in the main checkout
    Then the exit status is 1
    And the error output contains "CHECK: tools.conf: 'ir-dry-checker' pins github.com/unclebob/Acceptance-Pipeline-Specification at a different ref than 'gherkin-parser'"

  # kevin tool catalogue refs 3
  Scenario Outline: kevin tool catalogue refs 3: ensure refuses a broken row before it writes anything or runs git
    Given a project with kevin initialized
    And no tool source override
    And a task worktree
    And the task worktree's tools.conf adds the line "<line>"
    And a snapshot of "MAIN/.kevin"
    When I run "kevin tool ensure <tool>" in the task worktree
    Then the exit status is 1
    And the error output contains "kevin tool: tools.conf: <problem>"
    And "MAIN/.kevin" matches the snapshot

    Examples:
      | tool | line                                                                        | problem                                                                                        |
      | x    | x bbmain github.com/a/x --upload-pack=touch${IFS}pwned t - -                | 'x' needs a 40-digit lowercase hex commit SHA as its ref, not '--upload-pack=touch${IFS}pwned' |
      | x    | x clj g/x 1.0"} t - -                                                       | 'x' needs a pinned Maven version as its ref, not '1.0"}'                                       |
      | x    | x bb github.com/a/.. 0123456789abcdef0123456789abcdef01234567 t - -         | 'x' needs a source github.com/<owner>/<repo>, not 'github.com/a/..'                            |
      | y    | y bb github.com/unclebob/crap4go 0123456789abcdef0123456789abcdef01234567 crap4go - - | 'y' pins github.com/unclebob/crap4go at a different ref than 'crap4go'                  |

  # kevin tool catalogue refs 4
  Scenario: kevin tool catalogue refs 4: list prints the ref
    Given a project with kevin initialized
    And the main checkout's tools.conf pins "dry4go" at "0123456789abcdef0123456789abcdef01234567"
    When I run "kevin tool list" in the main checkout
    Then the exit status is 0
    And the output has a line with the words "dry4go bb github.com/unclebob/dry4go 0123456789abcdef0123456789abcdef01234567"
