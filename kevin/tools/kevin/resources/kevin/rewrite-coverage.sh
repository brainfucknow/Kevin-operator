plan="$({{bb}} --config /dev/null -cp {{src}} -m kevin.coverage-rewrite {{config}} "$@")" || exit 1
eval "$plan"
