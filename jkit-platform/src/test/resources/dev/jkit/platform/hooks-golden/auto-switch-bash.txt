# switch to a project's versions on cd (shell.auto_switch)
__jkit_hook_env() {
  local __jkit_rc=$?
  if [ "$PWD" != "${__jkit_pwd-}" ]; then
    __jkit_pwd=$PWD
    eval "$(__jkit_run hook-env bash)"
  fi
  return $__jkit_rc
}
case ";${PROMPT_COMMAND-};" in
  *";__jkit_hook_env;"*) ;;
  *) PROMPT_COMMAND="__jkit_hook_env${PROMPT_COMMAND:+;$PROMPT_COMMAND}" ;;
esac
__jkit_hook_env
