<#
.SYNOPSIS
    ⑤ 로컬 오토스케일러. 앱 컨테이너의 CPU 사용률을 보고 최소~최대 인스턴스 수 안에서 자동으로 스케일 아웃·인 한다.

.DESCRIPTION
    IntervalSeconds마다 deploy/compose.yaml의 app 컨테이너들을 docker stats로 재고,
    CPU 사용률(컨테이너 CPU 제한 대비 평균 %)이
      - ScaleOutPercent 이상이 ScaleOutAfter번 연속이면 1개 늘리고(최대 Max),
      - ScaleInPercent 미만이 ScaleInAfter번 연속이면 1개 줄인다(최소 Min).
    바꾼 뒤 CooldownSeconds 동안은 다시 바꾸지 않는다. 판단 근거와 인스턴스 수를 LogFile에 한 줄씩 남긴다.
    부하 분산기(nginx)의 분산 대상도 함께 관리한다(lb-upstream.ps1).
      - 매 회차: 요청에 답하는 새 인스턴스는 대상에 넣고, 없어진 인스턴스는 뺀다.
      - 스케일 인: 내릴 인스턴스(가장 나중에 만든 것)를 먼저 대상에서 빼고 nginx를 다시 읽게 한 뒤 DrainSeconds 기다렸다가
        docker stop으로 SIGTERM을 보낸다. 앱은 처리 중인 요청을 마친 뒤 내려간다(server.shutdown=graceful).
        그래서 내려가는 인스턴스로는 새 요청이 가지 않는다.

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
    [string]$UpstreamFile = (Join-Path $PSScriptRoot '..\..\deploy\nginx\upstream.d\app-servers.conf'),
    # 대상에서 뺀 뒤 인스턴스를 내리기까지 기다리는 시간. nginx가 새 설정으로 바뀌고 이미 보낸 요청이 끝날 여유를 둔다.
    [int]$DrainSeconds = 5,
    # 0이면 멈출 때(Ctrl+C)까지 계속 돈다.
    [int]$MaxIterations = 0
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lb-upstream.ps1')

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

$composeBase = @('compose', '-f', $ComposeFile)
if (Test-Path $EnvFile) { $composeBase += @('--env-file', $EnvFile) }

function Invoke-Compose([string[]]$arguments) {
    Invoke-Docker ($composeBase + $arguments)
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

# 부하 분산기 대상에 들어 있는(새 요청을 받는) 인스턴스. 컨테이너 ID와 주소가 모두 같아야 같은 인스턴스로 본다.
$inService = @()
$upstreamWritten = $false

function Get-InstanceKey($instance) { "$($instance.Id)|$($instance.Ip)" }

# 실행 중인 인스턴스와 대상 목록을 맞춘다. 없어진 것은 빼고, 요청에 답하는 새 인스턴스는 넣는다.
function Sync-LbTargets {
    $running = @(Get-AppInstances $composeBase)
    $runningKeys = @($running | ForEach-Object { Get-InstanceKey $_ })
    $kept = @($script:inService | Where-Object { $runningKeys -contains (Get-InstanceKey $_) })
    $keptKeys = @($kept | ForEach-Object { Get-InstanceKey $_ })
    $joined = @($running | Where-Object { $keptKeys -notcontains (Get-InstanceKey $_) -and (Test-AppReady $composeBase $_.Ip) })
    if ($joined.Count -eq 0 -and $kept.Count -eq @($script:inService).Count -and $script:upstreamWritten) { return }
    $script:inService = @($kept + $joined)
    Set-LbUpstream $composeBase $UpstreamFile $script:inService
    $script:upstreamWritten = $true
    Write-ScalerLog ("lb targets={0} [{1}]" -f $script:inService.Count, (($script:inService | Sort-Object Name | ForEach-Object { $_.Name }) -join ' '))
}

# 스케일 인: 대상에서 먼저 빼고(드레이닝) 기다린 뒤 내린다. 아직 대상에 들지 못한 인스턴스가 있으면 그것부터 내린다.
function Remove-AppInstance {
    $running = @(Get-AppInstances $composeBase)
    $serviceKeys = @($script:inService | ForEach-Object { Get-InstanceKey $_ })
    $victim = $running |
        Sort-Object @{ Expression = { $serviceKeys -contains (Get-InstanceKey $_) } }, @{ Expression = 'Created'; Descending = $true } |
        Select-Object -First 1
    $remaining = @($script:inService | Where-Object { (Get-InstanceKey $_) -ne (Get-InstanceKey $victim) })
    if ($remaining.Count -eq 0) {
        Write-ScalerLog "scale-in skipped: $($victim.Name)을 빼면 요청을 받을 인스턴스가 없다"
        return $null
    }
    if ($remaining.Count -ne @($script:inService).Count) {
        $script:inService = $remaining
        Set-LbUpstream $composeBase $UpstreamFile $script:inService
        Write-ScalerLog "lb drain $($victim.Name) ($($victim.Ip)), stop after ${DrainSeconds}s"
        Start-Sleep -Seconds $DrainSeconds
    }
    Invoke-Docker @('stop', '-t', '30', $victim.Id) | Out-Null
    Invoke-Docker @('rm', $victim.Id) | Out-Null
    return $victim.Name
}

$high = 0
$low = 0
$lastChange = [datetime]::MinValue
$iteration = 0
Write-ScalerLog "start min=$Min max=$Max out>=$ScaleOutPercent% x$ScaleOutAfter in<$ScaleInPercent% x$ScaleInAfter interval=${IntervalSeconds}s"

while ($MaxIterations -eq 0 -or $iteration -lt $MaxIterations) {
    $iteration++
    Sync-LbTargets
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

    if ($target -gt $current) {
        # 새 인스턴스는 요청에 답하게 된 뒤 다음 회차의 Sync-LbTargets가 대상에 넣는다.
        Set-AppReplicas $target
        Write-ScalerLog "instances=$current cpu=$cpu% -> scale-out to $target"
    } elseif ($target -lt $current) {
        $removed = Remove-AppInstance
        if ($removed) { Write-ScalerLog "instances=$current cpu=$cpu% -> scale-in to $target (removed $removed)" }
        else { $target = $current }
    }
    if ($target -ne $current) {
        $high = 0
        $low = 0
        $lastChange = Get-Date
    } else {
        Write-ScalerLog "instances=$current cpu=$cpu% (high=$high low=$low cooldown=$coolingDown)"
    }
    Start-Sleep -Seconds $IntervalSeconds
}
