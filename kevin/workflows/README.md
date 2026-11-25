# Kevin on Mistral Workflows

This project serves the Kevin pipeline on the Mistral platform for
sessions that have no subagent backend: no `claude`, `codex` or `copilot` CLI
on PATH, so `kevin dispatch` would otherwise refuse with "no backend found"
(issue #114).

Two workflows:

| Workflow | Execution | Purpose |
|---|---|---|
| `kevin-pipeline` | one per task | runs the task's stages until done or blocked |
| `kevin-role` | one per role or gate | what `kevin dispatch <role> <id> mistral` starts |

One workflow execution per task, one Agents SDK agent per role. The agent's
model is the role's tier mapped through the `vibe` column of
`kevin/tiers.conf`. All task bookkeeping goes through
`kevin` on the task branch: the worker clones the repository,
fetches `swarm/<task-id>`, works in a `kevin start` worktree, and pushes the
branch when the execution ends. Gate roles and the spec-judge run as workflow
steps that record nothing (read-only agent tools plus a before/after snapshot
check); the execution result carries their reports and the spec verdict so
the operator's session can show them and record the verdict with
`kevin spec-verdict`.

## kevin side

`kevin dispatch <role> <id> mistral` and `kevin run <id> mistral` call
`kevin mistral` (the runtime's `kevin/bin/kevin-mistral`), which pushes the local task branch, starts the
execution through the REST API (`POST /v1/workflows/<name>/execute`), polls
`GET /v1/workflows/executions/<id>` until it ends, and fetches the branch
back. Exit 0 on done/advanced, 2 on blocked, 1 on failure. `kevin run` with the
mistral backend starts one `kevin-pipeline` execution per call.

The mistral backend is detected when no CLI backend is on PATH and
`MISTRAL_API_KEY` is set, or with `KEVIN_BACKEND=mistral`. It needs a
git remote: the worker clones that URL. Environment:

| Variable | Default |
|---|---|
| `MISTRAL_API_KEY` | required |
| `MISTRAL_API_URL` | `https://api.mistral.ai` |
| `KEVIN_REMOTE` | `origin` |
| `KEVIN_MISTRAL_ROLE_WORKFLOW` | `kevin-role` |
| `KEVIN_MISTRAL_PIPELINE_WORKFLOW` | `kevin-pipeline` |
| `KEVIN_MISTRAL_TIMEOUT_SECONDS` | `3600` |

## Run the worker

```sh
uv sync
MISTRAL_API_KEY=... uv run python -m kevin_pipeline.worker
```

Deploy it as a managed workflow deployment so executions run without a local
worker (see the Mistral Workflows documentation). The `Dockerfile` builds the
worker image: Python, git, Babashka, and this checkout's Kevin runtime with
`kevin` on PATH. Build it from the repository root:
`docker build -f kevin/workflows/Dockerfile .`. The worker reads the
constitution from `kevin home`. It reads each role prompt and the catalogue
from the project's `kevin/`, else from the runtime. A project's
`kevin/roles/<role>.md` replaces a runtime role's prompt in this backend only;
the role's prompt names that file. A managed worker has
no `MISTRAL_API_KEY`; `completion.py` gives every role agent a completion config
whose provider calls the model through the workflow worker's client
(`get_mistral_client`), which authenticates each request with the deployment
service-account token mounted at `MISTRAL_SA_TOKEN_PATH` — the raw token is
not a static API key. Locally, the same config falls back to
`MISTRAL_CLIENT_API_KEY`/`MISTRAL_API_KEY`. Pushing the task branch back needs GitHub write
access: bind a workspace secret holding a token with `repo` scope as the
`GITHUB_TOKEN` env var of the deployment (for example secret `github_token`).
`kevinkit` passes it to git through a `GIT_CONFIG_*` env pair, never through argv,
so it stays out of logs and error messages. Cloning and fetching public
repositories works without it.

## Tests

```sh
uv run --frozen pytest
```

The workflow tests call the workflow's `run` method directly with the
activities replaced by fakes, so no git, kevin, model, or network runs.
`--frozen` runs with the checked-in `uv.lock` as-is instead of re-resolving;
CI runs the same command.

## Shared uv state

Harness sessions, task worktrees, and subagents share one uv state instead of
rebuilding it per worktree. The session env, which the harness sources from
the login shell for every command, exports:

| Variable | Value |
|---|---|
| `UV_CACHE_DIR` | `~/.cache/uv` |
| `UV_PYTHON_INSTALL_DIR` | `~/.local/share/uv/python` |
| `UV_PROJECT_ENVIRONMENT` | `<repo>/kevin/workflows/.venv` |

`~/.config/uv/uv.toml` pins `cache-dir` to the same shared cache. The project
environment stays inside the repository, at the main checkout's `.venv`, so
every worktree reuses it through the absolute path. `pyproject.toml` sets
`pythonpath = ["src"]`, so tests in a task worktree still import that
worktree's sources, not the main checkout's.
