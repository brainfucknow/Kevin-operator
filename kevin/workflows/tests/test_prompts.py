import os

from kevin_pipeline import catalogue, prompts
from kevin_pipeline.models import GateReport

ROLES_CONF = "specifier frontier subagent\nfalsifier strong subagent gate\nspec-judge strong subagent spec-judge\ncoder strong subagent\n"
TIERS_CONF = "frontier inherit high gpt-6.1-sol inherit mistral-large-latest\nstrong opus medium gpt-6.1-sol inherit mistral-medium-latest\nfast sonnet low gpt-6.1-terra inherit mistral-small-latest\n"


HOME = "/opt/kevin/kevin"


def test_role_prompt_names_role_and_task():
    prompt = prompts.role_prompt("coder", "add-login-3f9a2c1", HOME, "/opt/kevin/kevin/roles/coder.md")
    assert "'coder'" in prompt
    assert "/opt/kevin/kevin/constitution.md" in prompt
    assert "/opt/kevin/kevin/roles/coder.md" in prompt
    assert "Start with: kevin start add-login-3f9a2c1" in prompt
    assert "kevin/bin/kevin" not in prompt


def test_gate_prompt_is_read_only():
    prompt = prompts.gate_prompt("falsifier", "add-login-3f9a2c1", "/opt/kevin/kevin/roles/falsifier.md")
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


def _checkouts(tmp_path, runtime_roles, project_roles):
    """A runtime home and a main checkout, each holding the named role files
    with text that tells them apart."""
    home = tmp_path / "runtime" / "kevin"
    main = tmp_path / "main"
    (home / "roles").mkdir(parents=True)
    (main / "kevin" / "roles").mkdir(parents=True)
    (home / "constitution.md").write_text("# Constitution", encoding="utf-8")
    for role in runtime_roles:
        (home / "roles" / f"{role}.md").write_text(f"Runtime {role} text.", encoding="utf-8")
    for role in project_roles:
        (main / "kevin" / "roles" / f"{role}.md").write_text(f"Project {role} text.", encoding="utf-8")
    return str(home), str(main)


ROLES_WITH_ADDED = ROLES_CONF + "designer strong subagent\ncritic strong subagent gate\n"


def _stage(home, main, role):
    roles = catalogue.load_roles(ROLES_WITH_ADDED)
    tiers = catalogue.load_tiers(TIERS_CONF)
    role_file = prompts.role_file(main, home, role)
    instructions = prompts.agent_instructions(catalogue.role_by_name(roles, role), home, role_file, tiers, writable=True)
    return role_file, instructions, prompts.role_prompt(role, "add-login-3f9a2c1", home, role_file)


def test_added_role_reads_the_project_role_file(tmp_path):
    home, main = _checkouts(tmp_path, ["coder"], ["designer"])
    role_file, instructions, prompt = _stage(home, main, "designer")
    project_file = os.path.join(main, "kevin", "roles", "designer.md")
    assert role_file == project_file
    assert "Project designer text." in instructions
    assert f"read {project_file}," in prompt
    assert f"{home}/roles/designer.md" not in prompt


def test_project_role_file_replaces_the_runtime_one(tmp_path):
    home, main = _checkouts(tmp_path, ["coder"], ["coder"])
    role_file, instructions, prompt = _stage(home, main, "coder")
    project_file = os.path.join(main, "kevin", "roles", "coder.md")
    assert role_file == project_file
    assert "Project coder text." in instructions
    assert "Runtime coder text." not in instructions
    assert f"read {project_file}," in prompt
    assert f"{home}/roles/coder.md" not in prompt


def test_runtime_role_without_a_project_file_reads_the_runtime_one(tmp_path):
    home, main = _checkouts(tmp_path, ["coder"], [])
    role_file, instructions, prompt = _stage(home, main, "coder")
    runtime_file = os.path.join(home, "roles", "coder.md")
    assert role_file == runtime_file
    assert "Runtime coder text." in instructions
    assert f"read {runtime_file}," in prompt
    assert os.path.join(main, "kevin", "roles") not in prompt


def test_role_file_is_absolute_for_a_relative_checkout(tmp_path, monkeypatch):
    home, main = _checkouts(tmp_path, [], ["designer"])
    monkeypatch.chdir(tmp_path)
    assert prompts.role_file("main", home, "designer") == os.path.join(main, "kevin", "roles", "designer.md")


def test_gate_reads_and_names_the_project_role_file(tmp_path):
    home, main = _checkouts(tmp_path, ["falsifier"], ["critic"])
    roles = catalogue.load_roles(ROLES_WITH_ADDED)
    tiers = catalogue.load_tiers(TIERS_CONF)
    role_file = prompts.role_file(main, home, "critic")
    project_file = os.path.join(main, "kevin", "roles", "critic.md")
    instructions = prompts.agent_instructions(catalogue.role_by_name(roles, "critic"), home, role_file, tiers, writable=False)
    prompt = prompts.gate_prompt("critic", "add-login-3f9a2c1", role_file)
    assert role_file == project_file
    assert "Project critic text." in instructions
    assert "no write tool" in instructions
    assert f"Read {project_file}," in prompt
    assert f"{home}/roles/critic.md" not in prompt


def test_agent_instructions_embed_role_file(tmp_path):
    home, main = _checkouts(tmp_path, ["coder"], [])
    roles = catalogue.load_roles(ROLES_CONF)
    tiers = catalogue.load_tiers(TIERS_CONF)
    instructions = prompts.agent_instructions(
        catalogue.role_by_name(roles, "coder"), home, prompts.role_file(main, home, "coder"), tiers, writable=True
    )
    assert "Runtime coder text." in instructions
    assert "mistral-medium-latest" in instructions
    assert "write_file" in instructions
    assert f"Read {home}/constitution.md" in instructions
