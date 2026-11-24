"""The prompts the workflow sends to its role agents. They mirror the prompts
kevin dispatch builds for the local backends (kevin/tools/kevin/src/kevin/dispatch.clj)."""

from __future__ import annotations

import os

from . import kevinkit
from .catalogue import Role, mistral_model, role_by_name
from .models import GateReport


def role_file(main: str, home: str, role: str) -> str:
    """The absolute path of the role's prompt: the main checkout's
    kevin/roles/<role>.md when it exists, for an added role and for a runtime
    role alike, else the runtime's roles/<role>.md."""
    return os.path.abspath(kevinkit.conf_file(main, home, os.path.join("roles", f"{role}.md")))


def role_prompt(role: str, task_id: str, home: str, prompt_file: str) -> str:
    """home is the runtime's kevin/ directory, as kevin home prints it."""
    return (
        f"You are the Kevin '{role}' role. Read {home}/constitution.md and "
        f"follow its reading instructions, then read {prompt_file}, then "
        f"do your role's work for task {task_id}. Start with: kevin start {task_id}"
    )


def gate_prompt(role: str, task_id: str, prompt_file: str) -> str:
    return (
        f"You are the Kevin '{role}' gate. Read {prompt_file}, then "
        f"audit the specification of task {task_id} that waits for approval. Start with: "
        f"kevin show {task_id}. Change nothing: no edits, no commits, no kevin "
        f"command that records. End with your report."
    )


def judge_prompt(task_id: str, gate_reports: list[GateReport]) -> str:
    reports = "\n\n".join(f"{r.role}:\n{r.report}" for r in gate_reports) or "(no gate reports)"
    return (
        f"Task {task_id}. Run: kevin spec-revisions {task_id} --full\n"
        f"Then read these gate reports of this spec revision verbatim:\n\n{reports}\n"
        f"End your report with SPEC-VERDICT: <converged|continue|needs-operator|stuck> "
        f"and REASON: <why>. Record nothing."
    )


def agent_instructions(
    role: Role, home: str, prompt_file: str, tiers: dict[str, dict[str, str]], writable: bool
) -> str:
    """The standing instructions of one role agent: the constitution of the
    runtime at home, the text of prompt_file, and how its tools map onto the
    task worktree."""
    model = mistral_model(tiers, role.tier)
    role_text = open(prompt_file, encoding="utf-8").read() if os.path.isfile(prompt_file) else ""
    tools = (
        "run_shell runs a shell command in the task worktree; read_file and write_file "
        "work on paths relative to the worktree root."
        if writable
        else "run_readonly_shell runs read-only inspection commands (kevin "
        "show, git log, git diff, cat) in the task worktree; read_file reads files "
        "relative to the worktree root. You have no write tool and must record nothing."
    )
    return (
        f"You are a Kevin role agent on model {model}. You work in a clone of the "
        f"project repository; the task worktree is your working directory. {tools}\n\n"
        f"Read {home}/constitution.md and obey every article it lists.\n\n"
        f"Your role prompt:\n\n{role_text}"
    )
