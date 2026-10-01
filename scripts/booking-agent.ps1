param(
    [ValidateSet('eval', 'check', 'demo')][string]$Mode = 'eval',
    [string]$Maven = 'mvn',
    [ValidateSet('openai', 'fake')][string]$Provider = 'openai',
    [int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
function Invoke-AgentEval([switch]$ContractOnly) {
    if (-not $ContractOnly -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        throw '独立真实评测需要 OPENAI_API_KEY；请在当前进程环境配置后重试。'
    }
    $selection = if ($ContractOnly) { 'AgentEval#offlineCheck' } else { 'AgentEval#evaluateRealModel' }
    & $Maven -B test-compile 'failsafe:integration-test@default' 'failsafe:verify@default' "-Dit.test=$selection"
    if ($LASTEXITCODE -ne 0) { throw "AgentEval 退出码：$LASTEXITCODE" }
}
function Invoke-AgentDemo {
    if ($Provider -eq 'openai' -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        throw '真实演示需要 OPENAI_API_KEY；请在当前进程环境配置后重试，或使用 -Provider fake。'
    }
    $savedProvider = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_PROVIDER', 'Process')
    $savedModel = [Environment]::GetEnvironmentVariable('HOTEL_AGENT_MODEL', 'Process')
    try {
        $env:HOTEL_AGENT_PROVIDER = $Provider
        $env:HOTEL_AGENT_MODEL = 'gpt-6-luna'
        & powershell.exe -NoProfile -File (Join-Path $PSScriptRoot 'demo.ps1') -Port $Port
        if ($LASTEXITCODE -ne 0) { throw "演示启动器退出码：$LASTEXITCODE" }
    } finally {
        [Environment]::SetEnvironmentVariable('HOTEL_AGENT_PROVIDER', $savedProvider, 'Process')
        [Environment]::SetEnvironmentVariable('HOTEL_AGENT_MODEL', $savedModel, 'Process')
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