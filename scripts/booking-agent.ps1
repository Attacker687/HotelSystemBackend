param(
    [ValidateSet('eval', 'check', 'demo')][string]$Mode = 'eval',
    [string]$Maven = 'mvn',
    [ValidateSet('openai', 'fake')][string]$Provider = 'openai',
    [int]$Port = 8080,
    [ValidateSet('stream', 'init')][string]$FailureProbe
)

$ErrorActionPreference = 'Stop'
function Invoke-AgentEval([switch]$ContractOnly) {
    if (-not $ContractOnly -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        throw '独立真实评测需要 OPENAI_API_KEY；请在当前进程环境配置后重试。'
    }
    $selection = if ($ContractOnly) { if ($FailureProbe) { 'AgentEval#failureProbe' } else { 'AgentEval#offlineCheck' } } else { 'AgentEval#evaluateRealModel' }
    $probeArgs = @()
    if ($FailureProbe) { if (-not $ContractOnly) { throw '-FailureProbe 只能用于 -Mode check 的离线故障验证。' }; $probeArgs = @("-Dagent.eval.failure-probe=$FailureProbe") }
    & $Maven -B test-compile 'failsafe:integration-test@default' 'failsafe:verify@default' "-Dit.test=$selection" @probeArgs
    if ($LASTEXITCODE -ne 0) { throw "AgentEval 退出码：$LASTEXITCODE" }
}
function Invoke-AgentDemo {
    if ($Provider -eq 'openai' -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        throw '真实演示需要 OPENAI_API_KEY；请在当前进程环境配置后重试，或使用 -Provider fake。'
    }
    $savedProvider = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_PROVIDER', 'Process')
    $savedModel = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_MODEL', 'Process')
    $demoTarget = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../target'))
    $demoSourcePath = Join-Path $demoTarget 'agent-demo-source.json'
    $demoJarArgument = Join-Path $PSScriptRoot '../target/HotelSystemBackend-0.0.1-SNAPSHOT.jar'
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $demoZip = [IO.Compression.ZipFile]::OpenRead($demoJarArgument)
    try { $demoSdkEntries = @($demoZip.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/openai-java(?:-core|-client-okhttp)?-4\.73\.0\.jar$' } | ForEach-Object { $_.FullName }) }
    finally { $demoZip.Dispose() }
    if ($demoSdkEntries.Count -lt 2) { throw '演示 jar 未包含锁定的 OpenAI SDK 4.73.0，请重新 package。' }
    $demoSource = [ordered]@{ schemaVersion=1; runId=[guid]::NewGuid().ToString('N'); status='STARTING'; startedAt=(Get-Date).ToUniversalTime().ToString('o'); provider=$Provider; model='gpt-6-luna'; sdk='com.openai:openai-java:4.73.0'; sdkEntries=$demoSdkEntries; jarArgument=$demoJarArgument; jarSha256=(Get-FileHash -LiteralPath $demoJarArgument -Algorithm SHA256).Hash.ToLowerInvariant(); url="http://127.0.0.1:$Port/"; containers=@(); launcherPid=$null; javaPid=$null; readyAt=$null; sdkCalls=@() }
    function Save-DemoSource {
        $demoTemporary = "$demoSourcePath.tmp"
        [IO.File]::WriteAllText($demoTemporary, ($demoSource | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))
        if ([IO.File]::Exists($demoSourcePath)) { [IO.File]::Replace($demoTemporary, $demoSourcePath, [NullString]::Value) }
        else { [IO.File]::Move($demoTemporary, $demoSourcePath) }
    }
    function Receive-DemoLine([string]$demoLine) {
        if ($demoLine -match 'agent\.llm user=(\d+) model=(\S+) input=(\S+) output=(\S+) cached=(\S+) ms=(\d+) result=(SUCCESS|TIMEOUT|MODEL_UNAVAILABLE)') {
            $demoSource.sdkCalls += [ordered]@{ recordedAt=(Get-Date).ToUniversalTime().ToString('o'); userId=[int]$Matches[1]; model=$Matches[2]; ms=[long]$Matches[6]; result=$Matches[7] }
            Save-DemoSource
        }
        Write-Host $demoLine
    }
    function Update-DemoReady {
        if ($demoSource.status -ne 'STARTING') { return }
        $demoJava = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId=" + $demoSource.launcherPid + " AND Name='java.exe'") | Where-Object {
            $_.CommandLine.Replace('/', '\').IndexOf($demoJarArgument.Replace('/', '\'), [StringComparison]::OrdinalIgnoreCase) -ge 0 -and $_.CreationDate.ToUniversalTime() -ge [DateTime]::Parse($demoSource.startedAt).ToUniversalTime()
        })
        if ($demoJava.Count -ne 1) { return }
        $demoSource.javaPid = [int]$demoJava[0].ProcessId
        $demoInfoFile = Join-Path $demoTarget 'demo-info.json'
        if (-not (Test-Path -LiteralPath $demoInfoFile) -or (Get-Item -LiteralPath $demoInfoFile).LastWriteTimeUtc -lt [DateTime]::Parse($demoSource.startedAt).ToUniversalTime()) { return }
        $demoInfo = Get-Content -LiteralPath $demoInfoFile -Raw | ConvertFrom-Json
        if ($demoInfo.url -ne $demoSource.url -or $demoInfo.containers.Count -ne 2) { throw '本次演示启动来源不完整。' }
        $demoSource.containers = @($demoInfo.containers)
        $demoRequest = [Net.HttpWebRequest]::Create($demoSource.url); $demoRequest.Timeout = 500
        try { $demoResponse = $demoRequest.GetResponse(); try { if ([int]$demoResponse.StatusCode -ne 200) { return } } finally { $demoResponse.Dispose() } }
        catch [Net.WebException] { return }
        $demoSource.readyAt = (Get-Date).ToUniversalTime().ToString('o'); $demoSource.status='RUNNING'; Save-DemoSource
    }
    Save-DemoSource
    $demoProcess = New-Object Diagnostics.Process
    try {
        $env:HOTEL_AGENT_PROVIDER = $Provider
        $env:HOTEL_AGENT_MODEL = 'gpt-6-luna'
        $demoProcess.StartInfo.FileName = 'powershell.exe'
        $demoProcess.StartInfo.Arguments = '-NoProfile -File "' + (Join-Path $PSScriptRoot 'demo.ps1') + '" -Port ' + $Port
        $demoProcess.StartInfo.UseShellExecute = $false; $demoProcess.StartInfo.CreateNoWindow = $true
        $demoProcess.StartInfo.RedirectStandardOutput = $true; $demoProcess.StartInfo.RedirectStandardError = $true
        if (-not $demoProcess.Start()) { throw '演示启动器未启动。' }
        $demoSource.launcherPid = $demoProcess.Id; Save-DemoSource
        $demoOutput = $demoProcess.StandardOutput.ReadLineAsync(); $demoError = $demoProcess.StandardError.ReadLineAsync()
        while ($demoOutput -or $demoError) {
            if ($demoOutput -and $demoOutput.IsCompleted) {
                $demoLine = $demoOutput.GetAwaiter().GetResult()
                if ($null -eq $demoLine) { $demoOutput = $null } else { Receive-DemoLine $demoLine; $demoOutput = $demoProcess.StandardOutput.ReadLineAsync() }
            }
            if ($demoError -and $demoError.IsCompleted) {
                $demoLine = $demoError.GetAwaiter().GetResult()
                if ($null -eq $demoLine) { $demoError = $null } else { Receive-DemoLine $demoLine; $demoError = $demoProcess.StandardError.ReadLineAsync() }
            }
            if (-not $demoProcess.HasExited) { Update-DemoReady }
            $demoPending = @($demoOutput, $demoError | Where-Object { $null -ne $_ })
            if ($demoPending.Count) { [void][Threading.Tasks.Task]::WaitAny([Threading.Tasks.Task[]]$demoPending, 250) }
        }
        $demoProcess.WaitForExit()
        if ($demoProcess.ExitCode -ne 0) { throw "演示启动器退出码：$($demoProcess.ExitCode)" }
    } finally {
        try {
            if ($demoSource.javaPid) {
                $demoOwnedJava = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $demoSource.javaPid)
                if ($demoOwnedJava -and $demoOwnedJava.ParentProcessId -eq $demoSource.launcherPid -and $demoOwnedJava.Name -eq 'java.exe') { Stop-Process -Id $demoSource.javaPid -ErrorAction SilentlyContinue }
            }
            if ($demoSource.launcherPid -and -not $demoProcess.HasExited -and -not $demoProcess.WaitForExit(5000)) { $demoProcess.Kill() }
            foreach ($demoContainer in $demoSource.containers) {
                $demoRemaining = & docker ps -aq --no-trunc --filter ("id=" + $demoContainer)
                if ($demoRemaining -eq $demoContainer) { & docker rm -f $demoContainer | Out-Null }
            }
        } finally {
            $demoProcess.Dispose()
            [Environment]::SetEnvironmentVariable('HOTEL_AGENT_PROVIDER', $savedProvider, 'Process')
            [Environment]::SetEnvironmentVariable('HOTEL_AGENT_MODEL', $savedModel, 'Process')
            $demoSource.status='STOPPED'; $demoSource.stoppedAt=(Get-Date).ToUniversalTime().ToString('o')
            $demoSource.providerProcessRestored = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_PROVIDER', 'Process') -eq $savedProvider
            $demoSource.modelProcessRestored = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_MODEL', 'Process') -eq $savedModel
            Save-DemoSource
        }
    }
}
$previousDirectory = Get-Location
try {
    Set-Location -LiteralPath (Join-Path $PSScriptRoot '..')
    if ($Mode -eq 'demo') { Invoke-AgentDemo }
    else {
        Invoke-AgentEval -ContractOnly:($Mode -eq 'check')
        if ($Mode -eq 'check') {
            & node (Join-Path $PSScriptRoot 'booking-agent-demo.mjs') --self-check
            if ($LASTEXITCODE -ne 0) { throw "演示离线检查退出码：$LASTEXITCODE" }
        }
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally { Set-Location -LiteralPath $previousDirectory.Path }
