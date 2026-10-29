# Conversations+ updater: newest Conversations release + our changes -> build -> phone (adb) -> GitHub release
# (which the Obtainium app on the phone picks up as an update).
#   pwsh -File plus-tools\update.ps1            update to the newest upstream release if there is one
#   pwsh -File plus-tools\update.ps1 -Force     rebuild/reinstall even without a new upstream release
#   -NoInstall   don't install over adb      -NoPublish   don't push / create a GitHub release
param([switch]$Force, [switch]$NoInstall, [switch]$NoPublish)
$ErrorActionPreference = 'Continue'
function Say($t) { Write-Host $t }
function Step($t) { Write-Host ""; Write-Host "== $t" -ForegroundColor Cyan }
function Fail($t) { Write-Host ""; Write-Host "STOPPED: $t" -ForegroundColor Red; exit 1 }

$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }

Step "Checking for a new Conversations release"
git fetch origin --tags --quiet
if ($LASTEXITCODE -ne 0) { Fail "couldn't reach codeberg.org" }
$latest = git tag --list '2.*' | Where-Object { $_ -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_ } | Select-Object -Last 1
$base = (Get-Content (Join-Path $PSScriptRoot 'base-version.txt') -Raw).Trim()
Say "  upstream: $latest   ours is based on: $base"
$changed = $false
if ([version]$latest -gt [version]$base) {
    Step "Merging Conversations $latest into Conversations+"
    if (git status --porcelain --untracked-files=no) { Fail "uncommitted changes in $repo; commit or stash them first" }
    git merge --no-edit $latest
    if ($LASTEXITCODE -ne 0) {
        git merge --abort
        Fail "Conversations $latest conflicts with our changes. Nothing was changed; ask Claude to merge it by hand."
    }
    Set-Content (Join-Path $PSScriptRoot 'base-version.txt') $latest
    Set-Content (Join-Path $PSScriptRoot 'plus-build.txt') '0' -NoNewline
    git add (Join-Path $PSScriptRoot 'base-version.txt') (Join-Path $PSScriptRoot 'plus-build.txt')
    git commit --quiet -m "Conversations+ on Conversations $latest"
    $changed = $true
} else {
    Say "  already up to date"
}
if (-not $changed -and -not $Force) { Say ""; Say "Nothing to do (use -Force to rebuild anyway)."; exit 0 }

# every build gets the next number: <upstream>.<N> (the app's version and the GitHub tag)
$buildFile = Join-Path $PSScriptRoot 'plus-build.txt'
$build = [int](Get-Content $buildFile -Raw).Trim() + 1
Set-Content $buildFile "$build" -NoNewline
$base = (Get-Content (Join-Path $PSScriptRoot 'base-version.txt') -Raw).Trim()
$fullVersion = "$base.$build"
git add $buildFile
git commit --quiet -m "Conversations+ $fullVersion"

Step "Building $fullVersion (about 5-10 minutes)"
& .\gradlew.bat assembleConversationsFreeRelease --console=plain -q
if ($LASTEXITCODE -ne 0) { Fail "the build failed; run it again with output: .\gradlew.bat assembleConversationsFreeRelease" }
$apkDir = Join-Path $repo 'build\outputs\apk\conversationsFree\release'
$apks = Get-ChildItem $apkDir -Filter *.apk
$arm64 = $apks | Where-Object Name -match 'arm64-v8a' | Select-Object -First 1
$universal = $apks | Where-Object Name -match 'universal' | Select-Object -First 1
Say "  $($arm64.Name)"

if (-not $NoInstall) {
    Step "Installing on the phone"
    $device = & $adb devices | Select-String -Pattern "\tdevice$"
    if ($device) {
        & $adb install -r $arm64.FullName
    } else {
        Say "  no phone connected over adb: skipped (Obtainium will pick up the GitHub release)"
    }
}

if (-not $NoPublish) {
    Step "Publishing a GitHub release (for Obtainium)"
    $tag = "v$fullVersion"
    git push --quiet github plus
    git push --quiet github HEAD:refs/heads/main 2>$null
    gh release create $tag --target plus $arm64.FullName $universal.FullName --repo devfbpup/conversations-plus `
        --title "Conversations+ $fullVersion" --notes "Conversations $base + Conversations+ features (screen sharing in calls). Install the arm64-v8a APK on most phones; universal works everywhere. Built $(Get-Date -Format u)."
    if ($LASTEXITCODE -ne 0) { Say "  GitHub release failed (is gh logged in?)" }
}
Write-Host ""
Write-Host "DONE" -ForegroundColor Green
