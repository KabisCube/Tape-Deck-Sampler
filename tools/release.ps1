# Publishes a new release: checks the repo, runs the tests, bumps versionCode and versionName,
# writes the F-Droid changelog, then commits, tags vX.Y.Z and pushes. GitHub Actions builds and uploads the APK.
param([string]$Version)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Stop-Release([string]$reason) {
	Write-Host $reason -ForegroundColor Red
	exit 1
}

function Invoke-Git {
	$ErrorActionPreference = 'Continue'
	$output = & git @args 2>&1 | ForEach-Object { "$_" }
	if ($LASTEXITCODE -ne 0) { Stop-Release "git $($args -join ' ') failed:`n$($output -join "`n")" }
	return $output
}

$gradleFile = Join-Path $root 'app\build.gradle'
$gradle = [IO.File]::ReadAllText($gradleFile)
$codePattern = '(?m)^(\s*versionCode\s+)(\d+)'
$namePattern = "(?m)^(\s*versionName\s+)'([^']*)'"
$currentCode = [int]([regex]::Match($gradle, $codePattern).Groups[2].Value)
$latestTag = (& git tag --list 'v*' --sort=-v:refname | Select-Object -First 1)
$latestVersion = if ($latestTag) { [version]$latestTag.TrimStart('v') } else { [version]'0.0.0' }
$suggested = "$($latestVersion.Major).$($latestVersion.Minor).$($latestVersion.Build + 1)"

Write-Host "Latest release: $latestTag"
if (-not $Version) {
	$Version = Read-Host "New version (Enter for $suggested)"
	if (-not $Version) { $Version = $suggested }
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { Stop-Release "Version must look like 1.2.3, got '$Version'." }
if ([version]$Version -le $latestVersion) { Stop-Release "Version $Version must be newer than $latestVersion." }
$tag = "v$Version"

if (Invoke-Git status --porcelain) { Stop-Release 'You have uncommitted changes. Commit or stash them first.' }
$branch = (Invoke-Git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') { Stop-Release "Releases are made from main, but you are on '$branch'." }
Invoke-Git fetch origin main --tags | Out-Null
if ((Invoke-Git rev-list --count 'HEAD..origin/main').Trim() -ne '0') { Stop-Release 'GitHub has commits you do not have. Pull first.' }
if (Invoke-Git tag --list $tag) { Stop-Release "Tag $tag already exists." }

Write-Host 'Running tests...'
& (Join-Path $PSScriptRoot 'build.ps1') -TestOnly
if ($LASTEXITCODE -ne 0) { Stop-Release 'Tests failed. Nothing was released.' }

$newCode = $currentCode + 1
$changelogDir = Join-Path $root 'fastlane\metadata\android\en-US\changelogs'
$changelog = Join-Path $changelogDir "$newCode.txt"
New-Item -ItemType Directory -Force $changelogDir | Out-Null
if (-not (Test-Path -LiteralPath $changelog)) { [IO.File]::WriteAllText($changelog, '') }
Write-Host "Write what changed in $Version, then save and close Notepad."
Start-Process notepad.exe -ArgumentList "`"$changelog`"" -Wait
$notes = [IO.File]::ReadAllText($changelog).Trim()
$maxChangelogChars = 500
if (-not $notes) { Remove-Item -LiteralPath $changelog; Stop-Release 'The changelog is empty. Nothing was released.' }
if ($notes.Length -gt $maxChangelogChars) { Stop-Release "The changelog is $($notes.Length) characters. F-Droid allows $maxChangelogChars. Shorten it and run again." }

$gradle = [regex]::Replace($gradle, $codePattern, "`${1}$newCode")
$gradle = [regex]::Replace($gradle, $namePattern, "`${1}'$Version'")
[IO.File]::WriteAllText($gradleFile, $gradle)

Write-Host "Releasing $tag (versionCode $newCode)..."
Invoke-Git add -- $gradleFile $changelog | Out-Null
Invoke-Git commit -m "Release $Version" | Out-Null
Invoke-Git tag -a $tag -m "Tape Deck Sampler $Version" | Out-Null
Invoke-Git push --atomic origin main $tag | Out-Null
Write-Host "Done. GitHub is now building $tag; the APK appears under Releases in a few minutes." -ForegroundColor Green
