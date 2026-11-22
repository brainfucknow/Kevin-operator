Feature: kevin-java installation

  The consumer installation contains the Java wrapper and all runtime
  resources, including reviewed compatibility patches. kevin tool acquires each
  Java tool at its tools.conf commit, applies those patches, builds its JAR
  with Maven test execution disabled, and creates a project-local executable
  wrapper. It never assumes that a Java tool has a Babashka entry point.

  # kevin-java installation 1
  Scenario Outline: kevin-java installation 1: pinned Java tools can be installed and required
    Given a fresh Kevin consumer project
    And a controllable upstream checkout of "<tool>" at its configured commit
    When I run "kevin tool ensure <tool>" in the consumer project
    Then the exit status is 0
    And the installed wrapper runs the patched Java JAR
    And the output identifies the configured upstream commit
    And Maven does not execute tests
    And "kevin tool require <tool>" succeeds

    Examples:
      | tool        |
      | crap4java   |
      | dry4java    |
      | mutate4java |

  # kevin-java installation 2
  Scenario: kevin-java installation 2: a failed compatibility patch cannot publish a wrapper
    Given a fresh Kevin consumer project
    And the pinned Java tool source does not accept its required compatibility patch
    When I install that Java tool through kevin tool ensure
    Then the exit status is nonzero
    And the error output identifies the patch failure
    And no wrapper is published for that Java tool

  # kevin-java installation 3
  Scenario: kevin-java installation 3: reinstalling Java tools is safe
    Given a Kevin consumer project with the patched Java tools installed
    When I ensure the same Java tools again
    Then the exit status is 0
    And the installed tools remain usable at their configured commits

  # kevin-java installation 4
  Scenario: kevin-java installation 4: Java commands work in a consumer installation
    Given a new Git project with a passing Maven JUnit 5 application
    When I install Kevin into that project with install.sh
    And I run kevin-java with "tools" in that project
    And I run kevin-java with "test" in that project
    Then the exit status is 0
    And the ordinary JUnit tests pass through the dedicated runner
    And no runtime dependency is read from the harness source checkout
    And the application pom.xml is unchanged
