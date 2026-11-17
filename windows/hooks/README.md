# Island hooks — Claude Code, terminal, anything

The island listens on `http://127.0.0.1:47821` (local only). Every request needs the header `X-Island: 1`.

## Easiest: use the buttons

Island settings → **Developer** → *Install Claude Code hooks* / *Install PowerShell hook*.
They copy these scripts to `%APPDATA%\DynamicIsland\hooks\` and register them (your existing settings are backed up first).

## What you get

| Source | In the island |
|---|---|
| Claude Code prompt sent | Live pill: "Claude · project" with a spinner and elapsed time |
| Claude needs input | Orange "waiting" pill + peek; click jumps to VS Code / Terminal |
| Claude asks permission | **Allow / Deny** buttons right in the island (falls back to the normal prompt after 45 s) |
| Claude finished | Green "done" peek with the last message |
| PowerShell command ≥ 10 s | "Command finished / failed" with duration |

## Manual Claude Code setup

Add to `%USERPROFILE%\.claude\settings.json` (merge with any existing `hooks`):

```json
{
  "hooks": {
    "UserPromptSubmit": [{ "hooks": [{ "type": "command", "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:/Users/YOU/AppData/Roaming/DynamicIsland/hooks/claude-island.ps1\"", "timeout": 10 }] }],
    "Notification":     [{ "hooks": [{ "type": "command", "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:/Users/YOU/AppData/Roaming/DynamicIsland/hooks/claude-island.ps1\"", "timeout": 10 }] }],
    "Stop":             [{ "hooks": [{ "type": "command", "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:/Users/YOU/AppData/Roaming/DynamicIsland/hooks/claude-island.ps1\"", "timeout": 10 }] }],
    "PermissionRequest":[{ "matcher": "*", "hooks": [{ "type": "command", "command": "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:/Users/YOU/AppData/Roaming/DynamicIsland/hooks/claude-island.ps1\"", "timeout": 60 }] }]
  }
}
```

Forward slashes work in both PowerShell and Git Bash, whichever shell Claude Code uses.

## From your own scripts (builds, deploys, tests)

```powershell
$h = @{ 'X-Island' = '1' }
# start / update a live activity (same id = update)
Invoke-RestMethod http://127.0.0.1:47821/activity -Method Post -Headers $h -ContentType application/json `
  -Body '{"id":"build1","title":"Building app","icon":"build","state":"running","progress":0.4}'
# finish it
Invoke-RestMethod http://127.0.0.1:47821/activity -Method Post -Headers $h -ContentType application/json `
  -Body '{"id":"build1","title":"Build passed","icon":"build","state":"done"}'
# one-off peek
Invoke-RestMethod http://127.0.0.1:47821/notify -Method Post -Headers $h -ContentType application/json `
  -Body '{"title":"Deployed","body":"v2.3 is live","icon":"deploy"}'
# ask a question and wait for the click -> {"choice":"Yes"}
Invoke-RestMethod http://127.0.0.1:47821/ask -Method Post -Headers $h -ContentType application/json `
  -Body '{"title":"Push to main?","buttons":["Yes","No"],"timeout":60}'
```

`curl` works too: `curl -H "X-Island: 1" -d "{\"title\":\"Hi\"}" http://127.0.0.1:47821/notify`

Icons: `claude`, `terminal`, `build`, `test`, `deploy`, `git`, `check`, `error`, `info`.
States: `running`, `waiting`, `done`, `error`. Optional: `progress` (0–1), `focus` (process to bring forward on click, e.g. `Code`), `ttl` (seconds to keep a finished item).
