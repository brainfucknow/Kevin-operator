Feature: install.sh migrates a legacy install

  A legacy install has swarmforge/ and no kevin/. install.sh moves
  swarmforge/ to kevin/, so every project-owned file stays
  (kevin/constitution/articles/project.md, kevin/*.conf, kevin/verify.conf,
  kevin/.gitignore and any unshipped file), and removes the tool source
  directories the old install shipped under their old names
  (kevin/tools/sf/ and the sf-named namespace directories). Then it installs
  as usual: it ships the payload, removes the shipped files of the old names
  (bin/sf and bin/sf-*), regenerates the agents, and runs kevin init, which
  replaces the old commit-msg hook.

  In AGENTS.md it replaces the old swarm line with the new one in place. In
  .gitignore it adds ".kevin/" and keeps ".swarmforge/". It does not touch
  .swarmforge/, .worktrees/, git branches, or the index; the operator
  commits. In each kept project-owned file under kevin/ it replaces the old
  names as plain text (swarmforge/tools/sf/, swarmforge/bin/sf and
  swarmforge/bin/sf-*, the sf-* command names, SwarmForge-*, SWARMFORGE_*,
  swarmforge/), and for each file it changed it prints "install.sh: rewrote
  the old names in <path>".

  With both swarmforge/ and kevin/ present, it prints "install.sh: both
  swarmforge/ and kevin/ exist; keep one", exits 1, and changes nothing.

  Background:
    Given a git repository with a legacy install

  # install.sh migrates a legacy install 1
  Scenario: install.sh migrates a legacy install 1: the old tree moves to kevin/ and project files stay
    Given the file "swarmforge/routes.conf" in the repository holds "mine: coder,qa"
    And the file "swarmforge/notes.txt" in the repository holds "x"
    And the file "swarmforge/tools/sf/src/sf/cli.clj" in the repository holds "(ns sf.cli)"
    And the file "swarmforge/tools/core/src/sfcore/shell.clj" in the repository holds "(ns sfcore.shell)"
    When I run install.sh into it 1 times
    Then the exit status is 0
    And the repository has no path "swarmforge"
    And the file "kevin/routes.conf" in the repository holds "mine: coder,qa"
    And the file "kevin/notes.txt" in the repository holds "x"
    And the repository has the file "kevin/bin/kevin"
    And the repository has no file "kevin/bin/sf"
    And the repository has no file under "kevin/bin" whose name starts with "sf-"
    And the repository has no path "kevin/tools/sf"
    And the repository has no path "kevin/tools/core/src/sfcore"
    And the commit-msg hook has the line "# kevin commit-msg hook"

  # install.sh migrates a legacy install 2
  Scenario: install.sh migrates a legacy install 2: AGENTS.md and .gitignore get the new names
    When I run install.sh into it 1 times
    Then the exit status is 0
    And the file "AGENTS.md" in the repository has no line that mentions "swarmforge/bin/sf"
    And the file "AGENTS.md" in the repository has the new swarm line once
    And the file ".gitignore" in the repository has the line ".kevin/"
    And the file ".gitignore" in the repository has the line ".swarmforge/"

  # install.sh migrates a legacy install 3
  Scenario: install.sh migrates a legacy install 3: a kept file that names the old names is rewritten
    Given the file "swarmforge/verify.conf" in the repository holds "bb swarmforge/bin/sf-cs test"
    When I run install.sh into it 1 times
    Then the exit status is 0
    And the file "kevin/verify.conf" in the repository holds "bb kevin/bin/kevin-cs test"
    And the output has the line "install.sh: rewrote the old names in kevin/verify.conf"

  # install.sh migrates a legacy install 4
  Scenario: install.sh migrates a legacy install 4: both trees present is refused
    Given the file "kevin/roles.conf" in the repository holds "x"
    When I run install.sh into it 1 times
    Then the exit status is 1
    And the error output has the line "install.sh: both swarmforge/ and kevin/ exist; keep one"
    And the repository did not change

  # install.sh migrates a legacy install 5
  Scenario: install.sh migrates a legacy install 5: a second run changes nothing more
    When I run install.sh into it 2 times
    Then the exit status is 0
    And the file "AGENTS.md" in the repository has the new swarm line once
    And the file ".gitignore" in the repository has no line twice
    And the repository has no path "swarmforge"

  # install.sh migrates a legacy install 6
  Scenario: install.sh migrates a legacy install 6: a task started before the migration still finishes
    Given task "A" titled "Add login" was started on route "coder" before the migration
    And task "A" has a new commit in its worktree that does not touch swarmforge/
    When I run install.sh into it 1 times
    And I commit every change in the main checkout
    And I run "kevin handoff $A -m done"
    And I run "kevin finish $A"
    Then the exit status is 0
    And the base branch has the commit of task "A"
    And the repository has no path "swarmforge"
