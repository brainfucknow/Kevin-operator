#!/usr/bin/env bash
# Put the Kevin runtime's kevin on PATH.
#
#   install.sh --user
#
# The directory of this script (the Kevin plugin) is the runtime. install.sh
# links $HOME/.local/bin/kevin to its kevin/bin/kevin and writes nothing else.
# Then run kevin init in each project.
set -euo pipefail

usage="install.sh: usage: install.sh --user (then run kevin init in a project)"
if [ "$#" -ne 1 ] || [ "$1" != "--user" ]; then
  echo "$usage" >&2
  exit 2
fi

HERE="$(cd "$(dirname "$0")" && pwd -P)"

# Run from $HERE, which has no bb.edn, so the caller's bb.edn cannot affect it.
minimum=""
min_pattern=':min-bb-version "([^"]*)"'
while IFS= read -r line; do
  [[ $line =~ $min_pattern ]] && minimum="${BASH_REMATCH[1]}"
done < "$HERE/kevin/tools/kevin/bb.edn"
needs="install.sh: Kevin needs Babashka $minimum or newer: https://babashka.org"
command -v bb >/dev/null || { echo "$needs" >&2; exit 1; }
if ! check_err="$(cd "$HERE" && bb "$HERE/kevin/bin/kevin" help 2>&1 >/dev/null)"; then
  [ -z "$check_err" ] || echo "$check_err" >&2
  if echo "$check_err" | grep -q '^kevin: Babashka .* is too old; kevin needs '; then
    echo "$needs" >&2
  else
    echo "install.sh: the Babashka check failed" >&2
  fi
  exit 1
fi

bin="$HOME/.local/bin"
link="$bin/kevin"
if [ -e "$link" ] && [ ! -L "$link" ]; then
  echo "install.sh: $link exists and is not a symbolic link; move it and run install.sh --user again" >&2
  exit 1
fi
mkdir -p "$bin"
ln -sfn "$HERE/kevin/bin/kevin" "$link"

case ":$PATH:" in
  *":$bin:"*) ;;
  *) echo "install.sh: add $bin to PATH" ;;
esac
