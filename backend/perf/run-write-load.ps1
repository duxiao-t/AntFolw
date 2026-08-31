[CmdletBinding()]
param([switch]$Quick)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$compose = Join-Path $root 'compose.write-load.yaml'
$localCompose = Join-Path $root 'compose.yaml'
$envFile = Join-Path $root '.env.docker.local'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDir = Join-Path $root "_perf\write-load\$stamp"
$running = @()
$localStopped = $false

New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
Push-Location $root
try {
    Write-Host 'Building backend before stopping the local stack...'
    & mvn -q -f backend/pom.xml -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed ($LASTEXITCODE)" }

    $running = @(& docker compose --env-file $envFile -f $localCompose ps --services --filter status=running |
        Where-Object { $_ -and $_.Trim() } | ForEach-Object { $_.Trim() })
    if ($LASTEXITCODE -ne 0) { throw 'Could not record the running local services' }
    $running | Set-Content -Encoding utf8 (Join-Path $resultDir 'previously-running-services.txt')

    if ($running.Count -gt 0) {
        Write-Host "Stopping local services: $($running -join ', ')"
        & docker compose --env-file $envFile -f $localCompose stop @running
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop the local services' }
        $localStopped = $true
    }

    foreach ($pool in @(10, 20)) {
        Write-Host "Starting isolated Hikari pool $pool stack..."
        & docker compose -p antflow-write-load -f $compose down --volumes --remove-orphans
        $env:HIKARI_POOL_SIZE = [string]$pool
        & docker compose -p antflow-write-load -f $compose up -d --build
        if ($LASTEXITCODE -ne 0) { throw "Could not start pool $pool stack" }

        $healthy = $false
        for ($attempt = 0; $attempt -lt 120; $attempt++) {
            try {
                $health = Invoke-RestMethod -TimeoutSec 2 http://127.0.0.1:18080/actuator/health
                if ($health.status -eq 'UP') { $healthy = $true; break }
            } catch { Start-Sleep -Seconds 1 }
        }
        if (-not $healthy) { throw "Pool $pool backend did not become healthy" }

        $output = Join-Path $resultDir "pool-$pool.json"
        $nodeArgs = @('backend/perf/write-load.mjs', '--base=http://127.0.0.1:18080',
            "--pool=$pool", "--output=$output")
        if ($Quick) { $nodeArgs += '--quick' }
        & node @nodeArgs
        $nodeExit = $LASTEXITCODE
        & docker compose -p antflow-write-load -f $compose logs --no-color |
            Set-Content -Encoding utf8 (Join-Path $resultDir "pool-$pool-containers.log")
        if ($nodeExit -ne 0) { throw "Pool $pool load runner failed ($nodeExit)" }

        & docker compose -p antflow-write-load -f $compose down --volumes --remove-orphans
        if ($LASTEXITCODE -ne 0) { throw "Could not remove pool $pool temporary stack" }
    }

    & node backend/perf/write-load.mjs `
        "--compare=$(Join-Path $resultDir 'pool-10.json'),$(Join-Path $resultDir 'pool-20.json')" `
        "--output=$(Join-Path $resultDir 'comparison.md')"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create comparison report' }
    Write-Host "Write-path benchmark complete: $resultDir"
}
finally {
    Remove-Item Env:HIKARI_POOL_SIZE -ErrorAction SilentlyContinue
    & docker compose -p antflow-write-load -f $compose down --volumes --remove-orphans
    if ($localStopped -and $running.Count -gt 0) {
        Write-Host "Restoring local services: $($running -join ', ')"
        & docker compose --env-file $envFile -f $localCompose start @running
    }
    Pop-Location
}
