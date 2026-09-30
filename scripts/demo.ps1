param([int]$Port = 8080)

$ErrorActionPreference = 'Stop'
$demoJar = Join-Path $PSScriptRoot '../target/HotelSystemBackend-0.0.1-SNAPSHOT.jar'
if (-not (Test-Path -LiteralPath $demoJar)) { throw '请先执行 mvn -DskipTests package 构建 jar。' }
$demoId = [guid]::NewGuid().ToString('N').Substring(0, 10)
$demoPassword = [guid]::NewGuid().ToString('N')
$demoContainers = @()
$demoEnvironment = @{}
$demoNames = @('SPRING_PROFILES_ACTIVE', 'SERVER_PORT', 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'REDIS_HOST', 'REDIS_PORT', 'REDIS_PASSWORD', 'JWT_SECRET', 'HOTEL_SCHEDULER_ENABLED')
foreach ($demoName in $demoNames) { $demoEnvironment[$demoName] = [Environment]::GetEnvironmentVariable($demoName, 'Process') }
try {
    $demoMysql = (& docker run -d --rm --name "hotel-demo-mysql-$demoId" -p '127.0.0.1::3306' -e 'TZ=Asia/Shanghai' -e 'MYSQL_DATABASE=hotel_demo' -e "MYSQL_ROOT_PASSWORD=$demoPassword" mysql:8.0 --default-time-zone=+08:00).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 容器启动失败，请确认 Docker 正在运行。' }
    $demoContainers += $demoMysql
    $demoRedis = (& docker run -d --rm --name "hotel-demo-redis-$demoId" -p '127.0.0.1::6379' redis:7-alpine).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Redis 容器启动失败。' }
    $demoContainers += $demoRedis
    $demoMysqlPort = ((& docker port $demoMysql 3306).Trim() -split ':')[-1]
    $demoRedisPort = ((& docker port $demoRedis 6379).Trim() -split ':')[-1]
    $demoReady = $false
    for ($demoAttempt = 0; $demoAttempt -lt 90; $demoAttempt++) {
        & docker exec -e "MYSQL_PWD=$demoPassword" $demoMysql sh -c 'mysqladmin ping -h 127.0.0.1 --silent 2>/dev/null' | Out-Null
        if ($LASTEXITCODE -eq 0) { $demoReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $demoReady) { throw 'MySQL 在 90 秒内未就绪。' }
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:SERVER_PORT = "$Port"
    $env:DB_URL = "jdbc:mysql://127.0.0.1:$demoMysqlPort/hotel_demo?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    $env:DB_USERNAME = 'root'
    $env:DB_PASSWORD = $demoPassword
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = $demoRedisPort
    $env:REDIS_PASSWORD = ''
    $demoSecret = New-Object byte[] 32
    $demoRandom = [Security.Cryptography.RandomNumberGenerator]::Create()
    $demoRandom.GetBytes($demoSecret)
    $demoRandom.Dispose()
    $env:JWT_SECRET = [BitConverter]::ToString($demoSecret).Replace('-', '')
    $env:HOTEL_SCHEDULER_ENABLED = 'true'
    @{ url = "http://127.0.0.1:$Port/"; containers = $demoContainers } | ConvertTo-Json | Set-Content -Encoding utf8 -LiteralPath (Join-Path $PSScriptRoot '../target/demo-info.json')
    Write-Host "演示地址：http://127.0.0.1:$Port/（等待 Started 日志后打开）"
    Write-Host '员工：admin / Admin@123；front / Front@123；kitchen / Kitchen@123。住客：13900000000 / User@1234。'
    Write-Host 'Ctrl+C 停止应用并移除本次演示的临时容器。'
    & java '-Duser.timezone=Asia/Shanghai' -jar $demoJar
    if ($LASTEXITCODE -ne 0) { throw "应用退出码：$LASTEXITCODE" }
} finally {
    foreach ($demoContainer in $demoContainers) { & docker rm -f $demoContainer 2>$null | Out-Null }
    foreach ($demoName in $demoNames) { [Environment]::SetEnvironmentVariable($demoName, $demoEnvironment[$demoName], 'Process') }
}
