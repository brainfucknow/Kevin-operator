"""Task-state recovery through the SDK's activity retry wrapper."""

import pytest
from mistralai.workflows.core.config.config import config

from kevin_pipeline import activities, catalogue, prompts, runner, kevinkit
from kevin_pipeline.models import CheckoutInfo, TaskState


@pytest.fixture
def stage_attempt(monkeypatch):
    state = TaskState(stage="coder", route=["coder", "qa"])
    builds = []
    role = catalogue.Role(name="coder", tier="strong", runs_as="subagent")
    monkeypatch.setattr(activities, "_load_catalogue", lambda main: ([role], {}))
    monkeypatch.setattr(catalogue, "mistral_model", lambda tiers, tier: "test-model")
    monkeypatch.setattr(prompts, "agent_instructions", lambda *args, **kwargs: "instructions")
    monkeypatch.setattr(kevinkit, "kevin_home", lambda: "/opt/kevin/kevin")
    monkeypatch.setattr(kevinkit, "read_state", lambda main, task_id: state.model_copy())
    monkeypatch.setattr(runner, "build_agent", lambda *args, **kwargs: builds.append(object()) or builds[-1])
    monkeypatch.setattr(config.worker, "retry_policy_max_attempts", 2)
    monkeypatch.setattr(config.worker, "local_retry_backoff_coefficient", 0)
    params = activities.StageRunInput(
        co=CheckoutInfo(main="/fake", worktree="/fake/task", branch="swarm/t-1"),
        task_id="t-1",
        role="coder",
    )
    return state, builds, params


@pytest.mark.parametrize(
    "stage,needs,status",
    [("qa", "none", "advanced"), ("coder", "operator", "blocked"),
     ("coder", "approval", "blocked"), ("done", "none", "advanced")],
)
async def test_retry_observes_progress_before_repeating_role(stage_attempt, monkeypatch, stage, needs, status):
    state, builds, params = stage_attempt
    calls = []

    async def run_agent(*args):
        calls.append(state.model_copy())
        state.stage, state.needs = stage, needs
        if len(calls) == 1:
            raise ConnectionError("model transport failed after recording progress")
        return "stale role repeated"

    monkeypatch.setattr(runner, "run_role_agent", run_agent)
    outcome = await activities.run_stage_role(params)
    assert (outcome.status, outcome.stage, outcome.needs) == (status, stage, needs)
    assert len(calls) == len(builds) == 1


@pytest.mark.parametrize(
    "stage,needs,status",
    [("qa", "none", "advanced"), ("done", "none", "advanced"),
     ("coder", "operator", "blocked"), ("coder", "approval", "blocked"),
     ("qa", "approval", "blocked")],
)
async def test_existing_progress_skips_agent(stage_attempt, monkeypatch, stage, needs, status):
    state, builds, params = stage_attempt
    state.stage, state.needs = stage, needs

    async def unexpected_agent(*args):
        pytest.fail("a stale or blocked role must not run")

    monkeypatch.setattr(runner, "run_role_agent", unexpected_agent)
    outcome = await activities.run_stage_role(params)
    assert (outcome.status, outcome.stage, outcome.needs) == (status, stage, needs)
    assert builds == []


async def test_retry_before_progress_still_runs_role(stage_attempt, monkeypatch):
    state, builds, params = stage_attempt
    calls = []

    async def run_agent(*args):
        calls.append(state.stage)
        if len(calls) == 1:
            raise ConnectionError("model transport failed before progress")
        state.stage = "qa"
        return "handoff complete"

    monkeypatch.setattr(runner, "run_role_agent", run_agent)
    outcome = await activities.run_stage_role(params)
    assert (outcome.status, outcome.stage, outcome.report) == ("advanced", "qa", "handoff complete")
    assert calls == ["coder", "coder"]
    assert len(builds) == 2


async def test_role_without_progress_still_fails(stage_attempt, monkeypatch):
    state, builds, params = stage_attempt

    async def run_agent(*args):
        return "no handoff"

    monkeypatch.setattr(runner, "run_role_agent", run_agent)
    outcome = await activities.run_stage_role(params)
    assert (outcome.status, outcome.stage, outcome.report) == ("failed", "coder", "no handoff")
    assert len(builds) == 1
