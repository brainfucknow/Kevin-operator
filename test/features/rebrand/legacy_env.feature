Feature: kevin honors legacy SWARMFORGE variables

  For every variable kevin reads, KEVIN_<name> wins when it is set. When it
  is not set, kevin reads SWARMFORGE_<name>. kevin sets only KEVIN_*
  variables for the processes it starts.

  Background:
    Given a git repository with one commit and no user.name or user.email
    And a project with kevin initialized

  # kevin honors legacy SWARMFORGE variables 1
  Scenario Outline: kevin honors legacy SWARMFORGE variables 1: the record author comes from KEVIN_USER, else SWARMFORGE_USER
    When I run "kevin new 'Add login' --route coder" with environment "<environment>"
    Then the exit status is 0
    And the newest record has the author "<author>"

    Examples:
      | environment                                                     | author                      |
      | KEVIN_USER=new                                                  | new <new@kevin.local>       |
      | SWARMFORGE_USER=old                                             | old <old@kevin.local>       |
      | KEVIN_USER=new SWARMFORGE_USER=old                              | new <new@kevin.local>       |
      | SWARMFORGE_USER=old SWARMFORGE_EMAIL=old@example.com            | old <old@example.com>       |
      | KEVIN_EMAIL=new@example.com SWARMFORGE_EMAIL=old@example.com    | operator <new@example.com>  |
      |                                                                 | operator <operator@kevin.local> |

  # kevin honors legacy SWARMFORGE variables 2
  Scenario: kevin honors legacy SWARMFORGE variables 2: a dispatched role sees only KEVIN variables set by kevin
    Given task "A" titled "Add login" was created on route "coder"
    And a fake claude on PATH that prints its environment
    When I run "kevin dispatch coder $A claude" with environment "SWARMFORGE_BACKEND=claude"
    Then the fake claude saw "KEVIN_DISPATCHED" set
    And the fake claude saw "KEVIN_ROLE" set to "coder"
    And the fake claude saw no variable that kevin set with the prefix "SWARMFORGE_"
