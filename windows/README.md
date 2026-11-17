# Dynamic Island — Windows (v2)

A notch at the top-centre of the screen that sits as a **thin bar when idle**, peeks on hover and opens into a 7-tab panel.
C# / WPF on .NET 10. Everything stays on this PC.

## Run

- **Ready-made:** `publish\DynamicIsland.exe` (needs the .NET 10 Desktop Runtime, already installed here).
- **From source:** `dotnet run --project DynamicIsland -c Release`
- **Rebuild:** `dotnet publish DynamicIsland -c Release -r win-x64 --self-contained false -o publish`

Tray icon (water drop): left-click opens the island, right-click opens the menu.

## Island states

| State | What it shows |
|---|---|
| Docked | Thin bar flush with the top edge; hairline = today's water; dot = mic/camera in use |
| Peek (hover) | Pet · time · weather · battery · water |
| Live activities | Now playing (art + bars that react to the real audio level), focus timer, Claude Code / builds (spinner, elapsed time) |
| Peeks | Volume, brightness, charging, low/full battery, energy saver, Windows notifications, Bluetooth, copied text, mic/camera, track change |
| Alerts | Pet reminder (Drink / Snooze), your reminders (Done / Snooze), Claude permission (**Allow / Deny**) |

## Expanded tabs

1. **Home**: full player with album-art glow and click-to-seek, or a big clock + weather; your pet card with quick drink and undo.
2. **Water**: big pet with growth stage and progress to the next stage; quick-add cups; drink types (coffee/tea count less); pace marker; 7-day chart; streak, freezes and badges.
3. **Focus**: 5–60 min timer with pause and daily stats; quick reminders ("call mom 20m" + Enter).
4. **Shelf**: drag files onto the island, drag them out later; double-click opens.
5. **Clipboard**: last 25 text copies; click to copy again. Memory only; password-manager copies are skipped and secret-looking text is hidden.
6. **System**: live CPU and network graphs, memory, disk, battery time left, uptime.
7. **Dev**: Claude Code sessions, long terminal commands, builds; click to jump to VS Code / Terminal.

## The pet

Pick **Axolotl, Droplet, Plant or Otter** in Settings → Water & pet. Each has 4 growth stages (unlocked by goal days: 3 / 10 / 25) and 3 unlockable looks.
It shows today's water (tank level, body fill, soil, pool), follows your cursor, dances to music, sleeps at night and reacts when you drink or poke it.
Reminders are **pace-based**: only when you're behind for the time of day, never within 20 min of a drink, and they back off if ignored.

## Controls

- Hover the bar → peek. Click → open. Move away → closes.
- Scroll on the island → volume. Scroll on the tab bar → switch tabs.
- Drag files onto the island → shelf.
- **Ctrl+Alt+W** log a drink · **Ctrl+Alt+I** open/close · **Ctrl+Alt+V** clipboard.
- Right-click the island → settings.

## Developer integration

Settings → Developer → **Install Claude Code hooks** / **Install PowerShell hook**, or **Run demo** to see it without setup.
Any script can drive the island through the local API (`127.0.0.1:47821`). See `hooks\README.md`.

## Data

`%APPDATA%\DynamicIsland\settings.json` (settings, water history, shelf, reminders). Nothing is uploaded; the only network call is the optional weather lookup (Open-Meteo, city name only).

## Known limits

- Windows notifications are read from Windows' notification database (no official API for unpackaged apps). A Windows update could change it; the island would just stop showing toasts.
- Windows' own volume flyout still appears alongside the island's (it can't be turned off cleanly on Windows 11). Turn the island's volume peek off in Settings if the double bothers you.
- Bluetooth battery shows only for devices that report it.
