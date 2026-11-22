Feature: kevin-js tests

  kevin-js test runs the unit and acceptance tests without property tests;
  kevin-js property runs only the property tests (files named *.property.test.*).
  The runner is vitest when package.json lists it, else jest.

  Background:
    Given a task worktree branched from main
    And fake JavaScript tools on PATH
    And the worktree has the lockfile "package-lock.json"

  # kevin-js tests 1
  Scenario Outline: kevin-js tests 1: package.json picks the runner
    Given package.json lists "<package>" in <section>
    When I run kevin-js with "test"
    Then the exit status is 0
    And kevin-js ran "<command>"

    Examples:
      | package | section         | command                     |
      | vitest  | devDependencies | npx --no-install vitest run |
      | vitest  | dependencies    | npx --no-install vitest run |
      | jest    | devDependencies | npx --no-install jest       |
      | jest    | dependencies    | npx --no-install jest       |

  # kevin-js tests 2
  Scenario: kevin-js tests 2: vitest wins when both runners are listed
    Given package.json lists "jest" in devDependencies
    And package.json lists "vitest" in devDependencies
    When I run kevin-js with "test"
    Then the exit status is 0
    And kevin-js ran "npx --no-install vitest run"
    And kevin-js did not run "jest"

  # kevin-js tests 3
  Scenario Outline: kevin-js tests 3: no runner in package.json fails
    Given package.json lists "mocha" in devDependencies
    When I run kevin-js with "<command>"
    Then the exit status is 2
    And the error output contains "no test runner (vitest or jest) in package.json"

    Examples:
      | command  |
      | test     |
      | property |
      | coverage |

  # kevin-js tests 4
  Scenario Outline: kevin-js tests 4: test leaves out property tests
    Given package.json lists "<runner>" in devDependencies
    When I run kevin-js with "test"
    Then the command line that runs "<runner>" contains "<flag>"
    And the command line that runs "<runner>" contains "<pattern>"

    Examples:
      | runner | flag                     | pattern              |
      | vitest | --exclude                | **/*.property.test.* |
      | jest   | --testPathIgnorePatterns | \.property\.test\.   |

  # kevin-js tests 5
  Scenario Outline: kevin-js tests 5: test passes extra arguments to the runner
    Given package.json lists "<runner>" in devDependencies
    When I run kevin-js with "test <extra>"
    Then the exit status is 0
    And the command line that runs "<runner>" ends with " <passed>"

    Examples:
      | runner | extra          | passed         |
      | vitest | --reporter=dot | --reporter=dot |
      | jest   | --ci --silent  | --ci --silent  |

  # kevin-js tests 6
  Scenario Outline: kevin-js tests 6: property runs only property tests
    Given package.json lists "<runner>" in devDependencies
    When I run kevin-js with "property"
    Then the exit status is 0
    And kevin-js ran "<command>"
    And the command line that runs "<runner>" contains "<pattern>"
    And the command line that runs "<runner>" does not contain "--exclude"
    And the command line that runs "<runner>" does not contain "--testPathIgnorePatterns"

    Examples:
      | runner | command                     | pattern            |
      | vitest | npx --no-install vitest run | .property.test.    |
      | jest   | npx --no-install jest       | \.property\.test\. |

  # kevin-js tests 7
  Scenario Outline: kevin-js tests 7: a failing runner exits 1
    Given package.json lists "<runner>" in devDependencies
    And the fake <runner> exits with status 1
    When I run kevin-js with "<command>"
    Then the exit status is 1

    Examples:
      | runner | command  |
      | vitest | test     |
      | vitest | property |
      | jest   | test     |
      | jest   | property |
