using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using DynamicIsland.Pets;
using DynamicIsland.Services;

namespace DynamicIsland
{
    enum AlertKind { Water, Reminder, Ask }

    sealed class AlertInfo
    {
        public AlertKind Kind;
        public ReminderItem? Reminder;
        public AskRequest? Ask;
    }

    /// <summary>Hydration pet: logging, reactions, pace reminders, water tab, plus the shared alert pop-up.</summary>
    public partial class IslandWindow
    {
        Hydration _water = null!;
        DrinkType _drinkType = DrinkType.Water;
        AlertInfo? _alert;
        readonly Queue<(AlertInfo info, Action show)> _pendingAlerts = new();
        readonly DispatcherTimer _alertTimeout = new();
        readonly HashSet<PetView> _idlePets = new();

        PetView[] AllPets => new[] { PetMini, PetCard, PetBig, AlertPet, MsgPet };

        SyncService _sync = null!;
        int _lastIntake;
        public SyncService Sync => _sync;

        void InitWater()
        {
            _water = new Hydration(_s.Water);
            _sync = new SyncService(_s, _water, Dispatcher);
            _sync.Merged += () =>
            {
                int delta = _water.Intake - _lastIntake;
                _s.Save();
                RefreshWater(true);
                if (delta > 0) ShowMessage("", WaterB, "Synced from phone", $"+{delta} ml today  ·  {_water.AmountText}", "", 3);
            };
            _sync.Start();
            _alertTimeout.Tick += (_, _) =>
            {
                _alertTimeout.Stop();
                if (_alert?.Kind == AlertKind.Water) CloseAlert();
            };
            BuildWaterChips();
            RefreshWater(false);
        }

        string PetName => PetSkins.Create(_s.Water.Pet).Name;

        int CosmeticToShow
        {
            get
            {
                int c = Math.Clamp(_s.Water.Cosmetic, 0, 3);
                return _water.CosmeticsUnlocked[c] ? c : 0;
            }
        }

        // ---------- refresh ----------

        void RefreshWater(bool animate)
        {
            if (_water == null) return;
            _water.RollDay();
            var mood = _water.Mood;
            foreach (var pv in AllPets)
            {
                pv.SetPet(_s.Water.Pet, _water.Stage, CosmeticToShow);
                pv.SetMood(mood);
                pv.SetWater(_water.Fraction, animate);
            }

            DockFill.Width = Math.Max(0, 106 * _water.Fraction);
            DockFill.Visibility = _water.GoalMet ? Visibility.Collapsed : Visibility.Visible; // goal done: no line

            // Home card
            PetSpeech.Text = _water.StatusLine();
            WaterAmount.Text = _water.AmountText;
            SetWidth(HomeWaterFill, HomeWaterTrack, _water.Fraction);
            CardDrinkBtn.Content = $"+{_water.CupMl} ml";

            // Water tab
            var skin = PetBig.Skin;
            PetStageText.Text = $"{skin.StageNames[_water.Stage]} {skin.Name}";
            SetWidth(StageFill, StageTrack, _water.StageProgress);
            PetStageSub.Text = _water.BloomsToNextStage is int left
                ? $"{left} more goal day{(left == 1 ? "" : "s")} to {skin.StageNames[_water.Stage + 1]}"
                : "Fully grown";
            WaterBig.Text = _water.AmountText;
            WaterPace.Text = _water.StatusLine();
            SetWidth(WaterFill, WaterTrack, _water.Fraction);
            WaterPaceMark.Margin = new Thickness(Math.Max(0, WaterTrack.ActualWidth * Math.Min(1, _water.ExpectedNow / (double)_water.Goal) - 1), -2, 0, -2);
            var last = _water.TodayLog.Reverse().Take(3).Select(e => $"{(e.Type == DrinkType.Water ? "" : e.Type + " ")}{e.Ml} ml {e.Time:h:mm}");
            string caf = _water.CaffeineMg > 0 ? $"  ·  caffeine {_water.CaffeineMg} mg" : "";
            WaterLogText.Text = _water.TodayLog.Count == 0 ? "Nothing logged yet today" : string.Join("  ·  ", last) + caf;
            StreakText.Text = _water.Streak > 0 ? $"Streak {_water.Streak} day{(_water.Streak == 1 ? "" : "s")}" : "No streak yet";
            if (_water.Freezes > 0) StreakText.Text += $"  ·  {_water.Freezes} freeze{(_water.Freezes == 1 ? "" : "s")}";
            int got = Hydration.AllAchievements.Count(a => _water.HasAchievement(a.Id));
            var latest = Hydration.AllAchievements.LastOrDefault(a => _water.HasAchievement(a.Id));
            BadgeText.Text = $"{got}/{Hydration.AllAchievements.Length} badges" + (latest != null ? $" · {latest.Title}" : "");
            DrawWeek();
            UpdateTypeChips();
            _lastIntake = _water.Intake;
        }

        void DrawWeek()
        {
            WeekChart.Children.Clear();
            double w = WeekChart.ActualWidth > 0 ? WeekChart.ActualWidth : 144, top = 4, barArea = 50;
            var days = _water.LastDays().ToList();
            double slot = w / days.Count, bw = Math.Max(6, slot - 8);
            var dim = (Brush)FindResource("Dim");
            var goalLine = new Line { X1 = 0, X2 = w, Y1 = top, Y2 = top, Stroke = Frozen(Color.FromArgb(0x55, 255, 255, 255)), StrokeThickness = 1, StrokeDashArray = new DoubleCollection { 3, 3 } };
            WeekChart.Children.Add(goalLine);
            for (int i = 0; i < days.Count; i++)
            {
                var (label, ml, goal, today) = days[i];
                double f = goal > 0 ? Math.Min(1, ml / (double)goal) : 0;
                double h = Math.Max(3, barArea * f);
                var bar = new Rectangle
                {
                    Width = bw, Height = h, RadiusX = 3, RadiusY = 3,
                    Fill = f >= 1 ? WaterB : today ? Frozen(Color.FromArgb(0xCC, 0x4F, 0xC3, 0xF7)) : Frozen(Color.FromArgb(0x55, 0x4F, 0xC3, 0xF7)),
                };
                Canvas.SetLeft(bar, i * slot + (slot - bw) / 2);
                Canvas.SetTop(bar, top + barArea - h);
                WeekChart.Children.Add(bar);
                var t = new TextBlock { Text = label, FontSize = 9, Foreground = today ? Brushes.White : dim, Width = slot, TextAlignment = TextAlignment.Center };
                Canvas.SetLeft(t, i * slot);
                Canvas.SetTop(t, top + barArea + 3);
                WeekChart.Children.Add(t);
            }
        }

        void BuildWaterChips()
        {
            CupChips.Children.Clear();
            foreach (var cup in _s.Water.Cups.Take(5))
            {
                var b = new Button { Style = (Style)FindResource("PillButton"), Content = $"{cup.Name} {cup.Ml}", Tag = cup.Ml, Padding = new Thickness(9, 4, 9, 4), Margin = new Thickness(0, 0, 5, 5) };
                b.Click += (_, _) => LogDrink((int)b.Tag);
                CupChips.Children.Add(b);
            }
            TypeChips.Children.Clear();
            foreach (DrinkType t in Enum.GetValues<DrinkType>())
            {
                var b = new Button { Style = (Style)FindResource("PillButton"), Content = t.ToString(), Tag = t, Padding = new Thickness(8, 2, 8, 2), Margin = new Thickness(0, 0, 4, 4), FontSize = 11 };
                b.Click += (_, _) => { _drinkType = (DrinkType)b.Tag; UpdateTypeChips(); };
                TypeChips.Children.Add(b);
            }
            UpdateTypeChips();
        }

        void UpdateTypeChips()
        {
            foreach (var c in TypeChips.Children)
                if (c is Button b)
                {
                    bool on = (DrinkType)b.Tag == _drinkType;
                    b.Background = on ? WaterB : Frozen(Color.FromArgb(0x18, 255, 255, 255));
                    b.Foreground = on ? Frozen(Color.FromRgb(0x06, 0x20, 0x2E)) : (Brush)FindResource("Dim");
                }
        }

        // ---------- logging ----------

        void Drink_Click(object sender, RoutedEventArgs e) => LogDrink(_water.CupMl);
        void Undo_Click(object sender, RoutedEventArgs e) => UndoLast();

        /// <summary>Hotkey / tray: log one default cup without opening anything.</summary>
        public void LogWater() => LogDrink(_water.CupMl, DrinkType.Water);

        void LogDrink(int ml, DrinkType? type = null)
        {
            var t = type ?? _drinkType;
            var r = _water.Drink(ml, t);
            _s.Save();
            _sync.RequestPush();
            _drinkType = DrinkType.Water;
            RefreshWater(true);

            foreach (var pv in new[] { PetCard, PetBig, AlertPet, PetMini })
            {
                if (r.NewStage is int st) pv.Evolve(st, CosmeticToShow);
                else if (r.GoalJustMet) pv.Goal();
                else pv.Drink();
            }

            string thanks = _water.ThanksLine(r);
            if (r.NewStage is int ns) thanks = $"Evolved into a {PetBig.Skin.StageNames[ns]}!";
            PetSpeech.Text = thanks;

            if (_alert?.Kind == AlertKind.Water)
            {
                AlertLine.Text = thanks;
                _alertTimeout.Stop();
                var close = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.8) };
                close.Tick += (_, _) => { close.Stop(); if (_alert?.Kind == AlertKind.Water) CloseAlert(); };
                close.Start();
            }
            else if (!_expanded && !_hiddenForFullscreen)
            {
                string label = t == DrinkType.Water ? $"+{ml} ml" : $"+{ml} ml {t.ToString().ToLowerInvariant()}";
                ShowMessage("", WaterB, thanks, $"{label}  ·  {_water.AmountText}", "", 3.5, null, true, "Undo", UndoLast);
                MsgPet.Drink();
            }

            foreach (var a in r.NewAchievements.Take(1))
                QueueAfter(1.2, () => ShowMessage("", Yellow, $"Badge: {a.Title}", a.Description, "", 3));
            if (r.CosmeticUnlocked is int c)
                QueueAfter(2.4, () => ShowMessage("", Frozen(Color.FromRgb(0xB3, 0x88, 0xFF)), "New look unlocked", $"{PetBig.Skin.CosmeticNames[c]} for {PetName} — pick it in settings", "", 3.5));
        }

        void UndoLast()
        {
            var e = _water.Undo();
            if (e == null) return;
            _s.Save();
            _sync.RequestPush();
            RefreshWater(true);
            PetSpeech.Text = $"Removed {e.Ml} ml";
        }

        void QueueAfter(double seconds, Action a)
        {
            var t = new DispatcherTimer { Interval = TimeSpan.FromSeconds(seconds) };
            t.Tick += (_, _) => { t.Stop(); a(); };
            t.Start();
        }

        void Pet_Poke(object sender, MouseButtonEventArgs e)
        {
            if (sender is PetView pv) pv.Poke();
            string[] lines = { "Hey!", "Hehe, that tickles", "Sip with me?", "Stay hydrated!", "Boop!", $"I'm a {PetBig.Skin.StageNames[_water.Stage].ToLowerInvariant()}!" };
            PetSpeech.Text = lines[Rng.Next(lines.Length)];
            e.Handled = true;
        }

        void PetsLookAt(MouseEventArgs e)
        {
            if (!_expanded) return;
            foreach (var pv in new[] { PetCard, PetBig })
            {
                if (!pv.IsVisible) continue;
                var p = e.GetPosition(pv);
                pv.LookAt((p.X - pv.ActualWidth / 2) / (pv.ActualWidth * 1.2), (p.Y - pv.ActualHeight / 2) / (pv.ActualHeight * 1.2));
            }
        }

        void SetPetIdle(PetView pv, bool on)
        {
            if (on && _idlePets.Add(pv)) pv.StartIdle();
            else if (!on && _idlePets.Remove(pv)) { pv.StopIdle(); pv.StopDance(); }
        }

        // ---------- reminders (pace-based) ----------

        void CheckWater()
        {
            if (_water.RollDay()) _s.Save();
            RefreshWater(false);
            if (_hiddenForFullscreen || _expanded || _alert != null || _view is View.Notify or View.Message) return;
            if (!_water.ShouldRemind()) return;
            _water.MarkReminded();
            _s.Save();
            string cup = $"{_water.CupMl} ml";
            ShowAlert(new AlertInfo { Kind = AlertKind.Water }, $"{PetName} is thirsty", _water.ReminderLine(PetName), $"Drink {cup}", "Snooze 10 min", null, null);
            _alertTimeout.Interval = TimeSpan.FromSeconds(30);
            _alertTimeout.Start();
        }

        // ---------- shared alert pop-up ----------

        void ShowAlert(AlertInfo info, string title, string line, string btn1, string? btn2, string? glyph, Brush? accent, BitmapSource? logo = null)
        {
            void show()
            {
                _alert = info;
                AlertTitle.Text = title;
                AlertLine.Text = line;
                AlertBtn1.Content = btn1;
                AlertBtn2.Content = btn2 ?? "";
                AlertBtn2.Visibility = btn2 == null ? Visibility.Collapsed : Visibility.Visible;
                bool pet = glyph == null;
                AlertPet.Visibility = pet ? Visibility.Visible : Visibility.Collapsed;
                AlertGlyphBox.Visibility = pet ? Visibility.Collapsed : Visibility.Visible;
                if (!pet)
                {
                    var a = accent ?? Orange;
                    SetGlyph(AlertGlyph, glyph!);
                    AlertGlyph.Foreground = a;
                    AlertGlyph.Visibility = logo == null ? Visibility.Visible : Visibility.Collapsed;
                    AlertImage.Source = logo;
                    AlertImage.Visibility = logo != null ? Visibility.Visible : Visibility.Collapsed;
                    AlertGlyphBox.Background = logo != null ? Brushes.Transparent : Frozen(WithAlpha(a, 0x2E));
                }
                else AlertPet.SetMood(_water.Mood);
                _alertShownAt = DateTime.UtcNow;
                _transient.Stop();
                if (_expanded) { EndTyping(); _expanded = false; }
                _peeking = false;
                Show(View.Alert);
            }

            if (_alert != null)
            {
                // A permission question jumps the queue; a water nudge just waits for next time.
                if (info.Kind == AlertKind.Ask && _alert.Kind == AlertKind.Water) { _alert = null; show(); }
                else if (info.Kind != AlertKind.Water) _pendingAlerts.Enqueue((info, show));
                return;
            }
            show();
        }

        void CloseAlert()
        {
            _alert = null;
            _alertTimeout.Stop();
            if (_pendingAlerts.Count > 0) { _pendingAlerts.Dequeue().show(); return; }
            RevertToBase();
        }

        DateTime _alertShownAt;

        /// <summary>
        /// A new question can replace the one just answered in the same spot; ignore clicks for a moment so one
        /// click (or a touchpad tap that registers twice) can never answer two questions — e.g. approve twice.
        /// </summary>
        bool AlertJustShown => (DateTime.UtcNow - _alertShownAt).TotalMilliseconds < 400;

        void AlertBtn1_Click(object sender, RoutedEventArgs e)
        {
            var a = _alert;
            if (a == null || AlertJustShown) return;
            switch (a.Kind)
            {
                case AlertKind.Water: LogDrink(_water.CupMl, DrinkType.Water); break;
                case AlertKind.Reminder:
                    if (a.Reminder != null) { _s.ReminderItems.Remove(a.Reminder); _s.Save(); RefreshReminders(); }
                    CloseAlert(); break;
                case AlertKind.Ask:
                    if (a.Ask != null) IslandApi.Answer(a.Ask, a.Ask.Buttons[0]);
                    CloseAlert(); break;
            }
        }

        void AlertBtn2_Click(object sender, RoutedEventArgs e)
        {
            var a = _alert;
            if (a == null || AlertJustShown) return;
            switch (a.Kind)
            {
                case AlertKind.Water: _water.Snooze(10); _s.Save(); CloseAlert(); break;
                case AlertKind.Reminder:
                    if (a.Reminder != null) { a.Reminder.Due = DateTime.Now.AddMinutes(10); _s.Save(); RefreshReminders(); }
                    CloseAlert(); break;
                case AlertKind.Ask:
                    if (a.Ask != null) IslandApi.Answer(a.Ask, a.Ask.Buttons.Length > 1 ? a.Ask.Buttons[1] : null);
                    CloseAlert(); break;
            }
        }
    }
}
