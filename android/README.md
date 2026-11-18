# Dynamic Island — Android (v2)

Native Kotlin app (plain Views + Canvas, no Compose, no AndroidX). Package `com.rsv.dynamicisland`, minSdk 26, targetSdk 34.

## What it does

**Island** — black pill around the punch-hole camera. Tap = expand, long-press = open settings, swipe up / tap outside = collapse, swipe down = expand. Hidden in landscape.

- **Compact priority:** call › focus timer › navigation › music › progress (downloads etc.) › pet + "1.2/2.0 L".
- **Expanded pages** (swipe left/right, dots at the bottom): Live activity · Media · Pet · Timer. On the media page, swiping across the artwork/title row skips tracks; swiping lower down changes page. A horizontal swipe on the compact music pill also skips tracks.
- **Calls:** a green pill shows the caller and a call timer. Expanded, it has Answer / Speaker / End buttons, pulled from the dialer notification's actions.
- **Live activities:** any ongoing notification with progress shows an app icon + progress ring. Navigation apps (Google Maps, Waze, …) show the next-turn text instead.
- **Notification peeks:** you can copy OTP codes ("Copy 482913") and reply inline to messaging apps (WhatsApp, Telegram, SMS). If the ROM won't give the overlay a keyboard, the reply falls back to opening the chat.
- **Bluetooth:** a peek on connect/disconnect, with battery level if the device reports it.
- **Privacy dots:** green (camera) and orange (mic) next to the camera, plus a short "in use" peek.
- **Volume and ringer:** the volume becomes a level bar in the island, and Silent / Vibrate / Ring changes show as a bell peek.
- **Charging:** liquid-fill battery with percent on plug-in, a "Fully charged" peek at 100%, and a low-battery warning at 20%.
- **Focus timer:** 5/15/25/45 min with pause/resume and +1 min; tracks sessions and minutes today.

**Hydration pet** (`app/src/main/java/com/rsv/dynamicisland/pet/` — `PetEngine` + abstract `PetSkin`, 4 skins):

| Pet | Progress shown as | Stages (0 / 3 / 10 / 25 bloom days) | Cosmetics |
|---|---|---|---|
| Axolotl | tank water level | Hatchling › Juvenile › Adult › Elder | Bow, Crown, Flower crown |
| Droplet | water inside the glass drop | Droplet › Pup › Sprite › Spirit (own rain cloud) | Beanie, Scarf, Shades |
| Plant | soil moisture; droops when thirsty, never dies | Sprout › Sapling › Bush › Bloom | Blue glaze pot, Ribbon, Golden pot |
| Otter | water band it floats on | Pup › Kit › Adult › Elder | Beanie, Sunglasses, Bow tie |

Each pet has 5 moods (idle, happy, thirsty, very thirsty, sleeping), blinks, sways, and follows your touch with its eyes. Animations: drink, celebrate, goal hearts, evolve, poke (with a speech line), and dancing while music plays.

Cosmetics unlock at 3 blooms, a 7-day streak, and 20 blooms.

**Hydration rules** (same as the Windows app; all in `Water.kt`):

- **Bloom day:** you met your goal that day.
- **Streak:** counts days at ≥80% of your goal. Each 7-day streak earns a freeze (max 2), which is used automatically on a missed day.
- **Drink factors:** water 1.0, tea 0.9, coffee 0.8, juice 0.95, milk 0.95, soda 0.85. Caffeine is tracked.
- **Goal:** set manually, or auto = weight × 33 ml + 350 ml per 30 min of activity, +10% on a hot day.
- **Reminders are pace-based:** they compare what you've drunk with your goal × the fraction of your waking day that has passed. You're only reminded when you're ≥0.8 cup behind, never within 20 min of a drink, and never in sleep hours. After 2 ignored reminders the gap doubles (max 2 h). Snooze is 10 min.
- **Stats:** a 7-day chart with a goal line, plus 9 achievements.

## Build

Needs JDK 17+ and the Android SDK (`local.properties` → `sdk.dir=C\:/Android-sdk`).

```
gradlew.bat assembleRelease
```

The APK lands at `app/build/outputs/apk/release/app-release.apk`. It's signed with the local debug key, for sideloading.

Install: `adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk`
(one APK per CPU type: `arm64-v8a` for almost every modern phone, `armeabi-v7a` for old 32-bit ones, `x86_64` for emulators, `universal` for any)
Launch:  `adb shell am start -n com.rsv.dynamicisland/.MainActivity`

## Permissions (Set up card)

Every feature fails quietly if its permission is missing.

1. **Draw over apps** — required to show the island.
2. **Notification access** — music, calls, live activities, peeks, OTP, reply.
3. **Accessibility (recommended)** — puts the island above the status bar so it can be tapped. It reads nothing.
4. **Notifications** (Android 13+) — reminders + timer.
5. **Nearby devices** (Android 12+) — Bluetooth peeks.
6. **Unrestricted battery.**

**Android 13+ sideload:** if access is greyed out, go to App info › ⋮ › Allow restricted settings.

**Infinix / XOS:** turn on Auto-start for the app, set Battery to "No restrictions", and lock it in Recents.
