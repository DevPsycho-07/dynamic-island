# Third-party notices

Dynamic Island is MIT-licensed (see [LICENSE](LICENSE)). It uses the following third-party components and services,
each under its own licence or terms.

## Libraries — Windows app

| Component | Used for | Licence |
|---|---|---|
| [NAudio](https://github.com/naudio/NAudio) (`NAudio.Wasapi`, `NAudio.Core`) | Volume changes, output level for the visualizer | MIT |
| [Microsoft.Data.Sqlite.Core](https://github.com/dotnet/efcore) | Reading Windows' local notification database | MIT |
| [SQLitePCLRaw](https://github.com/ericsink/SQLitePCL.raw) (`bundle_winsqlite3`) | SQLite binding to Windows' built-in `winsqlite3` | Apache-2.0 |
| [System.Management](https://github.com/dotnet/runtime) | Screen-brightness events (WMI) | MIT |
| [C#/WinRT](https://github.com/microsoft/CsWinRT) + Windows SDK projections (`Microsoft.Windows.SDK.NET`) | Media controls, battery, Bluetooth APIs | MIT |
| .NET runtime and WPF | App framework | MIT |

## Libraries — Android app

| Component | Used for | Licence |
|---|---|---|
| Kotlin standard library | Language runtime | Apache-2.0 |
| Android Gradle Plugin, Gradle wrapper | Build | Apache-2.0 |

No other third-party libraries are bundled in the Android app.

## Online services (optional, used only when you set them up)

| Service | Used for | Terms |
|---|---|---|
| [Open-Meteo](https://open-meteo.com) | Weather and city geocoding | Free for non-commercial use; data under [CC BY 4.0](https://open-meteo.com/en/license) — *Weather data by Open-Meteo.com* |
| [OpenStreetMap Nominatim](https://nominatim.org) | PIN-code → location | Data © OpenStreetMap contributors, [ODbL 1.0](https://www.openstreetmap.org/copyright); [usage policy](https://operations.osmfoundation.org/policies/nominatim/) |
| India Post PIN directory ([api.postalpincode.in](http://www.postalpincode.in)) | PIN-code → area name | Public directory data |

## Trademarks and assets

- "Dynamic Island" is the name of a feature of Apple's iPhone. This project is independent and not affiliated with or endorsed by Apple.
- Claude and the Claude logo are trademarks of Anthropic. The logo shown for Claude Code sessions is **not bundled**; it is read at runtime from the Claude Code extension installed on the user's machine. This project is not affiliated with Anthropic.
- App icons shown in notification peeks belong to their respective owners and are read at runtime from the user's own system.
- TwinDesk is a separate project by its own authors; Dynamic Island only uses its user-facing shared-folder feature.
- The Dynamic Island app logo was designed for this project with Google Stitch and is covered by this project's MIT licence.
