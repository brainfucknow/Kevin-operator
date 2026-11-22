Feature: kevin-js typecheck

  With a tsconfig.json, kevin-js test runs "tsc --noEmit" before the tests.
  A type error stops kevin-js test with exit status 1 before the tests run.

  Background:
    Given a task worktree branched from main
    And fake JavaScript tools on PATH
    And the worktree has the lockfile "package-lock.json"
    And package.json lists "vitest" in devDependencies

  # kevin-js typecheck 1
  Scenario: kevin-js typecheck 1: tsc runs before the tests
    Given tsconfig.json is present
    When I run kevin-js with "test"
    Then the exit status is 0
    And kevin-js ran "npx --no-install tsc --noEmit" before "npx --no-install vitest run"

  # kevin-js typecheck 2
  Scenario Outline: kevin-js typecheck 2: a type error stops the tests
    Given tsconfig.json is present
    And the fake tsc exits with status <tsc_status>
    When I run kevin-js with "test"
    Then the exit status is 1
    And kevin-js did not run "vitest"

    Examples:
      | tsc_status |
      | 1          |
      | 2          |

  # kevin-js typecheck 3
  Scenario Outline: kevin-js typecheck 3: only test typechecks, and only with a tsconfig.json
    Given tsconfig.json is <tsconfig>
    When I run kevin-js with "<command>"
    Then the exit status is 0
    And kevin-js did not run "tsc"

    Examples:
      | tsconfig | command  |
      | absent   | test     |
      | present  | property |
      | present  | coverage |
