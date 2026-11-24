# Rust tools

Run `kevin rust` in a Cargo package or workspace inside a Git worktree.
Run `kevin init` to exclude `tmp/` from Git.

## Setup

Use stable Rust, Cargo, Git, Bash, and Babashka. The examples use Rust and
Cargo 1.100.0. Test and property use the project's Cargo dependencies.

Install the tools for the analyses you use. DRY needs Node.js and npm.

```sh
rustup component add llvm-tools
cargo install --locked cargo-llvm-cov --version 0.9.2
cargo install --locked cargo-crap --version 0.8.0
cargo install --locked cargo-mutants --version 27.1.0
npx --yes jscpd@4.3.0 --version
kevin rust tools
```

`tools` shows the active Cargo and rustc versions and the available analysis
tools. It returns 2 if Cargo or rustc is unavailable.

## Select packages and tests

Use `--manifest-path PATH` to choose a manifest in the worktree. Use repeatable
`-p NAME` or `--package NAME` to select workspace members. Use `--workspace` to
select all members and `--exclude NAME` to omit a member. With no selection,
Kevin uses Cargo's default members for the manifest.

Test, property, coverage, CRAP, and mutation accept `--locked`,
`--features FEATURES`, `--all-features`, and `--no-default-features`.
Test and coverage pass libtest arguments after `--`.

```sh
kevin rust test --locked
kevin rust test -p api --features tls -- integration_
kevin rust property --locked -p api
kevin rust coverage --workspace --exclude examples -- --include-ignored
```

Reserve `property_` in libtest names for property cases. Mark each property
with `#[ignore = "run with Kevin property"]`. The example uses Proptest 1.11.0.

```rust
proptest::proptest! {
    #[test]
    #[ignore = "run with Kevin property"]
    fn property_reverse_twice(values in proptest::collection::vec(0u8..10, 0..16)) {
        let reversed: Vec<_> = values.iter().copied().rev().collect();
        proptest::prop_assert!(values.iter().eq(reversed.iter().rev()));
    }
}
```

`test` runs ordinary Cargo tests, including doctests. `property` runs ignored
cases with the reserved name. Test, coverage, CRAP, and mutation skip reserved
cases, including with `--include-ignored`. The filters require libtest argument
handling. Custom test harnesses are outside this convention. Cargo can pass a
run with zero matching tests.

## Reports and exit status

Analyses use the merge base of `HEAD` and `--base REF`. The default is the
first available ref from `KEVIN_BASE`, `main`, and `master`. Scope includes
committed, staged, unstaged, and untracked changes. It excludes deleted and
Git-ignored files and files outside the selected packages.

| Command | Behavior |
| --- | --- |
| `coverage` | Runs unit and integration tests with cargo-llvm-cov. Stable coverage omits doctests. The LCOV report contains selected production source and can include inline unit test lines. The summary counts changed lines with LCOV data and shows how many ran. |
| `crap` | Scores functions in changed production files with fresh LCOV. Scores above 10 fail. cargo-crap treats missing function coverage as zero coverage. Use `--all` to score all eligible files. |
| `dry` | Reports jscpd Rust clones with both file paths and line ranges. A clone fails the check when either range touches a changed line. Use `--all` to report all clones. |
| `mutate` | Runs the baseline and eligible mutants with cargo-mutants. It tests in copies with four workers. Use `--list` to preview the same selection. A failed baseline, missed mutant, or timeout fails the check. Zero eligible mutants passes. |

Production scope excludes standalone test, bench, example, and build-script
targets, their conventional directories, and Cargo build output.

Each report run gets its own directory. Read the path printed by that run.
Coverage writes `tmp/coverage/run-*/rust.lcov` on success. DRY writes
`tmp/jscpd/run-*/jscpd-report.json`. Mutation writes
`tmp/mutation/run-*/mutants.out/outcomes.json` when it tests mutants. Mutation
reports caught, missed, timed out, and unviable counts.

Exit 0 means pass. Exit 1 means a test, build, or quality failure. Exit 2 means
invalid usage, a missing prerequisite, or an analysis or report error.

## Scan source and dependencies

Scan changed Rust files with the [shared pinned Semgrep rules](semgrep.md).
Use the Git merge base and include untracked, nonignored files. Pass only
existing `.rs` files to Semgrep.

```sh
uvx semgrep@1.183.0 scan --config tmp/semgrep-rules/rust \
  --metrics=off --disable-version-check --error --jobs 4 -- changed.rs
```

For dependency changes, run cargo-audit with the workspace lockfile. Use
cargo-deny with the checked-in policy when the project has one. The commands
use cargo-audit 0.23.0 and cargo-deny 0.22.0.

```sh
cargo install --locked cargo-audit --version 0.23.0
cargo install --locked cargo-deny --version 0.22.0

workspace_manifest="$(cargo locate-project --workspace --message-format plain)"
cargo audit --file "$(dirname "$workspace_manifest")/Cargo.lock"
cargo deny --manifest-path "$workspace_manifest" --locked check advisories
```
