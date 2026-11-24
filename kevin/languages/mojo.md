# Mojo

Kevin uses the `mojo` executable on `PATH`. The tested baseline is Mojo 1.2.0
on Linux aarch64. Use the `.mojo` extension. Run `kevin mojo tools` to see
the SDK version and whether `jscpd` and `kcov` are available.

## Set up a project SDK

For a new uv project, run `uv init`, then `uv add 'mojo==1.2.0'`. For an
existing locked project, run the command through uv.

```sh
uv run --locked -- kevin mojo tools
uv run --locked -- kevin mojo test -I src
```

For a new Pixi project, run the following commands.

```sh
pixi init -c https://conda.modular.com/max/ -c conda-forge
pixi add 'mojo==1.2.0'
```

For an existing locked project, run the command through Pixi.

```sh
pixi run --locked --executable kevin mojo tools
pixi run --locked --executable kevin mojo test -I src
```

Kevin leaves project manifests and lockfiles intact. See
[Install Mojo](https://mojolang.org/install/) for SDK prerequisites.

## Run suites

Give each suite a `main` function that calls
`TestSuite.discover_tests[__functions_in_module()]().run()`. Use
`std.testing.prop` for property tests. See
[Testing in Mojo](https://mojolang.org/docs/tools/testing/).

- `kevin mojo test` runs `test/**/test_*.mojo` in path order.
- `kevin mojo property` runs `test/**/property_*.mojo` in path order.
- Pass one or more matching suite paths inside the worktree to select files.
  Repeat `-I DIR` to add import directories. Quote paths with spaces.
- With one explicit suite, pass runner arguments after `--`.

```sh
kevin mojo test -I src "test/unit tests/test_math.mojo" -- --only test_add
kevin mojo property -I src
```

Both commands need only Mojo. They stop at the first compiler or suite failure
and return its status. Invalid usage and an empty selection return 2.

## Measure line coverage

Install [kcov](https://github.com/SimonKagstrom/kcov) and use a Linux
environment that can trace child processes. Pass the same options as for
`test`. Use `--source DIR` to select a source directory inside the worktree.
The default source directory is `src/`. Reports exclude
`test_*.mojo` and `property_*.mojo` files.

```sh
kevin mojo coverage -I src --source src test/test_math.mojo -- --skip test_slow
```

Kevin prints the Cobertura paths for each run under `tmp/coverage/mojo-*/`.
Coverage counts source lines the compiler maps into the executable's debug
line tables. A branch can compile to a single mapped line. Unsupported hosts,
missing reports, and runs with zero measured source lines return 2.

## Find duplicate blocks

Put jscpd 4.3.0 on `PATH`. Run `kevin mojo dry` from a Git worktree.
The command prints clones that touch changed lines, including staged, unstaged,
and untracked changes. It uses the merge base with the first available ref in
`KEVIN_BASE`, `main`, and `master`. Pass `--base REF` to choose the ref or
`--all` to report every clone.

Kevin skips ignored and deleted files and generated directories such as
`tmp/`, `build/`, `dist/`, `generated/`, and `target/`. The text
tokenizer can match comments and has no Mojo syntax model. It looks for blocks
of at least five lines and 30 tokens. The scanner writes its full JSON report
to `tmp/jscpd/jscpd-report.json`. The command returns 1 for clones in scope,
0 for a clean scan, and 2 for scanner or report errors.

## Audit dependencies

`kevin mojo crap` and `kevin mojo mutate` return 2 because Kevin has no
verified Mojo complexity or mutation tool. There is no verified Mojo source
security scanner.

- For a uv project, run `uv audit --locked`. See the
  [uv audit reference](https://docs.astral.sh/uv/reference/cli/#uv-audit).
- For a Pixi project, run `pixi install --locked`. Run
  `grype dir:.pixi/envs/default` to scan the installed default environment.
  Advisory coverage depends on the available package IDs and vulnerability
  database. See [Pixi security](https://pixi.prefix.dev/latest/security/).
