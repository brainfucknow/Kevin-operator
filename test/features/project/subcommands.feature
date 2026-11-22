Feature: kevin runs the runtime's other commands as subcommands

  Only kevin goes on PATH. "kevin <name> <args>" runs the runtime's
  kevin/bin/kevin-<name> with <args> unchanged, from the current
  directory, with the same output, error output and exit status, for the
  names cs, fs, vb, js, rb, java and mistral.

  # kevin runs the runtime's other commands as subcommands 1
  Scenario Outline: kevin runs the runtime's other commands as subcommands 1: a subcommand matches its wrapper
    Given a Kevin runtime at version "0.2.0" with its kevin on PATH
    And a git repository with one commit and no kevin
    And I ran "kevin init"
    And MISTRAL_API_KEY is not set
    When I run "kevin <name>" and the runtime's "kevin/bin/kevin-<name>" in the repository
    Then both have the same output, error output and exit status
    And the exit status of "kevin <name>" is <status>

    Examples:
      | name    | status |
      | cs      | 0      |
      | fs      | 0      |
      | vb      | 0      |
      | js      | 0      |
      | rb      | 0      |
      | java    | 2      |
      | mistral | 1      |
