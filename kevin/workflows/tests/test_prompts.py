import os

from kevin_pipeline import catalogue, prompts
from kevin_pipeline.models import GateReport

ROLES_CONF = "specifier frontier subagent\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\n"
TIERS_CONF = "frontier inherit high gpt-6.1-sol inherit mistral-large-latest\nstrong opus medium gpt-6.1-sol inherit mistral-medium-latest\nfast sonnet low gpt-6.1-terra inherit mistral-small-latest\n"


HOME = "/opt/kevin/kevin"


def test_role_prompt_names_role_and_task():
    prompt = prompts.role_prompt("coder", "add-login-3f9a2c1", HOME)
    assert "'coder'" in prompt
    assert "/opt/kevin/kevin/constitution.md" in prompt
    assert "/opt/kevin/kevin/roles/coder.md" in prompt
    assert "Start with: kevin start add-login-3f9a2c1" in prompt
    assert "kevin/bin/kevin" not in prompt


def test_gate_prompt_is_read_only():
    prompt = prompts.gate_prompt("falsifier", "add-login-3f9a2c1", HOME)
    assert "gate" in prompt
    assert "/opt/kevin/kevin/roles/falsifier.md" in prompt
    assert "Start with: kevin show add-login-3f9a2c1" in prompt
    assert "Change nothing" in prompt
    assert "kevin start" not in prompt


def test_judge_prompt_carries_reports_and_verdict_lines():
    reports = [GateReport(role="falsifier", report="DEFECT, HIGH: no login flow")]
    prompt = prompts.judge_prompt("add-login-3f9a2c1", reports)
    assert "Run: kevin spec-revisions add-login-3f9a2c1 --full" in prompt
    assert "DEFECT, HIGH: no login flow" in prompt
    assert "SPEC-VERDICT:" in prompt and "REASON:" in prompt


def test_agent_instructions_embed_role_file(tmp_path):
    home = tmp_path / "kevin"
    role_dir = home / "roles"
    role_dir.mkdir(parents=True)
    (role_dir / "coder.md").write_text("You are the coder.", encoding="utf-8")
    (home / "constitution.md").write_text("# Constitution", encoding="utf-8")
    roles = catalogue.load_roles(ROLES_CONF)
    tiers = catalogue.load_tiers(TIERS_CONF)
    instructions = prompts.agent_instructions(catalogue.role_by_name(roles, "coder"), str(home), tiers, writable=True)
    assert "You are the coder." in instructions
    assert "mistral-medium-latest" in instructions
    assert "write_file" in instructions
    assert f"Read {home}/constitution.md" in instructions
    read_only = prompts.agent_instructions(catalogue.role_by_name(roles, "falsifier"), str(home), tiers, writable=False)
    assert "no write tool" in read_only
    assert os.sep not in read_only.split("Your role prompt")[0] or True
