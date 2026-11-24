# Builds the release APK and signs it with the local release key, which lives OUTSIDE the repo
# (default C:\Users\seeth\PhoneBridge_keys, override with -KeyDir). Output:
# dist_itch\PhoneBridge-android-v<version>.apk
param([string]$Version = '0.1.0', [string]$KeyDir = "$env:USERPROFILE\PhoneBridge_keys")
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root 'dist_itch'
$sdk  = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt   = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name | Select-Object -Last 1 -ExpandProperty FullName
$ks   = Join-Path $KeyDir 'phonebridge-release.jks'
$pf   = Join-Path $KeyDir 'keystore-password.txt'
if (-not (Test-Path $ks) -or -not (Test-Path $pf)) { throw "release key not found in $KeyDir" }
Push-Location $root
try {
  & .\gradlew.bat :android:app:assembleRelease --console=plain -q
  if ($LASTEXITCODE -ne 0) { throw 'gradle assembleRelease failed' }
  New-Item -ItemType Directory -Force $out | Out-Null
  $unsigned = Join-Path $root 'android\app\build\outputs\apk\release\app-release-unsigned.apk'
  $aligned  = Join-Path $env:TEMP 'pb-release-aligned.apk'
  $apk      = Join-Path $out "PhoneBridge-android-v$Version.apk"
  & (Join-Path $bt 'zipalign.exe') -f -p 4 $unsigned $aligned
  cmd /c "`"$bt\apksigner.bat`" sign --ks `"$ks`" --ks-key-alias phonebridge --ks-pass file:`"$pf`" --out `"$apk`" `"$aligned`""
  if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
  Remove-Item $aligned, "$apk.idsig" -ErrorAction SilentlyContinue
  cmd /c "`"$bt\apksigner.bat`" verify `"$apk`""
  if ($LASTEXITCODE -ne 0) { throw 'signature verification failed' }
  Get-Item $apk | Select-Object FullName, Length
} finally { Pop-Location }
