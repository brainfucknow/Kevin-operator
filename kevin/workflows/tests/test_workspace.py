"""The worker's workspace directory comes from the environment."""

from kevin_pipeline import activities


def test_workspace_is_kevin_workspace_else_the_legacy_one_else_the_default():
    assert activities.workspace({"KEVIN_WORKSPACE": "/new", "SWARMFORGE_WORKSPACE": "/old"}) == "/new"
    assert activities.workspace({"SWARMFORGE_WORKSPACE": "/old"}) == "/old"
    assert activities.workspace({"KEVIN_WORKSPACE": "", "SWARMFORGE_WORKSPACE": "/old"}) == "/old"
    assert activities.workspace({}) == "/tmp/kevin-pipeline"
