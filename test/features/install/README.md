# Install acceptance features

`user_install.feature` verifies `install.sh --user`: the one link
`$HOME/.local/bin/kevin` to the plugin's `kevin/bin/kevin`, its Babashka
check, and its usage errors.

Run `bash test/acceptance.sh` from the repository root. The fixtures use the
real installer, Git, and Babashka with a scratch `HOME` and a scratch copy of
this repository as the plugin directory. `$HOME` in a step is that scratch
home. See the [install step handlers](../../acceptance/src/acceptance/install_steps.clj)
for operations and assertions.
