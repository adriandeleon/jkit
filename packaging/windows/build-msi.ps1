# Builds the jkit .msi: build-msi.ps1 -Version 1.2.3 -Source DIR -Output jkit-1.2.3-windows-x64.msi
# DIR holds what the .zip holds: jkit.exe, vcruntime140.dll, vcruntime140_1.dll, LICENSE and
# THIRD-PARTY-NOTICES. Needs WiX Toolset 5.0.2 (`dotnet tool install --global wix --version 5.0.2`)
# and its UI extension (`wix extension add --global WixToolset.UI.wixext/5.0.2`); -Wix and
# -UiExtension name others (the release workflow's, checked against their SHA-256). Checks the
# result with Windows Installer's own validation (ICEs).
param(
    [Parameter(Mandatory)] [string] $Version,
    [Parameter(Mandatory)] [string] $Source,
    [Parameter(Mandatory)] [string] $Output,
    [string] $Wix = 'wix',
    [string] $UiExtension = 'WixToolset.UI.wixext/5.0.2'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# 1.2.3 or 1.2.3-rc.1, as the release workflow and winget-manifests.sh accept
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?$') {
    throw "not a release version: '$Version' (expected 1.2.3 or 1.2.3-rc.1)"
}
# A ProductVersion is major.minor.build, at most 255.255.65535, and has no pre-release part:
# 1.2.3-rc.1 installs as 1.2.3, and the release then replaces it (same-version upgrades are on)
if ([int]$Matches[1] -gt 255 -or [int]$Matches[2] -gt 255 -or [int]$Matches[3] -gt 65535) {
    throw "$Version doesn't fit a Windows Installer ProductVersion (at most 255.255.65535)"
}
$productVersion = "$([int]$Matches[1]).$([int]$Matches[2]).$([int]$Matches[3])"

$here = $PSScriptRoot
$work = Join-Path ([IO.Path]::GetTempPath()) "jkit-msi-$([guid]::NewGuid())"
New-Item -ItemType Directory $work | Out-Null
try {
    foreach ($name in 'jkit.exe', 'vcruntime140.dll', 'vcruntime140_1.dll', 'LICENSE', 'THIRD-PARTY-NOTICES') {
        $file = Join-Path $Source $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "no $name in $Source" }
        Copy-Item -LiteralPath $file $work
    }
    # the file jkit self-update recognizes an .msi install by (SelfUpdate.MSI_MARKER)
    $marker = @(
        "This jkit was installed by jkit-$Version-windows-x64.msi."
        ''
        'To update it, run the .msi of a newer release (https://github.com/adriandeleon/jkit/releases),'
        "with the same choice of `"just for you`" or `"all users`": jkit self-update prints the command."
        'To remove it, uninstall jkit in Settings > Apps. Your JDKs and settings in %USERPROFILE%\.jkit stay.'
    )
    [IO.File]::WriteAllLines((Join-Path $work 'INSTALLER.txt'), $marker)

    # The setup dialogs' images, in jkit's colors: the banner across the top of most dialogs
    # (493 x 58, the title is drawn over its left part) and the side image of the first and last
    # (493 x 312, the text is drawn over all but its left 164 pixels). Drawn here from the icon in
    # branding/, so the repository holds no bitmaps to keep in step with it.
    Add-Type -AssemblyName System.Drawing
    $icon = [System.Drawing.Image]::FromFile((Resolve-Path (Join-Path $here '..\..\branding\jkit-icon-256.png')).Path)
    $ink = [System.Drawing.ColorTranslator]::FromHtml('#1B1E2A')
    function Draw($file, $width, $height, $paint) {
        $bmp = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $g.Clear([System.Drawing.Color]::White)
        & $paint $g
        $g.Dispose()
        $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Bmp)
        $bmp.Dispose()
    }
    $banner = Join-Path $work 'banner.bmp'
    $dialog = Join-Path $work 'dialog.bmp'
    Draw $banner 493 58 { param($g) $g.DrawImage($icon, 493 - 46 - 8, 6, 46, 46) }
    Draw $dialog 493 312 {
        param($g)
        $g.FillRectangle((New-Object System.Drawing.SolidBrush($ink)), 0, 0, 164, 312)
        $g.DrawImage($icon, 22, 56, 120, 120)
    }
    $icon.Dispose()

    # relative to PowerShell's folder, not the process's
    $out = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Output)
    & $Wix build (Join-Path $here 'jkit.wxs') -arch x64 -ext $UiExtension `
        -d "Version=$Version" -d "ProductVersion=$productVersion" -d "Source=$work" `
        -d "Icon=$(Join-Path $here 'jkit.ico')" -d "Banner=$banner" -d "Dialog=$dialog" -o $out
    if ($LASTEXITCODE -ne 0) { throw "wix build failed ($LASTEXITCODE)" }
    # ICE61 warns that same-version upgrades are on, which they are on purpose
    & $Wix msi validate -sice ICE61 $out
    if ($LASTEXITCODE -ne 0) { throw "wix msi validate failed ($LASTEXITCODE)" }
    $pdb = [IO.Path]::ChangeExtension($out, '.wixpdb')
    if (Test-Path -LiteralPath $pdb) { Remove-Item -LiteralPath $pdb }
    "built $out (jkit $Version, ProductVersion $productVersion)"
} finally {
    Remove-Item -Recurse -Force -LiteralPath $work -ErrorAction SilentlyContinue
}
