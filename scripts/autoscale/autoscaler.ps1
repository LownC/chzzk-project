<#
.SYNOPSIS
    ⑤ 로컬 오토스케일러. 앱 컨테이너의 CPU 사용률을 보고 최소~최대 인스턴스 수 안에서 자동으로 스케일 아웃·인 한다.

.DESCRIPTION
    IntervalSeconds마다 deploy/compose.yaml의 app 컨테이너들을 docker stats로 재고,
    CPU 사용률(컨테이너 CPU 제한 대비 평균 %)이
      - ScaleOutPercent 이상이 ScaleOutAfter번 연속이면 1개 늘리고(최대 Max),
      - ScaleInPercent 미만이 ScaleInAfter번 연속이면 1개 줄인다(최소 Min).
    바꾼 뒤 CooldownSeconds 동안은 다시 바꾸지 않는다. 판단 근거와 인스턴스 수를 LogFile에 한 줄씩 남긴다.
    스케일 인은 docker compose가 SIGTERM을 보내고, 앱은 처리 중인 요청을 마친 뒤 내려간다(server.shutdown=graceful).

.EXAMPLE
    .\scripts\autoscale\autoscaler.ps1
    .\scripts\autoscale\autoscaler.ps1 -Min 2 -Max 5 -ScaleOutPercent 60 -ScaleInPercent 20
#>
param(
    [int]$Min = 2,
    [int]$Max = 5,
    [double]$ScaleOutPercent = 60,
    [double]$ScaleInPercent = 20,
    [int]$ScaleOutAfter = 2,
    [int]$ScaleInAfter = 3,
    [int]$IntervalSeconds = 10,
    [int]$CooldownSeconds = 30,
    # compose.yaml의 app CPU 제한(cpus)과 같게 둔다. docker stats의 CPU %를 이 값으로 나눠 제한 대비 사용률로 본다.
    [double]$CpuLimit = 0.5,
    [string]$ComposeFile = (Join-Path $PSScriptRoot '..\..\deploy\compose.yaml'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\..\deploy\.env'),
    [string]$LogFile = (Join-Path $PSScriptRoot 'autoscaler.log'),
    # 0이면 멈출 때(Ctrl+C)까지 계속 돈다.
    [int]$MaxIterations = 0
)
$ErrorActionPreference = 'Stop'

# docker는 진행 상황을 stderr로 쓴다. Windows PowerShell 5.1에서 이를 오류로 보지 않도록 종료 코드로만 판단하고,
# 표준 출력(컨테이너 ID, CPU %)만 돌려준다.
function Invoke-Docker([string[]]$arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @arguments 2>$null
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) { throw "docker $($arguments -join ' ') 실패(exit $exitCode)" }
    return $output
}

function Invoke-Compose([string[]]$arguments) {
    $base = @('compose', '-f', $ComposeFile)
    if (Test-Path $EnvFile) { $base += @('--env-file', $EnvFile) }
    Invoke-Docker ($base + $arguments)
}

function Get-AppContainerIds {
    @(Invoke-Compose @('ps', '-q', 'app') | Where-Object { $_ })
}

function Get-CpuUtilization([string[]]$ids) {
    $percents = @(Invoke-Docker (@('stats', '--no-stream', '--format', '{{.CPUPerc}}') + $ids) |
        ForEach-Object { [double]($_.Trim().TrimEnd('%')) })
    if ($percents.Count -eq 0) { return 0 }
    $average = ($percents | Measure-Object -Average).Average
    # 컨테이너 하나가 CPU 0.5개로 묶여 있으면 docker stats는 최대 50%를 보인다. 제한 대비 %로 바꾼다.
    return [math]::Round($average / $CpuLimit, 1)
}

function Write-ScalerLog([string]$line) {
    $stamped = "{0} {1}" -f (Get-Date -Format 'yyyy-MM-ddTHH:mm:ss'), $line
    Write-Host $stamped
    # 다른 프로그램이 로그 파일을 열어 두고 있어도(예: tail) 스케일러는 멈추지 않는다. 화면에는 계속 남는다.
    try {
        Add-Content -Path $LogFile -Value $stamped -Encoding utf8 -ErrorAction Stop
    } catch {
        Write-Warning "로그 파일에 쓰지 못했습니다: $($_.Exception.Message)"
    }
}

function Set-AppReplicas([int]$count) {
    Invoke-Compose @('up', '-d', '--no-build', '--no-recreate', '--scale', "app=$count", 'app') | Out-Null
}

$high = 0
$low = 0
$lastChange = [datetime]::MinValue
$iteration = 0
Write-ScalerLog "start min=$Min max=$Max out>=$ScaleOutPercent% x$ScaleOutAfter in<$ScaleInPercent% x$ScaleInAfter interval=${IntervalSeconds}s"

while ($MaxIterations -eq 0 -or $iteration -lt $MaxIterations) {
    $iteration++
    $ids = Get-AppContainerIds
    $current = $ids.Count
    if ($current -lt $Min) {
        Set-AppReplicas $Min
        Write-ScalerLog "instances=$current -> $Min (below minimum)"
        $lastChange = Get-Date
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }
    $cpu = Get-CpuUtilization $ids
    if ($cpu -ge $ScaleOutPercent) { $high++ } else { $high = 0 }
    if ($cpu -lt $ScaleInPercent) { $low++ } else { $low = 0 }
    $coolingDown = ((Get-Date) - $lastChange).TotalSeconds -lt $CooldownSeconds
    $target = $current
    if (-not $coolingDown -and $high -ge $ScaleOutAfter -and $current -lt $Max) { $target = $current + 1 }
    elseif (-not $coolingDown -and $low -ge $ScaleInAfter -and $current -gt $Min) { $target = $current - 1 }

    if ($target -ne $current) {
        Set-AppReplicas $target
        $direction = if ($target -gt $current) { 'scale-out' } else { 'scale-in' }
        Write-ScalerLog "instances=$current cpu=$cpu% -> $direction to $target"
        $high = 0
        $low = 0
        $lastChange = Get-Date
    } else {
        Write-ScalerLog "instances=$current cpu=$cpu% (high=$high low=$low cooldown=$coolingDown)"
    }
    Start-Sleep -Seconds $IntervalSeconds
}
