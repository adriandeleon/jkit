# switch to a project's versions on cd (shell.auto_switch); interactive shells only,
# since fish scripts that source the hook cd too
if status is-interactive
  function __jkit_hook_env --on-variable PWD
    __jkit_run hook-env fish | source
  end
  __jkit_hook_env
end
