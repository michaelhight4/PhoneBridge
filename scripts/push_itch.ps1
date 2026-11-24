# Uploads the PhoneBridge builds to itch.io with butler.
# One-time setup: create the itch.io project page, then run `butler login` (opens your browser).
# Usage: .\push_itch.ps1 -Target yourname/phonebridge [-Version 0.1.0] [-DryRun]
param(
  [string]$Target  = '<itch-username>/phonebridge',
  [string]$Version = '0.1.0',
  [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
if ($Target -like '<*') { throw 'Set -Target to your itch.io username/project, e.g. -Target garyhight/phonebridge' }
$butler = Get-Command butler -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $butler) { $butler = 'C:\Users\seeth\tools\butler\butler.exe' }
if (-not (Test-Path $butler)) { throw "butler not found; install it from https://broth.itch.zone/butler/windows-amd64/LATEST/archive/default" }
$here    = if (Test-Path (Join-Path $PSScriptRoot "PhoneBridge-android-v$Version.apk")) { $PSScriptRoot } else { Join-Path (Split-Path -Parent $PSScriptRoot) 'dist_itch' }
$apk     = Join-Path $here "PhoneBridge-android-v$Version.apk"
$winZip  = Join-Path $here "PhoneBridge-windows-v$Version.zip"
foreach ($f in $apk, $winZip) { if (-not (Test-Path $f)) { throw "missing build: $f" } }
$extra = @('--userversion', $Version)
if ($DryRun) { $extra += '--dry-run' }
& $butler push $apk    "$($Target):android" @extra
if ($LASTEXITCODE -ne 0) { throw 'butler push android failed' }
& $butler push $winZip "$($Target):windows" @extra
if ($LASTEXITCODE -ne 0) { throw 'butler push windows failed' }
& $butler status $Target
