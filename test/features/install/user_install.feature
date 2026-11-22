Feature: install.sh --user puts kevin on PATH

  Kevin ships as a harness plugin. The plugin directory, a copy of this
  repository, is the Kevin runtime. "install.sh --user", run from that
  directory, makes the one link $HOME/.local/bin/kevin to the plugin's
  kevin/bin/kevin. It writes nothing else: no runtime copy, no skill, no
  agent, and nothing in any project. A second run ends in the same state.
  A link that points elsewhere is replaced, so a run from an updated plugin
  directory moves kevin to it. A path there that is not a symbolic link is
  refused and kept.

  Before it writes, install.sh checks Babashka as before: it runs kevin
  help from the plugin directory with the bb on PATH, so the guard in
  kevin/bin/kevin decides, and a bb.edn in the directory it runs from does
  not affect the check. Without bb, or when the check fails, it exits 1,
  its error output names Babashka and the minimum version, and it writes
  nothing.

  Any other argument list, including the old "install.sh <project>", is a
  usage error: exit 2, nothing is written, and the error line is
  "install.sh: usage: install.sh --user (then run kevin init in a project)".

  If $HOME/.local/bin is not on PATH, a successful run also prints
  "install.sh: add <HOME>/.local/bin to PATH", with <HOME> the absolute
  home directory.

  Background:
    Given a Kevin plugin directory at version "0.2.0"
    And a home directory with no ".local/bin/kevin"

  # install.sh --user puts kevin on PATH 1
  Scenario: install.sh --user puts kevin on PATH 1: the link points at the plugin's kevin
    When I run install.sh with "--user" 1 times
    Then the exit status is 0
    And "$HOME/.local/bin/kevin" is a symbolic link to the plugin's "kevin/bin/kevin"
    And running "$HOME/.local/bin/kevin version" prints "0.2.0"
    And the home directory has no new path outside ".local/bin"
    And the plugin directory has the same files with the same contents as before

  # install.sh --user puts kevin on PATH 2
  Scenario: install.sh --user puts kevin on PATH 2: a second run ends in the same state
    When I run install.sh with "--user" 2 times
    Then the exit status is 0
    And "$HOME/.local/bin/kevin" is a symbolic link to the plugin's "kevin/bin/kevin"
    And the home directory has no new path outside ".local/bin"

  # install.sh --user puts kevin on PATH 3
  Scenario: install.sh --user puts kevin on PATH 3: a link to another runtime is replaced
    Given "$HOME/.local/bin/kevin" is a symbolic link to "/opt/old-kevin/kevin/bin/kevin"
    When I run install.sh with "--user" 1 times
    Then the exit status is 0
    And "$HOME/.local/bin/kevin" is a symbolic link to the plugin's "kevin/bin/kevin"

  # install.sh --user puts kevin on PATH 4
  Scenario: install.sh --user puts kevin on PATH 4: a file that is not a link is kept
    Given the file "$HOME/.local/bin/kevin" holds "#!/bin/sh"
    When I run install.sh with "--user" 1 times
    Then the exit status is 1
    And the error output has the line "install.sh: $HOME/.local/bin/kevin exists and is not a symbolic link; move it and run install.sh --user again"
    And the file "$HOME/.local/bin/kevin" still holds "#!/bin/sh"

  # install.sh --user puts kevin on PATH 5
  Scenario Outline: install.sh --user puts kevin on PATH 5: other arguments are a usage error
    When I run install.sh with "<arguments>" 1 times
    Then the exit status is 2
    And the error output has the line "install.sh: usage: install.sh --user (then run kevin init in a project)"
    And the home directory has no path ".local/bin/kevin"

    Examples:
      | arguments     |
      |               |
      | .             |
      | --user extra  |

  # install.sh --user puts kevin on PATH 6
  Scenario: install.sh --user puts kevin on PATH 6: no bb on PATH writes nothing
    Given no directory on PATH holds bb
    When I run install.sh with "--user" 1 times
    Then the exit status is 1
    And the error output has a line containing "Babashka" and "1.12.216 or newer"
    And the home directory has no path ".local/bin/kevin"

  # install.sh --user puts kevin on PATH 7
  Scenario: install.sh --user puts kevin on PATH 7: a bb.edn in the working directory does not affect the check
    Given a working directory outside the plugin directory holds a bb.edn that requires Babashka "99.0.0"
    When I run install.sh with "--user" 1 times from that working directory
    Then the exit status is 0
    And the error output has no line containing "99.0.0"

  # install.sh --user puts kevin on PATH 8
  Scenario Outline: install.sh --user puts kevin on PATH 8: it says when the link directory is not on PATH
    Given "$HOME/.local/bin" <on-path> on PATH
    When I run install.sh with "--user" 1 times
    Then the exit status is 0
    And the output <has> the line "install.sh: add $HOME/.local/bin to PATH"

    Examples:
      | on-path | has           |
      | is not  | has           |
      | is      | does not have |
