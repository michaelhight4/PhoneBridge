# Builds the self-contained Windows desktop app (no Java needed on the user's PC) with jpackage
# and zips it for itch.io: dist_itch\PhoneBridge-windows-v<version>.zip
param([string]$Version = '0.1.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root 'dist_itch'
$work = Join-Path $root 'desktop\build\jpackage'
Push-Location $root
try {
  & .\gradlew.bat :desktop:installDist --console=plain -q
  if ($LASTEXITCODE -ne 0) { throw 'gradle installDist failed' }
  $lib = Join-Path $root 'desktop\build\install\phonebridge-desktop\lib'
  if (Test-Path $work) { Remove-Item $work -Recurse -Force }
  New-Item -ItemType Directory -Force $work, $out | Out-Null
  # Modules from `jdeps --print-module-deps`, plus crypto/unsafe/logging helpers used at runtime.
  $mods = 'java.base,java.desktop,java.naming,java.net.http,java.sql,java.logging,java.management,jdk.crypto.ec,jdk.unsupported'
  & jpackage --type app-image --name PhoneBridge --app-version $Version `
    --vendor 'Gary Hight' --description 'Let your own AI bot see and control your PC' `
    --input $lib --main-jar desktop.jar --main-class dev.phonebridge.desktop.MainKt `
    --add-modules $mods --jlink-options '--strip-debug --no-man-pages --no-header-files --compress=zip-6' `
    --dest $work
  if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
  Copy-Item (Join-Path $root 'LICENSE') (Join-Path $work 'PhoneBridge\LICENSE.txt')
  @"
PhoneBridge for Windows v$Version

Run PhoneBridge.exe. It starts in the system tray (bottom-right, maybe under the ^ arrow).
Open the tray menu > Pair a bot, then give your bot the QR code, the short code or the link.
No Java install is needed; everything is bundled. Data lives in %APPDATA%\PhoneBridge.
Uninstall: quit from the tray menu and delete this folder (and %APPDATA%\PhoneBridge if you like).
License: MIT (LICENSE.txt). Source: https://github.com/michaelhight4/PhoneBridge
"@ | Set-Content (Join-Path $work 'PhoneBridge\README.txt')
  $zip = Join-Path $out "PhoneBridge-windows-v$Version.zip"
  if (Test-Path $zip) { Remove-Item $zip }
  # tar.exe writes forward-slash zip entries (Compress-Archive on PowerShell 5 writes backslashes).
  & tar.exe -a -c -f $zip -C $work PhoneBridge
  if ($LASTEXITCODE -ne 0) { throw 'zip failed' }
  Get-Item $zip | Select-Object FullName, Length
} finally { Pop-Location }
