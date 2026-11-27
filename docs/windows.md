# jkit on Windows (preview)

jkit runs on Windows x64, but this part is less tested than macOS and Linux.

## Install

Three ways, all with the same `jkit.exe`:

- **The `.msi`** (from 0.6.0), `jkit-<version>-windows-x64.msi` from the
  [releases](https://github.com/adriandeleon/jkit/releases). It asks who jkit is for:
  - *just for you*: `%LOCALAPPDATA%\Programs\jkit`, on your user `PATH`; needs no
    administrator rights.
  - *all users*: `%ProgramFiles%\jkit`, on the system `PATH`; asks for administrator rights.
    Each user's JDKs and settings still go in their own `%USERPROFILE%\.jkit`.

  Open a new terminal afterwards: programs started before the install have the old `PATH`.
  Unattended, `msiexec /i jkit-<version>-windows-x64.msi /qn` installs it just for you, and
  `ALLUSERS=1` for all users. The `.msi` isn't Authenticode-signed, so SmartScreen may say
  "Windows protected your PC": check the file against `SHA256SUMS` ([below](#verifying-a-download)),
  then choose *More info* → *Run anyway*.
- **winget**: `winget install AdrianDeLeon.jkit` (once winget-pkgs has merged the release).
- **The `.zip`** from the releases. Put `jkit.exe` anywhere on your `PATH`.

To update a jkit from the `.msi`, run a newer `.msi` and make the same choice as before (Windows
Installer upgrades an install only for the same users; the other choice installs a second copy).
`jkit self-update` prints a PowerShell command that does it for you: it downloads the newer
`.msi`, checks it against the release's signed `SHA256SUMS`, and installs it for the same users
without asking. To remove it, uninstall jkit in Settings → Apps: that takes its folder off `PATH`
again and leaves `%USERPROFILE%\.jkit` (see [Uninstall](../README.md#uninstall)).

`jkit.exe` needs the Microsoft Visual C++ runtime. The `.msi` installs the runtime's two DLLs
next to it. winget installs the Visual C++
Redistributable (2015 or newer, x64) with jkit. From 0.5.0 the `.zip` carries the runtime's two
DLLs, `vcruntime140.dll` and `vcruntime140_1.dll`, next to `jkit.exe`: keep them next to it, in a
folder of its own. `jkit self-update` keeps them up to date. With 0.4.0 and earlier, install the
Redistributable first. Once the Redistributable is installed you can delete the two DLLs, and
Windows Update keeps the runtime patched.

Windows on ARM runs the x64 build under emulation.

## The PowerShell hook

`jkit hook --install` doesn't edit startup files on Windows (for Git Bash neither: jkit has no bash
hook there), so add the hook to your PowerShell profile by hand:
`notepad $PROFILE`, then add

```powershell
jkit hook pwsh | Out-String | Invoke-Expression
```

The hook puts the global `bin` folders first on PATH, ahead of the system PATH, where `jkit env
--install` alone can't put them.

Windows PowerShell (5.1, the one Windows comes with) runs no profile until scripts are allowed:
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`. PowerShell 7 allows them already.

Switching on `cd` isn't available in PowerShell yet; `jkit sync` applies a project's versions.

## Programs outside PowerShell

`jkit use … --global` points `%USERPROFILE%\.jkit\global\jdk` at the install. For programs
started outside a hooked PowerShell (cmd.exe, IDEs, Explorer) to follow it too, run
`jkit env --install` once: it sets `JAVA_HOME` and the other `*_HOME` variables in your user
environment to the global links and puts their `bin` folders first on your user `PATH`, so a
later `jkit use … --global` reaches new programs with no further change. Each change is printed;
`jkit env --uninstall` takes out only what it set. A `*_HOME` of your own that `--install`
replaced is kept in `JKIT_SAVED_<TOOL>` and put back by `--uninstall`. A `java` on the system `PATH` still comes
first; `jkit doctor` says if there is one.

## Verifying a download

In PowerShell, compare `(Get-FileHash <file>).Hash` with the file's line in `SHA256SUMS`. See
[SECURITY.md](../SECURITY.md#verifying-a-download) for checking `SHA256SUMS` itself.
