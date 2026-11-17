# Pushes the current branch to GitHub.
#
# github.com:443 is sometimes reset from mainland networks; pass -Proxy when the direct
# connection fails and a local HTTP proxy (Clash/V2Ray/...) is running.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\\push-github.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\\push-github.ps1 -Proxy http://127.0.0.1:7890
param(
    [string]$Remote = 'origin',
    [string]$Branch = 'master',
    [string]$Proxy  = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

$gitArgs = @('-C', $repo, '-c', 'http.version=HTTP/1.1')
if ($Proxy) { $gitArgs += @('-c', ('http.proxy=' + $Proxy)) }
$gitArgs += @('push', $Remote, $Branch)

$via = ''
if ($Proxy) { $via = ' via ' + $Proxy }
Write-Host ('pushing ' + $Branch + ' -> ' + $Remote + $via)
& git @gitArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host 'PUSH FAILED : no commit was lost; fix the network or pass -Proxy and run again.'
    exit 1
}
Write-Host 'PUSH OK'