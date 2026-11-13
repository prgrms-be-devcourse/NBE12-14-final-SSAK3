$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$demoEnv = Join-Path $repoRoot '.env.demo'
if (-not (Test-Path -LiteralPath $demoEnv)) {
    function New-DemoSecret {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
    }
    @(
        "DEMO_MYSQL_PASSWORD=$(New-DemoSecret)"
        "DEMO_MYSQL_ROOT_PASSWORD=$(New-DemoSecret)"
        "DEMO_JWT_SECRET=$(New-DemoSecret)"
    ) | Set-Content -LiteralPath $demoEnv -Encoding ascii
}
docker compose --env-file $demoEnv -p csmatch-demo -f (Join-Path $repoRoot 'compose.demo.yml') up -d --wait mysql redis
if ($LASTEXITCODE -ne 0) { throw 'Demo infrastructure failed to start.' }
Write-Host 'Demo DB ready on localhost:3307. Existing development DB is unchanged.'
Write-Host 'Backend: cd backend; ./gradlew bootRun --args="--spring.profiles.active=demo"'
Write-Host 'Frontend: cd frontend; npm ci; npm run dev:demo'
