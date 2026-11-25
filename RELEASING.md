# Releasing Kevin

Follow these steps to release Kevin vX.Y.Z.

1. Choose vX.Y.Z by semver from the changes since the last tag:
   `git log <last tag>..main`.
2. On a branch, set `"version"` to `X.Y.Z` in all four plugin manifests:
   - `plugin.json`: the Mistral Vibe manifest.
   - `.claude-plugin/plugin.json`: the manifest that `kevin version` reads.
   - `.codex-plugin/plugin.json`.
   - `.github/plugin/plugin.json`.

   The marketplace files carry no version.
3. When the release changes behavior a project relies on, raise
   `Minimum Kevin version:` in `kevin/project.md`. Raise it also in
   `README.md` and `kevin/README.md` where they name a minimum that the
   change affects.
4. Run `bash test/kevin_test.sh`. It must pass.
5. Open a pull request into `main`. Never commit or push to `main`
   directly. A human merges it.
6. After the merge, tag the merge commit and publish the release:
   `gh release create vX.Y.Z --target <merge commit sha> --generate-notes`.
   Add `--title` when the operator gives one.
7. Tell users to update each plugin install (Claude Code, Copilot CLI,
   Mistral Vibe) and to rerun `install.sh --user` from the updated one.
   `kevin version` then prints `X.Y.Z`.

Kevin does not tell apart two copies with the same version. A behavior
change that a project needs requires a new version.
