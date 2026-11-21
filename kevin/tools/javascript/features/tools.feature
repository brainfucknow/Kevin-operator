Feature: kevin-js tools

  kevin-js tools checks that node and the package manager are on PATH, adds the
  missing tool packages to devDependencies with the package manager, and
  checks that jscpd runs. The tool packages are: the runner (vitest unless
  package.json lists jest), @vitest/coverage-v8 for vitest, typescript, the
  complexity linter, @stryker-mutator/core, the Stryker runner plugin for the
  runner, @stryker-mutator/typescript-checker when tsconfig.json exists, and
  fast-check. The complexity linter is eslint and typescript-eslint when
  package.json lists eslint and not oxlint, else oxlint. Packages are added
  in one command, sorted.

  Background:
    Given a task worktree branched from main
    And fake JavaScript tools on PATH
    And the worktree has the lockfile "package-lock.json"

  # kevin-js tools 1
  Scenario: kevin-js tools 1: a project listing both linters gets no linter package
    Given package.json lists the devDependencies "vitest eslint oxlint"
    And tsconfig.json is absent
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js added the devDependencies "@stryker-mutator/core @stryker-mutator/vitest-runner @vitest/coverage-v8 fast-check typescript"

  # kevin-js tools 1
  Scenario: kevin-js tools 1: a project listing nothing gets every tool package
    Given package.json lists the devDependencies ""
    And tsconfig.json is absent
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js added the devDependencies "@stryker-mutator/core @stryker-mutator/vitest-runner @vitest/coverage-v8 fast-check oxlint typescript vitest"

  # kevin-js tools 1
  Scenario Outline: kevin-js tools 1: missing tool packages are added as devDependencies
    Given package.json lists the devDependencies "<listed>"
    And tsconfig.json is <tsconfig>
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js added the devDependencies "<added>"

    Examples:
      | listed                 | tsconfig | added                                                                                                                                     |
      | vitest                 | present  | @stryker-mutator/core @stryker-mutator/typescript-checker @stryker-mutator/vitest-runner @vitest/coverage-v8 fast-check oxlint typescript |
      | jest                   | absent   | @stryker-mutator/core @stryker-mutator/jest-runner fast-check oxlint typescript                                                           |
      | jest typescript eslint | present  | @stryker-mutator/core @stryker-mutator/jest-runner @stryker-mutator/typescript-checker fast-check typescript-eslint                       |
      | vitest fast-check      | absent   | @stryker-mutator/core @stryker-mutator/vitest-runner @vitest/coverage-v8 oxlint typescript                                                |

  # kevin-js tools 2
  Scenario: kevin-js tools 2: npm adds devDependencies its own way
    Given package.json lists the devDependencies "vitest"
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js ran "npm install --save-dev"

  # kevin-js tools 2
  Scenario: kevin-js tools 2: pnpm adds devDependencies its own way
    Given package.json declares packageManager "pnpm@9.1.0"
    And the worktree has the lockfile "pnpm-lock.yaml"
    And package.json lists the devDependencies "vitest"
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js ran "pnpm add --save-dev"

  # kevin-js tools 2
  Scenario: kevin-js tools 2: yarn adds devDependencies its own way
    Given package.json declares packageManager "yarn@4.5.0"
    And the worktree has the lockfile "yarn.lock"
    And package.json lists the devDependencies "vitest"
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js ran "yarn add --dev"

  # kevin-js tools 2
  Scenario: kevin-js tools 2: bun adds devDependencies its own way
    Given package.json declares packageManager "bun@1.1.30"
    And the worktree has the lockfile "bun.lock"
    And package.json lists the devDependencies "vitest"
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js ran "bun add --dev"

  # kevin-js tools 3
  Scenario Outline: kevin-js tools 3: nothing is added when every tool package is listed
    Given package.json lists the devDependencies "vitest @vitest/coverage-v8 typescript <linter> @stryker-mutator/core @stryker-mutator/vitest-runner fast-check"
    And tsconfig.json is absent
    When I run kevin-js with "tools"
    Then the exit status is 0
    And the output has the line "all JavaScript tools present"
    And kevin-js did not run "npm install"

    Examples:
      | linter                   |
      | oxlint                   |
      | eslint typescript-eslint |

  # kevin-js tools 4
  Scenario: kevin-js tools 4: jscpd is checked
    When I run kevin-js with "tools"
    Then the exit status is 0
    And kevin-js ran "npx --yes jscpd@4 --version"

  # kevin-js tools 5
  Scenario: kevin-js tools 5: node missing from PATH fails before anything is installed
    Given package.json declares packageManager "pnpm@9.1.0"
    And the worktree has the lockfile "pnpm-lock.yaml"
    And "node" is not on PATH
    When I run kevin-js with "tools"
    Then the exit status is 2
    And the error output contains "missing on PATH: node"
    And kevin-js ran nothing

  # kevin-js tools 5
  Scenario: kevin-js tools 5: pnpm missing from PATH fails before anything is installed
    Given package.json declares packageManager "pnpm@9.1.0"
    And the worktree has the lockfile "pnpm-lock.yaml"
    And "pnpm" is not on PATH
    When I run kevin-js with "tools"
    Then the exit status is 2
    And the error output contains "missing on PATH: pnpm"
    And kevin-js ran nothing

  # kevin-js tools 5
  Scenario: kevin-js tools 5: bun missing from PATH fails before anything is installed
    Given package.json declares packageManager "bun@1.1.30"
    And the worktree has the lockfile "bun.lock"
    And "bun" is not on PATH
    When I run kevin-js with "tools"
    Then the exit status is 2
    And the error output contains "missing on PATH: bun"
    And kevin-js ran nothing

  # kevin-js tools 6
  Scenario: kevin-js tools 6: a failed install fails
    Given the fake npm exits with status 1
    When I run kevin-js with "tools"
    Then the exit status is 2
    And the error output contains "npm install --save-dev failed"
