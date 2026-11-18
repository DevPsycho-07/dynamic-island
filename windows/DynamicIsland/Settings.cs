using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;
using DynamicIsland.Pets;

namespace DynamicIsland
{
    public sealed class ReminderItem
    {
        public string Text { get; set; } = "";
        public DateTime Due { get; set; }
    }

    /// <summary>All settings and saved state, as one JSON file in %APPDATA%\DynamicIsland.</summary>
    public sealed class Settings
    {
        // Island
        public bool DockWhenIdle { get; set; } = true;
        public bool ShowMedia { get; set; } = true;
        public bool ShowVolume { get; set; } = true;
        public bool ShowBrightness { get; set; } = true;
        public bool ShowPower { get; set; } = true;
        public bool ShowNotifications { get; set; } = true;
        public bool ShowBluetooth { get; set; } = true;
        public bool ShowClipboardPeek { get; set; } = true;
        public bool ShowPrivacy { get; set; } = true;
        public bool HideInFullscreen { get; set; } = true;
        public bool ReduceMotion { get; set; }
        public bool PetDances { get; set; } = true;
        public string WeatherCity { get; set; } = "";
        public string WeatherPin { get; set; } = "";
        public string WeatherMode { get; set; } = "City";   // "City" or "PIN"
        [JsonIgnore] public string WeatherQuery => WeatherMode == "PIN" ? WeatherPin : WeatherCity;
        public bool ApiEnabled { get; set; } = true;

        // Hydration pet
        public WaterState Water { get; set; } = new();

        // Laptop ⇄ phone sync (through TwinDesk's shared folder)
        public bool SyncEnabled { get; set; } = true;
        public string SyncFolder { get; set; } = "";   // empty = %USERPROFILE%\MacShare\DynamicIsland

        // Shortcuts pinned to the Home tab's quick row (ids from IslandWindow.Shortcuts.cs; right-click a tile to pin)
        public List<string> QuickShortcuts { get; set; } = new() { "area", "window", "record", "emoji", "terminal", "calc" };

        // Shelf
        public List<string> ShelfFiles { get; set; } = new();

        // Reminders
        public List<ReminderItem> ReminderItems { get; set; } = new();

        // Focus (today)
        public string FocusDay { get; set; } = "";
        public int FocusSessions { get; set; }
        public int FocusMinutes { get; set; }

        static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DynamicIsland");
        static readonly string FilePath = Path.Combine(Dir, "settings.json");
        static readonly JsonSerializerOptions Json = new() { WriteIndented = true, Converters = { new JsonStringEnumConverter() } };

        public static Settings Load()
        {
            try
            {
                if (File.Exists(FilePath))
                    return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath), Json) ?? new Settings();
            }
            catch { /* old or corrupt file: start fresh */ }
            return new Settings();
        }

        public void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                var tmp = FilePath + ".tmp";
                File.WriteAllText(tmp, JsonSerializer.Serialize(this, Json));
                File.Move(tmp, FilePath, true);
            }
            catch { }
        }
    }
}
