# PowerShell -> Dynamic Island: tells the island when a command that ran 10+ seconds finishes.
# Add this line to your PowerShell profile ($PROFILE):
#   . "$env:APPDATA\DynamicIsland\hooks\island-terminal.ps1"

if ($global:__IslandHooked) { return }
$global:__IslandHooked = $true
$global:__IslandLastId = (Get-History -Count 1).Id
$global:__IslandOrigPrompt = $function:prompt

function global:prompt {
    $ok = $?
    $h = Get-History -Count 1
    if ($h -and $h.Id -ne $global:__IslandLastId) {
        $global:__IslandLastId = $h.Id
        $secs = ($h.EndExecutionTime - $h.StartExecutionTime).TotalSeconds
        if ($secs -ge 10) {
            $cmd = $h.CommandLine
            if ($cmd.Length -gt 80) { $cmd = $cmd.Substring(0, 80) + '...' }
            $focus = if ($env:TERM_PROGRAM -eq 'vscode') { 'Code' } elseif ($env:WT_SESSION) { 'WindowsTerminal' } else { $null }
            $body = @{
                title = $(if ($ok) { 'Command finished' } else { 'Command failed' })
                body  = "$cmd  -  $([int]$secs)s"
                icon  = 'terminal'
                state = $(if ($ok) { 'done' } else { 'error' })
                ttl   = 8
                focus = $focus
            } | ConvertTo-Json -Compress
            try {
                Invoke-RestMethod -Uri 'http://127.0.0.1:47821/activity' -Method Post -Headers @{ 'X-Island' = '1' } `
                    -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 1 | Out-Null
            } catch { }
        }
    }
    & $global:__IslandOrigPrompt
}
