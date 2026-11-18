<#
.SYNOPSIS
    ⑤ 로컬 클러스터를 띄운다: JAR 빌드 → 이미지 빌드 → H2·Redis → 앱 1개로 스키마 생성 → 앱 Min개 → 부하 분산기.
.DESCRIPTION
    앱 여러 개가 빈 DB에 동시에 테이블을 만들지 않도록, 처음에는 앱 1개를 띄워 준비될 때까지 기다린 뒤 Min개로 늘린다.
    부하 분산기의 분산 대상(deploy/nginx/upstream.d/app-servers.conf)에는 요청에 답하는 인스턴스만 넣는다(lb-upstream.ps1).
    deploy/.env가 있어야 한다(deploy/.env.example 참고).
#>
param(
    [int]$Min = 2,
    [string]$ComposeFile = (Join-Path $PSScriptRoot '..\..\deploy\compose.yaml'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\..\deploy\.env'),
    [string]$UpstreamFile = (Join-Path $PSScriptRoot '..\..\deploy\nginx\upstream.d\app-servers.conf'),
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lb-upstream.ps1')
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
if (-not (Test-Path $EnvFile)) { throw "deploy/.env가 없습니다. deploy/.env.example을 복사해 값을 넣으세요." }

# docker·gradle은 진행 상황을 stderr로 쓴다. Windows PowerShell 5.1에서 이를 오류로 보지 않도록 종료 코드로만 판단한다.
function Invoke-Native([string]$command, [string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $command @arguments 2>&1 | ForEach-Object { Write-Host "$_" }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) { throw "$command $($arguments -join ' ') 실패(exit $exitCode)" }
}

$compose = @('compose', '-f', $ComposeFile, '--env-file', $EnvFile)

if (-not $SkipBuild) {
    Push-Location $root
    try {
        Invoke-Native '.\gradlew.bat' @('bootJar')
        Invoke-Native 'docker' @('build', '-t', 'chzzk-app:local', '.')
    } finally {
        Pop-Location
    }
}

function Add-ReadyAppsToLb {
    $ready = @()
    foreach ($instance in @(Get-AppInstances $compose)) {
        if (-not (Wait-AppReady $compose $instance.Ip)) { throw "$($instance.Name)이 준비되지 않았습니다. docker compose logs app을 확인하세요." }
        $ready += $instance
    }
    Set-LbUpstream $compose $UpstreamFile $ready
    Write-Host "부하 분산 대상: $(($ready | ForEach-Object { $_.Name }) -join ', ')"
}

# 부하 분산기는 대상 목록 파일이 있어야 뜬다. 지난 실행의 주소가 남아 있지 않도록 빈 자리 표시로 시작한다.
Set-LbUpstream $compose $UpstreamFile @() -NoReload
Invoke-Native 'docker' ($compose + @('up', '-d', 'h2', 'redis'))
Invoke-Native 'docker' ($compose + @('up', '-d', '--no-build', '--scale', 'app=1', 'app', 'lb'))
Add-ReadyAppsToLb

$port = (Get-Content $EnvFile | Where-Object { $_ -match '^CHZZK_HTTP_PORT=' } | ForEach-Object { ($_ -split '=', 2)[1] }) | Select-Object -First 1
if (-not $port) { $port = '18090' }
$deadline = (Get-Date).AddMinutes(3)
do {
    Start-Sleep -Seconds 3
    try { $status = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$port/broadcasts" -TimeoutSec 5).StatusCode } catch { $status = 0 }
} while ($status -ne 200 -and (Get-Date) -lt $deadline)
if ($status -ne 200) { throw "앱이 준비되지 않았습니다. docker compose logs app을 확인하세요." }

Invoke-Native 'docker' ($compose + @('up', '-d', '--no-build', '--no-recreate', '--scale', "app=$Min", 'app'))
Add-ReadyAppsToLb
Invoke-Native 'docker' ($compose + @('ps'))
Write-Host "준비됨: http://127.0.0.1:$port  (오토스케일러: .\scripts\autoscale\autoscaler.ps1)"
