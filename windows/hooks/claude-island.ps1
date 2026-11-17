# Claude Code -> Dynamic Island bridge.
# Registered for UserPromptSubmit, Notification, Stop and PermissionRequest (see README.md).
# Reads the hook JSON from stdin and talks to the island's local API on 127.0.0.1:47821.
# If the island isn't running, every call fails fast and Claude Code behaves exactly as normal.
# NOTE: keep this file pure ASCII - Windows PowerShell 5.1 misreads UTF-8 without a BOM.

$ErrorActionPreference = 'SilentlyContinue'
# Claude Code sends UTF-8; without this PowerShell decodes stdin with the old console code page
# and anything non-ASCII (check marks, emoji, Hindi/Gujarati) turns into garbage like "Γ£ô".
[Console]::InputEncoding = [Text.Encoding]::UTF8
$raw = [Console]::In.ReadToEnd()
try { $j = $raw | ConvertFrom-Json } catch { exit 0 }
if (-not $j) { exit 0 }

$api = 'http://127.0.0.1:47821'
$headers = @{ 'X-Island' = '1' }
$project = if ($j.cwd) { Split-Path -Leaf $j.cwd } else { 'Claude' }
$id = "claude-$($j.session_id)"
$focus = if ($env:TERM_PROGRAM -eq 'vscode' -or $env:VSCODE_PID) { 'Code' } elseif ($env:WT_SESSION) { 'WindowsTerminal' } else { $null }

function Post([string]$path, $body, [int]$timeout = 2) {
    try {
        Invoke-RestMethod -Uri "$api$path" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' `
            -Body ([Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 5))) -TimeoutSec $timeout
    } catch { $null }
}

function Short([string]$s, [int]$n) {
    if (-not $s) { return '' }
    # Plain text for a one-line preview: drop markdown (bold/italic markers, code ticks, headings, links, bullets).
    $s = $s -replace '\[([^\]]*)\]\([^)]*\)', '$1'
    $s = $s -replace '(\*\*|__|`+|~~)', ''
    $s = $s -replace '(?m)^\s*(#+|[-*>]|\d+\.)\s+', ''
    $s = ($s -replace '\s+', ' ').Trim()
    if ($s.Length -gt $n) { $s.Substring(0, $n) + '...' } else { $s }
}

# Every call carries the session's project (label) so the island can tell parallel sessions apart
# ("Dynamic Island", "Dynamic Island #2", "Twindesk") and jump to the right VS Code window.
function Act($title, $body, $state, $extra = @{}) {
    $a = @{ id = $id; title = $title; body = $body; icon = 'claude'; state = $state; label = $project; window = $project; focus = $focus }
    foreach ($k in $extra.Keys) { $a[$k] = $extra[$k] }
    Post '/activity' $a | Out-Null
}

# The session moved on (e.g. you answered the permission prompt in the chat instead of the island):
# clear any question the island is still showing for it.
function Resolve { Post '/resolve' @{ activity = $id } 1 | Out-Null }

# Small diagnostic log (event, session, agent, tool) - no prompt/message text. Kept under ~200 KB.
$logFile = Join-Path $PSScriptRoot 'hook.log'
function Log([string]$extra = '') {
    try {
        if ((Test-Path $logFile) -and (Get-Item $logFile).Length -gt 200KB) { Remove-Item $logFile -Force }
        $sid = if ($j.session_id) { "$($j.session_id)".Substring(0, [Math]::Min(8, "$($j.session_id)".Length)) } else { '-' }
        $agent = if ($j.agent_id) { $j.agent_id } else { '-' }
        Add-Content -Path $logFile -Value ("{0:HH:mm:ss.fff} {1,-18} session={2} agent={3} tool={4} {5}" -f (Get-Date), $j.hook_event_name, $sid, $agent, $j.tool_name, $extra)
    } catch { }
}
Log

switch ($j.hook_event_name) {
    'UserPromptSubmit' {
        Resolve
        $p = Short $j.prompt 90
        Act 'Claude' $p 'running' @{ topic = $p }
    }
    'Notification' {
        # Permission prompts are handled by PermissionRequest below (with Allow/Deny buttons).
        if ($j.notification_type -ne 'permission_prompt') { Resolve; Act 'Claude needs you' (Short $j.message 120) 'waiting' }
    }
    'PostToolUse' {
        # A tool just ran, so any pending permission question for this session was answered elsewhere.
        Resolve
        Act 'Claude' 'Working...' 'running'
    }
    'PostToolUseFailure' {
        Resolve
        Act 'Claude' 'Working...' 'running'
    }
    'Stop' {
        Resolve
        Act 'Claude finished' (Short $j.last_assistant_message 140) 'done' @{ ttl = 10 }
    }
    'PermissionRequest' {
        $ti = $j.tool_input
        $detail = if ($ti.command) { $ti.command } elseif ($ti.file_path) { $ti.file_path } elseif ($ti.url) { $ti.url } else { ($ti | ConvertTo-Json -Compress -Depth 3) }
        Act 'Claude is waiting' "Permission: $($j.tool_name)" 'waiting'
        # Find this exact tool call in the session's transcript, so we can notice when you answer it in the
        # chat instead (Claude Code doesn't stop this hook then): its tool_result shows up in the transcript.
        $tp = $j.transcript_path
        $toolUseId = $null
        if ($tp -and (Test-Path $tp)) {
            try {
                $want = ($ti | ConvertTo-Json -Compress -Depth 8)
                $sameName = $null
                foreach ($line in (Get-Content -Path $tp -Tail 80 -Encoding UTF8)) {
                    if ($line -notmatch '"tool_use"') { continue }
                    foreach ($c in @((($line | ConvertFrom-Json).message).content)) {
                        if ($c.type -ne 'tool_use' -or $c.name -ne $j.tool_name) { continue }
                        $sameName = $c.id
                        if (($c.input | ConvertTo-Json -Compress -Depth 8) -eq $want) { $toolUseId = $c.id }
                    }
                }
                if (-not $toolUseId) { $toolUseId = $sameName }
            } catch { }
        }
        Log "tool_use=$toolUseId"

        # Poll mode: the island gets the question at once and we check in every ~0.6 s.
        $q = Post '/ask' @{ title = "Allow $($j.tool_name)?"; body = (Short $detail 150); buttons = @('Allow', 'Deny'); icon = 'claude'; timeout = 45; focus = $focus; window = $project; activity = $id; poll = $true } 3
        if (-not $q -or -not $q.id) { exit 0 }   # island not running: normal prompt
        $r = $null
        $n = 0
        $deadline = (Get-Date).AddSeconds(45)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 600
            $a = Post '/answer' @{ id = $q.id } 2
            if (-not $a) { break }                 # island closed
            if (-not $a.pending) { $r = $a; break }
            # Answered in the chat? (allowed and run, or denied) -> take the question off the island.
            if ($toolUseId -and (++$n % 2 -eq 0)) {
                $done = Get-Content -Path $tp -Tail 40 -Encoding UTF8 | Where-Object { $_.Contains($toolUseId) -and $_.Contains('"tool_result"') }
                if ($done) { Post '/cancel' @{ id = $q.id } 1 | Out-Null; Log 'answered-in-chat'; exit 0 }
            }
        }
        Log "answer=$($r.choice)"
        if ($r -and $r.choice -eq 'Allow') {
            Act 'Claude' 'Working...' 'running'
            '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
        }
        elseif ($r -and $r.choice -eq 'Deny') {
            '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"Denied from the Dynamic Island"}}}'
        }
        # No answer (timeout or island closed): print nothing, so Claude Code shows its normal prompt.
    }
}
exit 0
