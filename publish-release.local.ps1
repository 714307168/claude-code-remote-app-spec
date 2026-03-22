param(
    [string]$RepoRoot = $PSScriptRoot,
    [string]$BaseUrl = $(if ($env:RELAY_BASE_URL) { $env:RELAY_BASE_URL } else { "https://relay.liuyg.cn" }),
    [string]$Channel = "stable",
    [string]$AdminUser = $env:RELAY_ADMIN_USER,
    [string]$AdminPassword = $env:RELAY_ADMIN_PASSWORD,
    [string]$ServerHost = $env:RELAY_SERVER_HOST,
    [string]$ServerUser = $env:RELAY_SERVER_USER,
    [string]$ServerPassword = $env:RELAY_SERVER_PASSWORD,
    [string]$DesktopNotes,
    [string]$AndroidNotes,
    [int]$MaxUploadSizeMb = 200,
    [switch]$SkipDesktopBuild,
    [switch]$SkipAndroidBuild,
    [switch]$SkipRelayDeploy,
    [switch]$SkipServerChecks,
    [switch]$SkipDesktopUpload,
    [switch]$SkipAndroidUpload,
    [switch]$SkipTests,
    [switch]$Draft
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path $RepoRoot).Path
$BaseUrl = $BaseUrl.TrimEnd("/")

$ArtifactsDir = Join-Path $RepoRoot "artifacts"
$LocalAgentDir = Join-Path $RepoRoot "local-agent"
$AndroidDir = Join-Path $RepoRoot "android-app"
$DeployScriptPath = Join-Path $RepoRoot "deploy-relay-server.local.ps1"
$DesktopPackageJson = Join-Path $LocalAgentDir "package.json"
$AndroidBuildFile = Join-Path $AndroidDir "app\build.gradle.kts"
$DesktopReleaseDir = Join-Path $LocalAgentDir "release"
$AndroidReleaseApk = Join-Path $AndroidDir "app\build\outputs\apk\release\app-release.apk"
$SshOptions = @("-q", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=10")

function Get-RequiredTool {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) {
        throw "Required tool not found: $Name"
    }

    return $command.Source
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter()]
        [string[]]$ArgumentList = @(),

        [string]$DisplayCommand
    )

    $rendered = if ($DisplayCommand) {
        $DisplayCommand
    }
    else {
        "$FilePath $($ArgumentList -join ' ')"
    }

    Write-Host ">> $rendered"
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        throw ("Command failed with exit code {0}: {1}" -f $LASTEXITCODE, $rendered)
    }
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,

        [Parameter()]
        [string[]]$ArgumentList = @(),

        [string]$DisplayCommand
    )

    $rendered = if ($DisplayCommand) {
        $DisplayCommand
    }
    else {
        "$FilePath $($ArgumentList -join ' ')"
    }

    Write-Host ">> $rendered"
    $output = & $FilePath @ArgumentList 2>&1
    $text = ($output | Out-String)
    $text = [regex]::Replace($text, "\x1b\[[0-9;?]*[ -/]*[@-~]", "")
    $text = $text -replace "`r", ""

    if ($LASTEXITCODE -ne 0) {
        throw ("Command failed with exit code {0}: {1}`n{2}" -f $LASTEXITCODE, $rendered, $text.Trim())
    }

    return $text.Trim()
}

function Get-DesktopVersion {
    $package = Get-Content -Path $DesktopPackageJson -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace($package.version)) {
        throw "Desktop version not found in $DesktopPackageJson"
    }

    return $package.version.Trim()
}

function Get-AndroidVersionInfo {
    $content = Get-Content -Path $AndroidBuildFile -Raw

    $versionCodeMatch = [regex]::Match($content, 'versionCode\s*=\s*(\d+)')
    if (-not $versionCodeMatch.Success) {
        throw "versionCode not found in $AndroidBuildFile"
    }

    $versionNameMatch = [regex]::Match($content, 'versionName\s*=\s*"([^"]+)"')
    if (-not $versionNameMatch.Success) {
        throw "versionName not found in $AndroidBuildFile"
    }

    [pscustomobject]@{
        VersionCode = [int]$versionCodeMatch.Groups[1].Value
        VersionName = $versionNameMatch.Groups[1].Value.Trim()
    }
}

function Get-DeployScriptValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Content,

        [Parameter(Mandatory = $true)]
        [string]$VariableName
    )

    $match = [regex]::Match($Content, '\$' + [regex]::Escape($VariableName) + '\s*=\s*"([^"]*)"')
    if ($match.Success) {
        return $match.Groups[1].Value
    }

    return $null
}

function Resolve-ServerConnectionDefaults {
    if (-not (Test-Path $DeployScriptPath)) {
        return
    }

    $content = Get-Content -Path $DeployScriptPath -Raw

    if ([string]::IsNullOrWhiteSpace($ServerHost)) {
        $script:ServerHost = Get-DeployScriptValue -Content $content -VariableName "ServerHost"
    }
    if ([string]::IsNullOrWhiteSpace($ServerUser)) {
        $script:ServerUser = Get-DeployScriptValue -Content $content -VariableName "ServerUser"
    }
    if ([string]::IsNullOrWhiteSpace($ServerPassword)) {
        $script:ServerPassword = Get-DeployScriptValue -Content $content -VariableName "ServerPassword"
    }
}

function Invoke-SshCapture {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command,

        [Parameter(Mandatory = $true)]
        [string]$Summary
    )

    $args = @("-p", $ServerPassword, $script:SshPath) + $SshOptions + @("$ServerUser@$ServerHost", $Command)
    Invoke-NativeCapture -FilePath $script:SshPassPath -ArgumentList $args -DisplayCommand ("ssh {0}@{1} {2}" -f $ServerUser, $ServerHost, $Summary)
}

function Copy-ToArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,

        [Parameter(Mandatory = $true)]
        [string]$TargetPath
    )

    if (-not (Test-Path $SourcePath)) {
        throw "Artifact not found: $SourcePath"
    }

    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $TargetPath) | Out-Null
    Copy-Item -Path $SourcePath -Destination $TargetPath -Force
    return $TargetPath
}

function Copy-ToAsciiTemp {
    param(
        [Parameter(Mandatory = $true)]
        [string]$SourcePath,

        [Parameter(Mandatory = $true)]
        [string]$AsciiName
    )

    $tempPath = Join-Path $env:TEMP $AsciiName
    Copy-Item -Path $SourcePath -Destination $tempPath -Force
    return $tempPath
}

function Ensure-ServerUploadPrerequisites {
    if ([string]::IsNullOrWhiteSpace($ServerHost) -or [string]::IsNullOrWhiteSpace($ServerUser) -or [string]::IsNullOrWhiteSpace($ServerPassword)) {
        throw "Server SSH credentials are missing. Set RELAY_SERVER_HOST / RELAY_SERVER_USER / RELAY_SERVER_PASSWORD or keep deploy-relay-server.local.ps1 available with those defaults."
    }

    $remoteCommand = @"
set -e
CONF=/www/server/panel/vhost/nginx/relay.liuyg.cn.conf
if ! grep -q 'client_max_body_size' "`$CONF"; then
  echo "client_max_body_size line not found in `"`$CONF`"" >&2
  exit 1
fi
sed -i 's/client_max_body_size [^;]*;/client_max_body_size $MaxUploadSizeMb`m;/' "`$CONF"
chown -R www:www /www/wwwroot/relay-server/data/releases
chmod 755 /www/wwwroot/relay-server/data/releases
nginx -t >/dev/null 2>&1
nginx -s reload >/dev/null 2>&1
grep -m1 'client_max_body_size' "`$CONF"
stat -c '%U:%G %a %n' /www/wwwroot/relay-server/data/releases
"@

    $result = Invoke-SshCapture -Command $remoteCommand -Summary "ensure release upload prerequisites"
    Write-Host $result
}

function Invoke-RelayDeploy {
    if (-not (Test-Path $DeployScriptPath)) {
        throw "Relay deploy script not found: $DeployScriptPath"
    }

    $powershellPath = Get-RequiredTool "powershell"
    $args = @("-ExecutionPolicy", "Bypass", "-File", $DeployScriptPath)
    if ($SkipTests) {
        $args += "-SkipTests"
    }

    $summary = "powershell -ExecutionPolicy Bypass -File .\deploy-relay-server.local.ps1"
    if ($SkipTests) {
        $summary += " -SkipTests"
    }

    Invoke-Native -FilePath $powershellPath -ArgumentList $args -DisplayCommand $summary
}

function Build-DesktopPackage {
    $npmPath = Get-RequiredTool "npm"
    $nodeModules = Join-Path $LocalAgentDir "node_modules"
    Push-Location $LocalAgentDir
    try {
        if (-not (Test-Path $nodeModules)) {
            Invoke-Native -FilePath $npmPath -ArgumentList @("install") -DisplayCommand "cd local-agent; npm install"
        }
        Invoke-Native -FilePath $npmPath -ArgumentList @("run", "build") -DisplayCommand "cd local-agent; npm run build"
        Invoke-Native -FilePath $npmPath -ArgumentList @("run", "dist:win") -DisplayCommand "cd local-agent; npm run dist:win"
    }
    finally {
        Pop-Location
    }
}

function Build-AndroidPackage {
    $gradlePath = Join-Path $AndroidDir "gradlew.bat"
    if (-not (Test-Path $gradlePath)) {
        throw "Gradle wrapper not found: $gradlePath"
    }

    Push-Location $AndroidDir
    try {
        Invoke-Native -FilePath $gradlePath -ArgumentList @(":app:assembleRelease") -DisplayCommand "cd android-app; .\gradlew.bat :app:assembleRelease"
    }
    finally {
        Pop-Location
    }
}

function New-AdminSessionCookie {
    if ([string]::IsNullOrWhiteSpace($AdminUser) -or [string]::IsNullOrWhiteSpace($AdminPassword)) {
        throw "Admin credentials are required. Set RELAY_ADMIN_USER and RELAY_ADMIN_PASSWORD, or pass -AdminUser / -AdminPassword."
    }

    $body = @{
        username = $AdminUser
        password = $AdminPassword
    } | ConvertTo-Json

    $null = Invoke-RestMethod -Uri "$BaseUrl/admin/api/login" -Method Post -Body $body -ContentType "application/json" -SessionVariable session
    $cookie = $session.Cookies.GetCookies("$BaseUrl/admin/")["admin_session"].Value
    if ([string]::IsNullOrWhiteSpace($cookie)) {
        throw "admin_session cookie missing after login"
    }

    return $cookie
}

function Upload-ReleasePackage {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Cookie,

        [Parameter(Mandatory = $true)]
        [string]$PackagePath,

        [Parameter(Mandatory = $true)]
        [string]$UploadFileName,

        [Parameter(Mandatory = $true)]
        [string]$Platform,

        [Parameter(Mandatory = $true)]
        [string]$Version,

        [Parameter(Mandatory = $true)]
        [int]$Build,

        [Parameter(Mandatory = $true)]
        [string]$Notes,

        [string]$Arch = ""
    )

    $publishedValue = if ($Draft) { "false" } else { "true" }
    $tempPackage = Copy-ToAsciiTemp -SourcePath $PackagePath -AsciiName $UploadFileName

    try {
        $args = @(
            "-fsS",
            "-X", "POST",
            "-H", "Cookie: admin_session=$Cookie",
            "-F", "platform=$Platform",
            "-F", "channel=$Channel",
            "-F", "arch=$Arch",
            "-F", "version=$Version",
            "-F", "build=$Build",
            "-F", "published=$publishedValue",
            "-F", "notes=$Notes",
            "-F", "package=@$tempPackage;filename=$UploadFileName",
            "$BaseUrl/admin/api/releases"
        )

        $responseText = Invoke-NativeCapture -FilePath $script:CurlPath -ArgumentList $args -DisplayCommand ("curl.exe POST {0}/admin/api/releases ({1} {2})" -f $BaseUrl, $Platform, $Version)
        return ($responseText | ConvertFrom-Json)
    }
    finally {
        Remove-Item -Path $tempPackage -Force -ErrorAction SilentlyContinue
    }
}

function Verify-HealthEndpoint {
    $health = Invoke-NativeCapture -FilePath $script:CurlPath -ArgumentList @("-fsS", "$BaseUrl/health") -DisplayCommand ("curl.exe {0}/health" -f $BaseUrl)
    if ($health -ne "ok") {
        throw "Health check failed: $health"
    }
}

function Verify-UpdateEndpoint {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Platform,

        [Parameter(Mandatory = $true)]
        [string]$ExpectedVersion,

        [int]$ExpectedBuild = 0,

        [string]$Arch = "",

        [int]$ExpectedReleaseId = 0
    )

    $queryUrl = "{0}/api/update/check?platform={1}&channel={2}&arch={3}&version=0.0.0&build=0" -f $BaseUrl, $Platform, $Channel, $Arch
    $response = Invoke-RestMethod -Uri $queryUrl -Method Get

    if (-not $response.available) {
        throw "Update check returned available=false for $Platform"
    }
    if ($response.latestVersion -ne $ExpectedVersion) {
        throw "Unexpected version for $Platform. expected=$ExpectedVersion actual=$($response.latestVersion)"
    }
    if ($Platform -eq "android" -and [int]$response.build -ne $ExpectedBuild) {
        throw "Unexpected Android build. expected=$ExpectedBuild actual=$($response.build)"
    }
    if ($ExpectedReleaseId -gt 0 -and [int]$response.releaseId -ne $ExpectedReleaseId) {
        throw "Unexpected releaseId for $Platform. expected=$ExpectedReleaseId actual=$($response.releaseId)"
    }

    $downloadUrl = "$BaseUrl/api/update/download/$($response.releaseId)"
    $null = Invoke-NativeCapture -FilePath $script:CurlPath -ArgumentList @("-fsSI", $downloadUrl) -DisplayCommand ("curl.exe -I {0}" -f $downloadUrl)
}

if (-not (Test-Path $DesktopPackageJson)) {
    throw "Desktop package.json not found: $DesktopPackageJson"
}
if (-not (Test-Path $AndroidBuildFile)) {
    throw "Android build file not found: $AndroidBuildFile"
}

New-Item -ItemType Directory -Force -Path $ArtifactsDir | Out-Null

$desktopVersion = Get-DesktopVersion
$androidVersionInfo = Get-AndroidVersionInfo
$androidVersion = $androidVersionInfo.VersionName
$androidBuild = $androidVersionInfo.VersionCode

$desktopNotes = if ([string]::IsNullOrWhiteSpace($DesktopNotes)) {
    "Release $desktopVersion."
}
else {
    $DesktopNotes
}

$androidNotes = if ([string]::IsNullOrWhiteSpace($AndroidNotes)) {
    "Release $androidVersion (build $androidBuild)."
}
else {
    $AndroidNotes
}

$desktopReleaseSource = Join-Path $DesktopReleaseDir ("Claude Code Agent-{0}-x64-setup.exe" -f $desktopVersion)
$desktopArtifact = Join-Path $ArtifactsDir ("ClaudeCodeAgent-{0}-x64-setup.exe" -f $desktopVersion)
$androidArtifact = Join-Path $ArtifactsDir ("ClaudeCodeRemote-{0}-release.apk" -f $androidVersion)

if (-not $SkipDesktopBuild) {
    Build-DesktopPackage
}
if (-not $SkipAndroidBuild) {
    Build-AndroidPackage
}

if (Test-Path $desktopReleaseSource) {
    Copy-ToArtifact -SourcePath $desktopReleaseSource -TargetPath $desktopArtifact | Out-Null
}
elseif (-not (Test-Path $desktopArtifact)) {
    throw "Desktop artifact not found. Expected one of: $desktopReleaseSource or $desktopArtifact"
}

if (Test-Path $AndroidReleaseApk) {
    Copy-ToArtifact -SourcePath $AndroidReleaseApk -TargetPath $androidArtifact | Out-Null
}
elseif (-not (Test-Path $androidArtifact)) {
    throw "Android artifact not found. Expected one of: $AndroidReleaseApk or $androidArtifact"
}

$desktopHash = (Get-FileHash -Path $desktopArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
$androidHash = (Get-FileHash -Path $androidArtifact -Algorithm SHA256).Hash.ToLowerInvariant()

Resolve-ServerConnectionDefaults

$script:CurlPath = Get-RequiredTool "curl.exe"
if (-not $SkipServerChecks) {
    $script:SshPath = Get-RequiredTool "ssh"
    $script:SshPassPath = Get-RequiredTool "sshpass"
}

if (-not $SkipRelayDeploy) {
    Invoke-RelayDeploy
}

if (-not $SkipServerChecks) {
    Ensure-ServerUploadPrerequisites
}

$desktopRelease = $null
$androidRelease = $null

if (-not $SkipDesktopUpload -or -not $SkipAndroidUpload) {
    $adminCookie = New-AdminSessionCookie

    if (-not $SkipDesktopUpload) {
        $desktopRelease = Upload-ReleasePackage `
            -Cookie $adminCookie `
            -PackagePath $desktopArtifact `
            -UploadFileName ("ClaudeCodeAgent-{0}-x64-setup.exe" -f $desktopVersion) `
            -Platform "desktop-win" `
            -Arch "x64" `
            -Version $desktopVersion `
            -Build 0 `
            -Notes $desktopNotes
    }

    if (-not $SkipAndroidUpload) {
        $androidRelease = Upload-ReleasePackage `
            -Cookie $adminCookie `
            -PackagePath $androidArtifact `
            -UploadFileName ("ClaudeCodeRemote-{0}-release.apk" -f $androidVersion) `
            -Platform "android" `
            -Arch "" `
            -Version $androidVersion `
            -Build $androidBuild `
            -Notes $androidNotes
    }
}

Verify-HealthEndpoint

if (-not $Draft) {
    if ($desktopRelease) {
        Verify-UpdateEndpoint -Platform "desktop-win" -Arch "x64" -ExpectedVersion $desktopVersion -ExpectedReleaseId $desktopRelease.id
    }
    if ($androidRelease) {
        Verify-UpdateEndpoint -Platform "android" -Arch "" -ExpectedVersion $androidVersion -ExpectedBuild $androidBuild -ExpectedReleaseId $androidRelease.id
    }
}

Write-Host ""
Write-Host "Release pipeline completed."
Write-Host "Desktop version: $desktopVersion"
Write-Host "Desktop artifact: $desktopArtifact"
Write-Host "Desktop SHA256: $desktopHash"
if ($desktopRelease) {
    Write-Host "Desktop release id: $($desktopRelease.id)"
}
Write-Host "Android version: $androidVersion (build $androidBuild)"
Write-Host "Android artifact: $androidArtifact"
Write-Host "Android SHA256: $androidHash"
if ($androidRelease) {
    Write-Host "Android release id: $($androidRelease.id)"
}
Write-Host "Publish mode: $(if ($Draft) { 'draft' } else { 'published' })"
Write-Host "Base URL: $BaseUrl"
