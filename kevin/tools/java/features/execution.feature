Feature: kevin-java execution

  kevin-java supports a single Maven module with pom.xml, src/main/java and
  src/test/java, Java 17 or later, and JUnit 5. Tests run through a dedicated
  runner; Maven may prepare dependencies and compile classes, with test
  execution disabled. JUnit tests tagged property run only via property.
  Commands succeed with 0, fail tests or quality findings with 1, and report
  usage, missing prerequisites or invalid inputs with 2 and a kevin-java:
  diagnostic on stderr. No project pom.xml edit is implicit.

  Background:
    Given a single-module Maven Java project in a task worktree
    And controllable Java, Maven and JUnit tools on PATH

  # kevin-java execution 1
  Scenario Outline: kevin-java execution 1: normal and property tests stay separate
    Given the project has a passing ordinary JUnit test covering production code
    And the project has a passing JUnit test tagged property
    When I run kevin-java with "<command>"
    Then the exit status is 0
    And the dedicated runner executes "<selected>" tests
    And Maven does not execute tests

    Examples:
      | command  | selected |
      | test     | ordinary |
      | property | property |
      | coverage | ordinary |

  # kevin-java execution 2
  Scenario Outline: kevin-java execution 2: test failures fail the command
    Given an ordinary JUnit test fails
    When I run kevin-java with "<command>"
    Then the exit status is 1
    And the output identifies the failing test

    Examples:
      | command  |
      | test     |
      | coverage |

  # kevin-java execution 3
  Scenario: kevin-java execution 3: compilation failure prevents the runner
    Given the Java sources fail compilation
    When I run kevin-java with "test"
    Then the exit status is 1
    And the dedicated runner was not started

  # kevin-java execution 4
  Scenario Outline: kevin-java execution 4: unsupported inputs fail clearly
    Given the project has "<problem>"
    When I run kevin-java with "test"
    Then the exit status is 2
    And the error output identifies "<problem>"
    And the dedicated runner was not started

    Examples:
      | problem              |
      | no pom.xml           |
      | Java older than 17   |
      | no Maven executable  |
      | a Maven reactor root |

  # kevin-java execution 5
  Scenario Outline: kevin-java execution 5: stale coverage cannot make a run pass
    Given a stale JaCoCo XML report is <state>
    And a successful dedicated coverage run writes no new JaCoCo XML report
    When I run kevin-java with "coverage"
    Then the exit status is 2
    And the error output identifies the missing new coverage report

    Examples:
      | state   |
      | present |
      | absent  |

  # kevin-java execution 6
  Scenario: kevin-java execution 6: coverage produces a usable JaCoCo report
    Given the project has a passing ordinary JUnit test covering production code
    When I run kevin-java with "coverage"
    Then the exit status is 0
    And a new JaCoCo XML report records the covered production code
    And the output identifies the coverage report path
