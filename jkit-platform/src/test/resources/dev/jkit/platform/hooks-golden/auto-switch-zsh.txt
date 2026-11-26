# switch to a project's versions on cd (shell.auto_switch); not in a $( ) or ( )
# subshell, such as a prompt theme's: what it sets is gone with it, and what it says
# would land in the prompt (bash's prompt hook doesn't run in one either)
__jkit_hook_env() {
  [[ $ZSH_SUBSHELL -eq 0 ]] || return 0
  eval "$(__jkit_run hook-env zsh)"
}
autoload -Uz add-zsh-hook
add-zsh-hook chpwd __jkit_hook_env
__jkit_hook_env
