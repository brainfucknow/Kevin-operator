@AGENTS.md

In Claude Code, the Kevin plugin's `/kevin:swarm <feature request>` starts
the pipeline. Its agents are `kevin:<role>`; they carry the model settings
generated from `kevin/tiers.conf`. Keep those settings when delegating.
After the operator answers a specifier question, continue the same
specifier agent rather than starting a new one.
