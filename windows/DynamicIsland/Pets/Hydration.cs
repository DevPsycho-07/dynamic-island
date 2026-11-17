using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace DynamicIsland.Pets
{
    public enum DrinkType { Water, Tea, Coffee, Juice, Milk, Soda }

    /// <summary>One drink. Immutable once logged; undo adds its Id to the deleted set (so it merges across devices).</summary>
    public sealed class DrinkEntry
    {
        public string Id { get; set; } = Guid.NewGuid().ToString("N");
        public DateTimeOffset Time { get; set; }
        public int Ml { get; set; }
        public int Effective { get; set; }
        public DrinkType Type { get; set; }
        public string Device { get; set; } = "";
    }

    public sealed class DayRecord
    {
        public int Ml { get; set; }
        public int Goal { get; set; }
        public bool Frozen { get; set; }
    }

    public sealed class CupPreset
    {
        public string Name { get; set; } = "";
        public int Ml { get; set; }
    }

    public sealed record Achievement(string Id, string Title, string Description);

    public sealed class DrinkResult
    {
        public bool GoalJustMet;
        public int? NewStage;
        public int? CosmeticUnlocked;
        public List<Achievement> NewAchievements = new();
    }

    /// <summary>
    /// Hydration brain shared by every pet. Event-sourced so laptop and phone can merge:
    /// the drink log (+ deleted ids) and timestamped settings are the only synced truth;
    /// streaks, freezes, growth, badges and history are recomputed from them on every change.
    /// </summary>
    public sealed class Hydration
    {
        public static readonly IReadOnlyDictionary<DrinkType, double> Factor = new Dictionary<DrinkType, double>
        {
            [DrinkType.Water] = 1.0, [DrinkType.Tea] = 0.9, [DrinkType.Coffee] = 0.8,
            [DrinkType.Juice] = 0.95, [DrinkType.Milk] = 0.95, [DrinkType.Soda] = 0.85,
        };
        static readonly IReadOnlyDictionary<DrinkType, double> CaffeinePerMl = new Dictionary<DrinkType, double>
        {
            [DrinkType.Coffee] = 95 / 250.0, [DrinkType.Tea] = 45 / 250.0, [DrinkType.Soda] = 35 / 330.0,
        };

        public static readonly Achievement[] AllAchievements =
        {
            new("first", "First sip", "Log your first drink"),
            new("goal", "Goal getter", "Hit your daily goal"),
            new("streak3", "On a roll", "3-day streak"),
            new("streak7", "Hydro habit", "7-day streak"),
            new("streak30", "Water legend", "30-day streak"),
            new("early", "Early bird", "A glass before 9 am"),
            new("10l", "10 L club", "10 litres in total"),
            new("50l", "50 L club", "50 litres in total"),
            new("variety", "Mixologist", "3 drink types in one day"),
        };

        public static readonly int[] StageBlooms = { 0, 3, 10, 25 };

        readonly WaterState _w;
        readonly DateTime _startedUtc = DateTime.UtcNow;

        // derived (recomputed by Derive)
        List<DrinkEntry> _today = new();
        readonly Dictionary<string, int> _dayTotals = new();
        int _streak, _bestStreak, _freezes, _blooms, _totalMl;
        bool _todayCounted;
        readonly HashSet<string> _achievements = new();
        readonly HashSet<string> _frozenDays = new();

        public Hydration(WaterState w)
        {
            _w = w;
            _w.FirstUse ??= DateTime.Now;
            if (string.IsNullOrEmpty(_w.DeviceId)) _w.DeviceId = "pc-" + Guid.NewGuid().ToString("N")[..8];
            MigrateLegacy();
            StampSettings();
            RollDay();
            Derive();
        }

        public bool HotToday { get; set; }
        /// <summary>Raised after a sync merge brought in changes (UI should refresh).</summary>
        public event Action? ChangedBySync;

        static string Key(DateTime d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        static string Today => Key(DateTime.Now);
        static string DayOf(DrinkEntry e) => Key(e.Time.ToLocalTime().DateTime);

        IEnumerable<DrinkEntry> Live => _w.Log.Where(e => !_w.Deleted.Contains(e.Id));

        // ---------- goal ----------

        public int Goal
        {
            get
            {
                int g = _w.GoalMl;
                if (_w.AutoGoal && _w.WeightKg > 0)
                    g = (int)(_w.WeightKg * 33 + _w.ActivityMin / 30.0 * 350);
                if (HotToday && _w.AutoGoal) g = (int)(g * 1.1);
                return Math.Max(500, (int)Math.Round(g / 50.0) * 50);
            }
        }

        int GoalFor(string day) => day == Today ? Goal : _w.DayGoals.TryGetValue(day, out var g) && g > 0 ? g : Goal;

        public int CupMl => Math.Max(50, _w.CupMl);
        public int Intake => _today.Sum(e => e.Effective);
        public double Fraction => Math.Min(1.0, Intake / (double)Goal);
        public bool GoalMet => Intake >= Goal;
        public int CaffeineMg => (int)_today.Where(e => CaffeinePerMl.ContainsKey(e.Type)).Sum(e => e.Ml * CaffeinePerMl[e.Type]);
        public IReadOnlyList<DrinkEntry> TodayLog => _today;
        public int Streak => _streak + (_todayCounted ? 1 : 0);
        public int BestStreak => Math.Max(_bestStreak, Streak);
        public int Freezes => _freezes;
        public int Blooms => _blooms;
        public int TotalMl => _totalMl;

        public int Stage => StageBlooms.Count(b => _blooms >= b) - 1;
        public int? BloomsToNextStage => Stage >= 3 ? null : StageBlooms[Stage + 1] - _blooms;
        public double StageProgress => Stage >= 3 ? 1 : (_blooms - StageBlooms[Stage]) / (double)(StageBlooms[Stage + 1] - StageBlooms[Stage]);

        // Unlocks and streak badges use completed days only (not today) — identical rule on Android.
        public bool[] CosmeticsUnlocked => new[] { true, _blooms >= 3, _bestStreak >= 7, _blooms >= 20 };
        public static readonly string[] CosmeticHints = { "", "Unlocks at 3 goal days", "Unlocks with a 7-day streak", "Unlocks at 20 goal days" };
        public bool HasAchievement(string id) => _achievements.Contains(id);

        // ---------- derive everything from the event log ----------

        void Derive()
        {
            var live = Live.ToList();
            _dayTotals.Clear();
            foreach (var g in live.GroupBy(DayOf)) _dayTotals[g.Key] = g.Sum(e => e.Effective);
            _today = live.Where(e => DayOf(e) == Today).OrderBy(e => e.Time).ToList();
            _totalMl = live.Sum(e => e.Effective);

            _streak = 0; _bestStreak = 0; _freezes = 0; _blooms = 0;
            _frozenDays.Clear();
            var first = live.Count > 0 ? live.Min(e => e.Time.ToLocalTime().Date) : DateTime.Today;
            for (var d = first; d < DateTime.Today; d = d.AddDays(1))
            {
                string k = Key(d);
                int ml = _dayTotals.GetValueOrDefault(k), goal = GoalFor(k);
                if (ml >= goal) _blooms++;
                if (ml >= goal * 0.8)
                {
                    _streak++;
                    _bestStreak = Math.Max(_bestStreak, _streak);
                    if (_streak % 7 == 0 && _freezes < 2) _freezes++;
                }
                else if (_freezes > 0) { _freezes--; _frozenDays.Add(k); }
                else _streak = 0;
            }
            int todayMl = _dayTotals.GetValueOrDefault(Today);
            if (todayMl >= Goal) _blooms++;
            _todayCounted = todayMl >= Goal * 0.8;

            _achievements.Clear();
            if (live.Count > 0) _achievements.Add("first");
            if (_blooms > 0) _achievements.Add("goal");
            if (_bestStreak >= 3) _achievements.Add("streak3");
            if (_bestStreak >= 7) _achievements.Add("streak7");
            if (_bestStreak >= 30) _achievements.Add("streak30");
            if (live.Any(e => e.Time.ToLocalTime().Hour < 9)) _achievements.Add("early");
            if (_totalMl >= 10_000) _achievements.Add("10l");
            if (_totalMl >= 50_000) _achievements.Add("50l");
            if (live.GroupBy(DayOf).Any(g => g.Select(e => e.Type).Distinct().Count() >= 3)) _achievements.Add("variety");
        }

        /// <summary>New day: reset the local reminder state. Returns true if the day rolled over.</summary>
        public bool RollDay()
        {
            if (_w.Day == Today) return false;
            _w.Day = Today;
            _w.LastAlertUtc = null;
            _w.SnoozeUntilUtc = null;
            _w.IgnoredAlerts = 0;
            // Forget raw events older than ~13 months (keeps the file small).
            var cutoff = DateTimeOffset.Now.AddDays(-400);
            _w.Log.RemoveAll(e => e.Time < cutoff);
            Derive();
            return true;
        }

        // ---------- logging ----------

        public DrinkResult Drink(int ml, DrinkType type = DrinkType.Water)
        {
            RollDay();
            var r = new DrinkResult();
            int stageBefore = Stage;
            var unlockedBefore = CosmeticsUnlocked;
            var achBefore = new HashSet<string>(_achievements);
            bool wasMet = GoalMet;

            _w.Log.Add(new DrinkEntry
            {
                Time = DateTimeOffset.Now, Ml = ml, Effective = (int)Math.Round(ml * Factor[type]), Type = type, Device = _w.DeviceId,
            });
            _w.DayGoals[Today] = Goal;
            _w.LastDrinkUtc = DateTime.UtcNow;
            _w.LastAlertUtc = null;
            _w.SnoozeUntilUtc = null;
            _w.IgnoredAlerts = 0;
            Derive();

            r.GoalJustMet = !wasMet && GoalMet;
            if (Stage != stageBefore) r.NewStage = Stage;
            var unlockedAfter = CosmeticsUnlocked;
            for (int i = 1; i < 4; i++) if (unlockedAfter[i] && !unlockedBefore[i]) r.CosmeticUnlocked = i;
            foreach (var a in AllAchievements) if (_achievements.Contains(a.Id) && !achBefore.Contains(a.Id)) r.NewAchievements.Add(a);
            return r;
        }

        /// <summary>Undo the most recent drink today (from any device).</summary>
        public DrinkEntry? Undo()
        {
            var last = _today.OrderBy(e => e.Time).LastOrDefault();
            if (last == null) return null;
            _w.Deleted.Add(last.Id);
            Derive();
            return last;
        }

        // ---------- pace + reminders (local only, never synced) ----------

        public bool Awake
        {
            get
            {
                int h = DateTime.Now.Hour, a = _w.WakeHour, b = _w.SleepHour;
                if (a == b) return true;
                return a < b ? (h >= a && h < b) : (h >= a || h < b);
            }
        }

        public int ExpectedNow
        {
            get
            {
                var now = DateTime.Now;
                var wake = DateTime.Today.AddHours(_w.WakeHour);
                var sleep = DateTime.Today.AddHours(_w.SleepHour <= _w.WakeHour ? _w.SleepHour + 24 : _w.SleepHour);
                // On the very first day, count pace from when the app was first opened, not from wake-up.
                var start = _w.FirstUse is DateTime fu && fu.Date == DateTime.Today && fu > wake ? fu : wake;
                double f = Math.Clamp((now - start).TotalMinutes / Math.Max(60, (sleep - wake).TotalMinutes), 0, 1);
                return (int)(Goal * f);
            }
        }

        public int BehindMl => Math.Max(0, ExpectedNow - Intake);

        public double MinutesSinceDrink
        {
            get
            {
                var last = _today.Count > 0 ? _today.Max(e => e.Time).UtcDateTime : DateTime.MinValue;
                var wake = DateTime.Today.AddHours(_w.WakeHour).ToUniversalTime();
                if (wake > last && wake <= DateTime.UtcNow) last = wake;
                if (_startedUtc > last) last = _startedUtc;
                return (DateTime.UtcNow - last).TotalMinutes;
            }
        }

        public PetMood Mood
        {
            get
            {
                if (!Awake) return PetMood.Sleeping;
                if (GoalMet || MinutesSinceDrink < 15 || BehindMl <= 0) return PetMood.Happy;
                if (BehindMl < CupMl) return PetMood.Idle;
                return BehindMl < CupMl * 2.5 ? PetMood.Thirsty : PetMood.VeryThirsty;
            }
        }

        public bool ShouldRemind()
        {
            RollDay();
            if (!_w.Reminders || !Awake || GoalMet) return false;
            if (_w.SnoozeUntilUtc is DateTime z && DateTime.UtcNow < z) return false;
            if (MinutesSinceDrink < 20) return false;
            if (BehindMl < CupMl * 0.8) return false;
            double gap = 30 * Math.Pow(2, Math.Min(_w.IgnoredAlerts, 2));
            return _w.LastAlertUtc is not DateTime la || (DateTime.UtcNow - la).TotalMinutes >= gap;
        }

        public void MarkReminded() { _w.LastAlertUtc = DateTime.UtcNow; _w.IgnoredAlerts++; }
        public void Snooze(int minutes = 10) { _w.SnoozeUntilUtc = DateTime.UtcNow.AddMinutes(minutes); _w.LastAlertUtc = null; _w.IgnoredAlerts = 0; }

        // ---------- stats ----------

        public IEnumerable<(string Label, int Ml, int Goal, bool Today)> LastDays(int n = 7)
        {
            for (int i = n - 1; i >= 0; i--)
            {
                var d = DateTime.Today.AddDays(-i);
                string k = Key(d);
                yield return (d.ToString("ddd", CultureInfo.InvariantCulture)[..2], _dayTotals.GetValueOrDefault(k), GoalFor(k), i == 0);
            }
        }

        // ---------- copy ----------

        static readonly Random Rng = new();

        public string ReminderLine(string pet)
        {
            int behind = BehindMl;
            string[] lines = Mood == PetMood.VeryThirsty
                ? new[] { $"You're {behind} ml behind today.", $"{pet} is parched... one glass?", "Water break? Pretty please." }
                : new[] { $"A little behind pace ({behind} ml).", "Sip break? Just one glass.", $"{pet} could use a drink." };
            return lines[Rng.Next(lines.Length)];
        }

        public string ThanksLine(DrinkResult r)
        {
            if (r.NewStage != null) return "Evolved!";
            if (r.GoalJustMet) return Streak > 1 ? $"Goal done! Streak {Streak}" : "Daily goal reached!";
            string[] lines = { "Ahh, refreshing!", "Glug glug!", "That hit the spot!", "Hydrated and happy!" };
            return lines[Rng.Next(lines.Length)];
        }

        public string StatusLine()
        {
            if (!Awake) return "Sleeping... see you in the morning";
            if (GoalMet) return "Goal done for today!";
            int behind = BehindMl;
            if (behind <= 0) return $"On pace · {(int)(Fraction * 100)}% done";
            return $"{behind} ml behind pace";
        }

        public string AmountText => $"{Intake / 1000.0:0.0#} / {Goal / 1000.0:0.0#} L";

        // ================= sync =================

        static readonly string[] SyncedKeys = { "GoalMl", "AutoGoal", "WeightKg", "ActivityMin", "CupMl", "WakeHour", "SleepHour", "Reminders", "Pet", "Cosmetic", "Cups" };
        // camelCase fields on the wire, read case-insensitively; settings KEYS stay PascalCase (dictionary keys aren't renamed).
        static readonly JsonSerializerOptions SyncJson = new()
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            Converters = { new JsonStringEnumConverter() },
        };

        string SettingValue(string key) => SettingValue(_w, key);

        static string SettingValue(WaterState w, string key) => key switch
        {
            "GoalMl" => w.GoalMl.ToString(CultureInfo.InvariantCulture),
            "AutoGoal" => w.AutoGoal ? "1" : "0",
            "WeightKg" => w.WeightKg.ToString(CultureInfo.InvariantCulture),
            "ActivityMin" => w.ActivityMin.ToString(CultureInfo.InvariantCulture),
            "CupMl" => w.CupMl.ToString(CultureInfo.InvariantCulture),
            "WakeHour" => w.WakeHour.ToString(CultureInfo.InvariantCulture),
            "SleepHour" => w.SleepHour.ToString(CultureInfo.InvariantCulture),
            "Reminders" => w.Reminders ? "1" : "0",
            "Pet" => w.Pet.ToString(),
            "Cosmetic" => w.Cosmetic.ToString(CultureInfo.InvariantCulture),
            "Cups" => JsonSerializer.Serialize(w.Cups, SyncJson),
            _ => "",
        };

        static readonly WaterState Defaults = new();

        void ApplySetting(string key, string v)
        {
            int I() => int.TryParse(v, NumberStyles.Integer, CultureInfo.InvariantCulture, out var n) ? n : 0;
            switch (key)
            {
                case "GoalMl": _w.GoalMl = Math.Clamp(I(), 500, 10000); break;
                case "AutoGoal": _w.AutoGoal = v == "1"; break;
                case "WeightKg": _w.WeightKg = Math.Clamp(I(), 0, 300); break;
                case "ActivityMin": _w.ActivityMin = Math.Clamp(I(), 0, 600); break;
                case "CupMl": _w.CupMl = Math.Clamp(I(), 50, 2000); break;
                case "WakeHour": _w.WakeHour = Math.Clamp(I(), 0, 23); break;
                case "SleepHour": _w.SleepHour = Math.Clamp(I(), 0, 23); break;
                case "Reminders": _w.Reminders = v == "1"; break;
                case "Pet": if (Enum.TryParse<PetKind>(v, out var p)) _w.Pet = p; break;
                case "Cosmetic": _w.Cosmetic = Math.Clamp(I(), 0, 3); break;
                case "Cups":
                    try { var c = JsonSerializer.Deserialize<List<CupPreset>>(v, SyncJson); if (c is { Count: > 0 }) _w.Cups = c; } catch { }
                    break;
            }
            _w.SettingValues[key] = v;
        }

        /// <summary>
        /// Timestamp synced settings so the newest edit wins. A value still at its default keeps timestamp 0
        /// (never overrides the other device); a value you changed — including before sync existed — gets "now".
        /// </summary>
        public void StampSettings()
        {
            long now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            foreach (var k in SyncedKeys)
            {
                string v = SettingValue(k);
                bool isDefault = v == SettingValue(Defaults, k);
                bool changed = !_w.SettingValues.TryGetValue(k, out var old) || old != v;
                long ts = _w.SettingStamps.GetValueOrDefault(k);
                if (changed) { _w.SettingValues[k] = v; _w.SettingStamps[k] = isDefault && old == null ? 0 : now; }
                else if (ts == 0 && !isDefault) _w.SettingStamps[k] = now;   // customised before sync existed
            }
        }

        /// <summary>Everything the other device needs, as JSON (a few KB).</summary>
        public string ExportSync()
        {
            StampSettings();
            var doc = new WaterSyncDoc
            {
                Device = _w.DeviceId,
                Drinks = _w.Log,
                Deleted = _w.Deleted.ToList(),
                Settings = SyncedKeys.ToDictionary(k => k, k => new StampedValue { V = SettingValue(k), Ts = _w.SettingStamps.GetValueOrDefault(k) }),
                DayGoals = _w.DayGoals,
            };
            return JsonSerializer.Serialize(doc, SyncJson);
        }

        /// <summary>Merge the other device's state. Drinks = union minus deletions; settings = newest edit wins.</summary>
        public bool ImportSync(string json)
        {
            WaterSyncDoc? doc;
            try { doc = JsonSerializer.Deserialize<WaterSyncDoc>(json, SyncJson); } catch { return false; }
            if (doc == null || doc.Schema != 1 || doc.Device == _w.DeviceId) return false;
            StampSettings();
            bool changed = false;

            var known = new HashSet<string>(_w.Log.Select(e => e.Id));
            foreach (var d in doc.Drinks ?? new())
                if (!string.IsNullOrEmpty(d.Id) && d.Ml > 0 && d.Ml <= 5000 && known.Add(d.Id)) { _w.Log.Add(d); changed = true; }
            foreach (var id in doc.Deleted ?? new())
                if (_w.Deleted.Add(id)) changed = true;
            foreach (var (k, sv) in doc.Settings ?? new())
            {
                if (!SyncedKeys.Contains(k) || sv == null) continue;
                if (sv.Ts > _w.SettingStamps.GetValueOrDefault(k) && sv.V != SettingValue(k))
                {
                    ApplySetting(k, sv.V);
                    _w.SettingStamps[k] = sv.Ts;
                    changed = true;
                }
            }
            foreach (var (day, g) in doc.DayGoals ?? new())
                if (day != Today && g > 0 && !_w.DayGoals.ContainsKey(day)) { _w.DayGoals[day] = g; changed = true; }

            if (changed) { Derive(); ChangedBySync?.Invoke(); }
            return changed;
        }

        // ---------- one-time upgrade from the pre-sync format ----------

        void MigrateLegacy()
        {
            if (_w.TodayLog is { Count: > 0 } legacy && _w.Log.Count == 0)
            {
                foreach (var e in legacy)
                {
                    if (string.IsNullOrEmpty(e.Id)) e.Id = Guid.NewGuid().ToString("N");
                    if (string.IsNullOrEmpty(e.Device)) e.Device = _w.DeviceId;
                    _w.Log.Add(e);
                }
            }
            _w.TodayLog = null;
        }
    }

    public sealed class StampedValue
    {
        public string V { get; set; } = "";
        public long Ts { get; set; }
    }

    /// <summary>The wire format both apps exchange (Windows and Android must match).</summary>
    public sealed class WaterSyncDoc
    {
        public int Schema { get; set; } = 1;
        public string Device { get; set; } = "";
        public List<DrinkEntry> Drinks { get; set; } = new();
        public List<string> Deleted { get; set; } = new();
        public Dictionary<string, StampedValue> Settings { get; set; } = new();
        public Dictionary<string, int> DayGoals { get; set; } = new();
    }

    /// <summary>Everything hydration-related that's saved to disk.</summary>
    public sealed class WaterState
    {
        // settings (synced)
        public PetKind Pet { get; set; } = PetKind.Axolotl;
        public int Cosmetic { get; set; }
        public bool AutoGoal { get; set; }
        public int WeightKg { get; set; }
        public int ActivityMin { get; set; } = 30;
        public int GoalMl { get; set; } = 2000;
        public int CupMl { get; set; } = 250;
        public List<CupPreset> Cups { get; set; } = new()
        {
            new() { Name = "Sip", Ml = 100 }, new() { Name = "Glass", Ml = 250 },
            new() { Name = "Mug", Ml = 300 }, new() { Name = "Bottle", Ml = 500 },
        };
        public int WakeHour { get; set; } = 8;
        public int SleepHour { get; set; } = 23;
        public bool Reminders { get; set; } = true;

        // synced data
        public List<DrinkEntry> Log { get; set; } = new();
        public HashSet<string> Deleted { get; set; } = new();
        public Dictionary<string, int> DayGoals { get; set; } = new();
        public Dictionary<string, long> SettingStamps { get; set; } = new();
        public Dictionary<string, string> SettingValues { get; set; } = new();

        // local only
        public string DeviceId { get; set; } = "";
        public DateTime? FirstUse { get; set; }
        public string Day { get; set; } = "";
        public DateTime? LastDrinkUtc { get; set; }
        public DateTime? SnoozeUntilUtc { get; set; }
        public DateTime? LastAlertUtc { get; set; }
        public int IgnoredAlerts { get; set; }

        /// <summary>Pre-sync format; migrated into Log on first load.</summary>
        public List<DrinkEntry>? TodayLog { get; set; }
    }
}
