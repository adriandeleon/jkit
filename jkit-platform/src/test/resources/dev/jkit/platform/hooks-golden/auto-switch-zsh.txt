# switch to a project's versions on cd (shell.auto_switch). Not inside a $( ) or ( ) subshell,
# such as a prompt theme's: what it sets would be lost with the subshell, and what it prints
# would end up in the prompt. (bash's prompt hook doesn't run in subshells either.)
__jkit_hook_env() {
  [[ $ZSH_SUBSHELL -eq 0 ]] || return 0
  eval "$(__jkit_run hook-env zsh)"
}
autoload -Uz add-zsh-hook
add-zsh-hook chpwd __jkit_hook_env
__jkit_hook_env
