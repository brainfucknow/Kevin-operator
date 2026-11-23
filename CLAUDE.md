@AGENTS.md

In Claude Code, the Kevin plugin's `/kevin:swarm <feature request>` starts
the pipeline. Before each delegation, run `kevin agent <role> claude`.
Delegate to the agent it names (`kevin:<role>` or the project agent
`kevin-<role>`), and pass its `MODEL` as the model of the call. Pass no other
model.
After the operator answers a specifier question, continue the same
specifier agent rather than starting a new one.
