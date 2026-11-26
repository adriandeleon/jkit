# jkit on Windows (preview)

jkit runs on Windows x64, but this part is less tested than macOS and Linux.

## Install

`winget install AdrianDeLeon.jkit` (once winget-pkgs has merged the release; until then, the
`.zip` from the [releases](https://github.com/adriandeleon/jkit/releases)). Put `jkit.exe`
anywhere on your `PATH`.

`jkit.exe` needs the Microsoft Visual C++ runtime. winget installs the Visual C++
Redistributable (2015 or newer, x64) with jkit. From 0.5.0 the `.zip` carries the runtime's two
DLLs, `vcruntime140.dll` and `vcruntime140_1.dll`, next to `jkit.exe`: keep them next to it, in a
folder of its own. `jkit self-update` keeps them up to date. With 0.4.0 and earlier, install the
Redistributable first. Once the Redistributable is installed you can delete the two DLLs, and
Windows Update keeps the runtime patched.

Windows on ARM runs the x64 build under emulation.

## The PowerShell hook

`jkit hook --install` doesn't do PowerShell yet, so add the hook to your profile by hand:
`notepad $PROFILE`, then add

```powershell
jkit hook pwsh | Out-String | Invoke-Expression
```

Windows PowerShell (5.1, the one Windows comes with) runs no profile until scripts are allowed:
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`. PowerShell 7 allows them already.

Switching on `cd` isn't available in PowerShell yet; `jkit sync` applies a project's versions.

## Programs outside PowerShell

`jkit use … --global` points `%USERPROFILE%\.jkit\global\jdk` at the install. For programs
started outside a hooked PowerShell (cmd.exe, IDEs, Explorer) to follow it too, run
`jkit env --install` once: it sets `JAVA_HOME` and the other `*_HOME` variables in your user
environment to the global links and puts their `bin` folders first on your user `PATH`, so a
later `jkit use … --global` reaches new programs with no further change. Each change is printed;
`jkit env --uninstall` takes out only what it set. A `java` on the system `PATH` still comes
first; `jkit doctor` says if there is one.

## Verifying a download

In PowerShell, compare `(Get-FileHash <file>).Hash` with the file's line in `SHA256SUMS`. See
[SECURITY.md](../SECURITY.md#verifying-a-download) for checking `SHA256SUMS` itself.
