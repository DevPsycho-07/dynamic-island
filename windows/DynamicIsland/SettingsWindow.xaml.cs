using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using DynamicIsland.Pets;
using DynamicIsland.Services;

namespace DynamicIsland
{
    /// <summary>Fully custom settings: borderless glass window, sidebar tabs, switches, steppers, pet picker.</summary>
    public partial class SettingsWindow : Window
    {
        readonly Settings _s;
        readonly IslandWindow _island;
        static readonly string DataDir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DynamicIsland");
        static readonly string HookDir = System.IO.Path.Combine(DataDir, "hooks");

        public SettingsWindow(Settings s, IslandWindow island)
        {
            InitializeComponent();
            _s = s;
            _island = island;
            VersionText.Text = "v" + (Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "2.0.0");
            Loaded += (_, _) =>
            {
                HeaderPet.SetPet(_s.Water.Pet, _island.Water.Stage, 0);
                HeaderPet.SetMood(PetMood.Happy);
                HeaderPet.SetWater(_island.Water.Fraction, false);
                HeaderPet.StartIdle();
                ShowPage("General");
            };
            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
            SourceInitialized += (_, _) => Native.ApplyWin11Frame(new System.Windows.Interop.WindowInteropHelper(this).Handle);
        }

        void Drag(object sender, MouseButtonEventArgs e) { if (e.ButtonState == MouseButtonState.Pressed) try { DragMove(); } catch { } }
        void Close_Click(object sender, RoutedEventArgs e) => Close();

        void Nav_Checked(object sender, RoutedEventArgs e)
        {
            if (!IsLoaded) return;
            ShowPage(((RadioButton)sender).Content?.ToString() ?? "General");
        }

        void Apply() { _s.Save(); _island.OnSettingsChanged(); }

        void ShowPage(string name)
        {
            Page.Children.Clear();
            Scroller.ScrollToTop();
            PageTitle.Text = name;
            switch (name)
            {
                case "General": BuildGeneral(); break;
                case "Island": BuildIsland(); break;
                case "Water & pet": BuildPet(); break;
                case "Developer": BuildDev(); break;
                case "Phone sync": BuildSync(); break;
                default: BuildAbout(); break;
            }
        }

        // ================= pages =================

        void BuildGeneral()
        {
            Section("Startup");
            Card(
                Toggle("Start with Windows", "Launch the island when you sign in", App.StartWithWindows, v => { try { App.StartWithWindows = v; } catch { } }),
                Toggle("Dock when idle", "Shrinks to a thin bar at the top edge; hover to peek", _s.DockWhenIdle, v => { _s.DockWhenIdle = v; Apply(); }));
            Section("Behaviour");
            Card(
                Toggle("Hide during fullscreen", "Games, videos and presentations stay clean", _s.HideInFullscreen, v => { _s.HideInFullscreen = v; Apply(); }),
                Toggle("Respect Do not disturb",
                    "While Windows' Do not disturb or a focus session is on: no pop-ups or sounds. Permission prompts wait quietly — click the island to answer. Your own timers and reminders still ring." +
                    (Native.IsDoNotDisturbOn() ? " (Do not disturb is on right now.)" : ""),
                    _s.RespectDnd, v => { _s.RespectDnd = v; Apply(); }),
                Toggle("Reduce motion", "No bounce, shorter animations", _s.ReduceMotion, v => { _s.ReduceMotion = v; Apply(); }));
            Section("Weather");
            bool pin = _s.WeatherMode == "PIN";
            Card(
                Segmented("Location by", "Shown in the header, peek and clock. Free, no account", new[] { "City", "PIN code" }, pin ? "PIN code" : "City",
                          v => { _s.WeatherMode = v == "PIN code" ? "PIN" : "City"; Apply(); ShowPage("General"); }),
                pin
                    ? TextField("PIN code", "6-digit Indian PIN code", _s.WeatherPin, v => { _s.WeatherPin = new string(v.Where(char.IsDigit).ToArray()); Apply(); })
                    : TextField("City", null, _s.WeatherCity, v => { _s.WeatherCity = v.Trim(); Apply(); }));
        }

        void BuildIsland()
        {
            Section("Live activities");
            Card(
                Toggle("Now playing", "Album art, visualizer and controls", _s.ShowMedia, v => { _s.ShowMedia = v; Apply(); }),
                Toggle("Pet dances to music", "Your pet bops along on the Home tab", _s.PetDances, v => { _s.PetDances = v; Apply(); }),
                Toggle("Notifications", "Windows notifications peek into the island. Tip: turn off Windows banners per app to avoid doubles", _s.ShowNotifications, v => { _s.ShowNotifications = v; Apply(); }),
                Toggle("Bluetooth", "Headphones and devices connecting / disconnecting", _s.ShowBluetooth, v => { _s.ShowBluetooth = v; Apply(); }));
            Section("System");
            Card(
                Toggle("Volume", "Island turns into a slider when volume changes", _s.ShowVolume, v => { _s.ShowVolume = v; Apply(); }),
                Toggle("Brightness", "Same for screen brightness", _s.ShowBrightness, v => { _s.ShowBrightness = v; Apply(); }),
                Toggle("Battery and charging", "Plug in, unplug, low, full and energy saver", _s.ShowPower, v => { _s.ShowPower = v; Apply(); }),
                Toggle("Microphone and camera", "Orange / green dot while an app is using them", _s.ShowPrivacy, v => { _s.ShowPrivacy = v; Apply(); }),
                Toggle("Clipboard peek", "Shows 'Copied' with a preview. Passwords are hidden; history stays in memory only", _s.ShowClipboardPeek, v => { _s.ShowClipboardPeek = v; Apply(); }));
        }

        void BuildPet()
        {
            var w = _s.Water;
            var hyd = _island.Water;

            Section("Choose your pet");
            var pets = new WrapPanel();
            foreach (var kind in PetSkins.All)
            {
                bool sel = kind == w.Pet;
                var pv = new PetView { Width = 86, Height = 86 };
                var skin = PetSkins.Create(kind);
                var card = new Border
                {
                    Width = 118, Height = 146, CornerRadius = new CornerRadius(20), Margin = new Thickness(0, 0, 8, 8), Cursor = Cursors.Hand,
                    Background = (Brush)FindResource(sel ? "CardHover" : "Card"),
                    BorderBrush = sel ? (Brush)FindResource("Water") : Brushes.Transparent, BorderThickness = new Thickness(2),
                };
                var st = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                st.Children.Add(pv);
                st.Children.Add(new TextBlock { Text = skin.Name, FontSize = 13, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 0) });
                st.Children.Add(new TextBlock { Text = skin.StageNames[hyd.Stage], FontSize = 11, Foreground = (Brush)FindResource("Dim"), HorizontalAlignment = HorizontalAlignment.Center });
                card.Child = st;
                pv.Loaded += (_, _) => { pv.SetPet(kind, hyd.Stage, 0); pv.SetMood(PetMood.Happy); pv.SetWater(Math.Max(0.55, hyd.Fraction), false); };
                card.MouseEnter += (_, _) => { pv.StartIdle(); pv.Skin.PlayPoke(); };
                card.MouseLeave += (_, _) => pv.StopIdle();
                card.MouseLeftButtonUp += (_, _) =>
                {
                    w.Pet = kind; w.Cosmetic = 0; Apply();
                    HeaderPet.SetPet(kind, hyd.Stage, 0);
                    ShowPage("Water & pet");
                };
                pets.Children.Add(card);
            }
            Page.Children.Add(pets);

            Section("Look");
            var cos = new WrapPanel();
            var names = PetSkins.Create(w.Pet).CosmeticNames;
            var unlocked = hyd.CosmeticsUnlocked;
            for (int i = 0; i < 4; i++)
            {
                int idx = i;
                bool on = w.Cosmetic == i, open = unlocked[i];
                var chip = new Border
                {
                    CornerRadius = new CornerRadius(14), Padding = new Thickness(14, 7, 14, 7), Margin = new Thickness(0, 0, 8, 8),
                    Background = on ? (Brush)FindResource("Water") : (Brush)FindResource("Card"), Cursor = open ? Cursors.Hand : Cursors.Arrow,
                    ToolTip = open ? null : Hydration.CosmeticHints[i],
                };
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                if (!open) sp.Children.Add(new TextBlock { Text = "", FontFamily = (FontFamily)FindResource("IconFont"), FontSize = 11, Foreground = (Brush)FindResource("Dim"), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) });
                sp.Children.Add(new TextBlock { Text = names[i], FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = on ? Brush(0x06, 0x20, 0x2E) : open ? Brushes.White : (Brush)FindResource("Dim") });
                chip.Child = sp;
                if (open) chip.MouseLeftButtonUp += (_, _) => { w.Cosmetic = idx; Apply(); ShowPage("Water & pet"); };
                cos.Children.Add(chip);
            }
            Page.Children.Add(cos);

            Section("Progress");
            var stats = new UniformGrid4();
            stats.Add("Streak", $"{hyd.Streak} d", hyd.Freezes > 0 ? $"{hyd.Freezes} freeze{(hyd.Freezes == 1 ? "" : "s")}" : "80% counts");
            stats.Add("Best streak", $"{hyd.BestStreak} d", "");
            stats.Add("Goal days", $"{hyd.Blooms}", hyd.BloomsToNextStage is int left ? $"{left} to next stage" : "Fully grown");
            stats.Add("All time", $"{hyd.TotalMl / 1000.0:0.#} L", "");
            Page.Children.Add(stats.Root);
            var badges = new WrapPanel { Margin = new Thickness(0, 4, 0, 0) };
            foreach (var a in Hydration.AllAchievements)
            {
                bool got = hyd.HasAchievement(a.Id);
                var b = new Border { CornerRadius = new CornerRadius(12), Padding = new Thickness(10, 5, 10, 5), Margin = new Thickness(0, 0, 6, 6), Background = (Brush)FindResource("Card"), ToolTip = a.Description, Opacity = got ? 1 : 0.45 };
                var sp = new StackPanel { Orientation = Orientation.Horizontal };
                sp.Children.Add(new TextBlock { Text = got ? "" : "", FontFamily = (FontFamily)FindResource("IconFont"), FontSize = 11, Foreground = got ? Brush(0xFF, 0xD6, 0x0A) : (Brush)FindResource("Dim"), VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) });
                sp.Children.Add(new TextBlock { Text = a.Title, FontSize = 11 });
                b.Child = sp;
                badges.Children.Add(b);
            }
            Page.Children.Add(badges);

            Section("Daily goal");
            var todayGoal = new TextBlock { Text = $"{hyd.Goal} ml", FontSize = 12, Foreground = (Brush)FindResource("Dim") };
            void goalChanged() { Apply(); todayGoal.Text = $"{hyd.Goal} ml"; }
            Card(
                Toggle("Personal goal", "Calculated from your weight and activity (+10% on hot days). Not medical advice", w.AutoGoal, v => { w.AutoGoal = v; Apply(); ShowPage("Water & pet"); }),
                w.AutoGoal
                    ? Stepper("Weight", "Used only for the goal, stays on this PC", w.WeightKg, 30, 200, 1, "kg", v => { w.WeightKg = v; goalChanged(); })
                    : Stepper("Goal", null, w.GoalMl, 500, 6000, 100, "ml", v => { w.GoalMl = v; goalChanged(); }),
                w.AutoGoal ? Stepper("Exercise per day", null, w.ActivityMin, 0, 300, 10, "min", v => { w.ActivityMin = v; goalChanged(); }) : null,
                Row("Today's goal", null, todayGoal));

            Section("Reminders");
            Card(
                Toggle("Remind me", "Only when you're behind pace, never right after a drink, and backs off if you ignore it", w.Reminders, v => { w.Reminders = v; Apply(); }),
                Stepper("Wake up", "Reminders and pace start here", w.WakeHour, 0, 23, 1, ":00", v => { w.WakeHour = v; Apply(); }),
                Stepper("Bedtime", "Your pet sleeps, no reminders", w.SleepHour, 0, 23, 1, ":00", v => { w.SleepHour = v; Apply(); }));

            Section("Cups");
            var rows = new UIElement?[w.Cups.Count + 1];
            rows[0] = Stepper("Default cup", "Used by the Drink button, Ctrl+Alt+W and reminders", w.CupMl, 50, 2000, 50, "ml", v => { w.CupMl = v; Apply(); });
            for (int i = 0; i < w.Cups.Count; i++)
            {
                var cup = w.Cups[i];
                rows[i + 1] = Stepper(cup.Name, "Quick-add chip on the Water tab", cup.Ml, 50, 2000, 50, "ml", v => { cup.Ml = v; Apply(); });
            }
            Card(rows);
        }

        void BuildDev()
        {
            bool claude = ClaudeHooksInstalled(), ps = ProfileHookInstalled();
            Section("Claude Code");
            Card(
                Info("What you get", "Live 'Claude is working' pill, 'needs you' and 'finished' peeks, and Allow / Deny for permission prompts right in the island"),
                ActionRow(claude ? "Hooks installed" : "Install Claude Code hooks",
                          claude ? "Registered in ~/.claude/settings.json (backup kept next to it)" : "Adds 4 hooks to ~/.claude/settings.json. Your file is backed up first",
                          claude ? "Remove" : "Install", !claude, () => { if (claude) RemoveClaudeHooks(); else InstallClaudeHooks(); ShowPage("Developer"); }),
                ActionRow("Try it", "Plays a fake Claude session in the island", "Run demo", false, () => _island.RunDemo()));

            Section("Terminal");
            Card(ActionRow(ps ? "PowerShell hook installed" : "PowerShell command finished",
                           "Peek when a command that ran 10+ seconds finishes or fails",
                           ps ? "Remove" : "Install", !ps, () => { if (ps) RemoveProfileHook(); else InstallProfileHook(); ShowPage("Developer"); }));

            Section("Island API");
            Card(
                Toggle("Local API", $"127.0.0.1:{Services.IslandApi.Port} — builds, scripts and tools can push live activities (restart to apply)", _s.ApiEnabled, v => { _s.ApiEnabled = v; Apply(); }),
                ActionRow("Docs and examples", "curl / PowerShell snippets for /activity, /notify and /ask", "Open", false, () => OpenPath(System.IO.Path.Combine(AppContext.BaseDirectory, "hooks", "README.md"))));
        }

        void BuildSync()
        {
            var sync = _island.Sync;
            static string Ago(DateTime? t)
            {
                if (t is not DateTime d) return "never";
                var span = DateTime.Now - d;
                return span.TotalMinutes < 1 ? "just now" : span.TotalHours < 1 ? $"{(int)span.TotalMinutes} min ago" : span.TotalDays < 1 ? $"{(int)span.TotalHours} h ago" : d.ToString("d MMM, h:mm tt");
            }

            Section("Water & pet sync");
            string status = !_s.SyncEnabled ? "Off"
                : !sync.FolderUsable ? "Waiting for TwinDesk's shared folder (see setup below)"
                : sync.LastPhoneSeen == null ? "Ready · phone hasn't synced yet"
                : $"Phone last synced {Ago(sync.LastPhoneSeen)}";
            Card(
                Toggle("Sync with my phone", "Drinks, goal, streaks, pet growth and looks. Each device keeps working on its own and merges when they meet", _s.SyncEnabled, v => { _s.SyncEnabled = v; Apply(); ShowPage("Phone sync"); }),
                Info("Status", status),
                Info("This laptop", $"Last written {Ago(sync.LastPush)}"),
                sync.LastError != null ? Info("Last error", sync.LastError) : null,
                ActionRow("Sync now", null, "Sync", true, () => { sync.Pull(); sync.Push(); ShowPage("Phone sync"); }));

            Section("Folder");
            Card(
                TextField("Sync folder", "Leave empty to use TwinDesk's share: " + SyncService.DefaultFolder, _s.SyncFolder, v => { _s.SyncFolder = v.Trim(); Apply(); ShowPage("Phone sync"); }),
                ActionRow("Open folder", sync.Folder, "Open", false, () => { try { Directory.CreateDirectory(sync.Folder); } catch { } OpenPath(sync.Folder); }));

            Section("Set up once (TwinDesk)");
            Card(
                Info("1. On this PC", "Open TwinDesk → Shared folders → turn on sharing this PC's folder with your phone (default folder: MacShare)"),
                Info("2. On the phone", "Dynamic Island → Settings → Sync with laptop → pick TwinDesk › PC › DynamicIsland"),
                Info("3. That's it", "Whenever the phone is connected to TwinDesk (Wi-Fi, USB or Wi-Fi Direct), both sides merge automatically"));
        }

        void BuildAbout()
        {
            Section("Shortcuts");
            Card(
                Info("Ctrl + Alt + W", "Log a drink"),
                Info("Ctrl + Alt + I", "Open / close the island"),
                Info("Ctrl + Alt + V", "Clipboard history"),
                Info("Scroll on the island", "Volume (scroll on the tab bar to switch tabs)"),
                Info("Drag files onto the island", "Puts them on the shelf"),
                Info("Right-click the island", "Settings"));
            Section("Data");
            Card(ActionRow("Your data", "Settings, water history and shelf live in %APPDATA%\\DynamicIsland. Nothing is uploaded", "Open folder", false, () => OpenPath(DataDir)));
        }

        // ================= building blocks =================

        static SolidColorBrush Brush(byte r, byte g, byte b) { var s = new SolidColorBrush(Color.FromRgb(r, g, b)); s.Freeze(); return s; }

        void Section(string title) =>
            Page.Children.Add(new TextBlock { Text = title.ToUpperInvariant(), FontSize = 11, FontWeight = FontWeights.SemiBold, Foreground = (Brush)FindResource("Dim"), Margin = new Thickness(4, Page.Children.Count == 0 ? 0 : 18, 0, 8) });

        void Card(params UIElement?[] rows)
        {
            var stack = new StackPanel();
            bool first = true;
            foreach (var r in rows)
            {
                if (r == null) continue;
                if (!first) stack.Children.Add(new Border { Height = 1, Background = Brush(0x22, 0x23, 0x26), Margin = new Thickness(16, 0, 16, 0) });
                stack.Children.Add(r);
                first = false;
            }
            Page.Children.Add(new Border { CornerRadius = new CornerRadius(18), Background = (Brush)FindResource("Card"), Child = stack });
        }

        Grid Row(string label, string? desc, UIElement right)
        {
            var g = new Grid { Margin = new Thickness(16, 12, 14, 12), MinHeight = 32 };
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 16, 0) };
            text.Children.Add(new TextBlock { Text = label, FontSize = 13, FontWeight = FontWeights.SemiBold });
            if (!string.IsNullOrEmpty(desc)) text.Children.Add(new TextBlock { Text = desc, FontSize = 11, Foreground = (Brush)FindResource("Dim"), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 0) });
            g.Children.Add(text);
            Grid.SetColumn(right, 1);
            if (right is FrameworkElement fe) fe.VerticalAlignment = VerticalAlignment.Center;
            g.Children.Add(right);
            return g;
        }

        UIElement Info(string label, string value) => Row(label, null, new TextBlock { Text = value, FontSize = 12, Foreground = (Brush)FindResource("Dim"), TextWrapping = TextWrapping.Wrap, MaxWidth = 330, TextAlignment = TextAlignment.Right });

        /// <summary>iOS-style switch: track + sliding knob.</summary>
        UIElement Toggle(string label, string? desc, bool value, Action<bool> set)
        {
            bool on = value;
            var track = new Border { Width = 44, Height = 26, CornerRadius = new CornerRadius(13), Cursor = Cursors.Hand };
            var knobMove = new TranslateTransform(on ? 18 : 0, 0);
            var knob = new Ellipse { Width = 20, Height = 20, Fill = Brushes.White, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(3, 0, 0, 0), RenderTransform = knobMove };
            track.Child = knob;
            void paint() => track.Background = on ? (Brush)FindResource("Water") : Brush(0x3A, 0x3B, 0x40);
            paint();
            var row = Row(label, desc, track);
            row.Background = Brushes.Transparent;
            row.Cursor = Cursors.Hand;
            row.MouseLeftButtonUp += (_, _) =>
            {
                on = !on;
                paint();
                knobMove.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(on ? 18 : 0, TimeSpan.FromMilliseconds(180)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                set(on);
            };
            return row;
        }

        UIElement Stepper(string label, string? desc, int value, int min, int max, int step, string unit, Action<int> set)
        {
            int v = Math.Clamp(value, min, max);
            var text = new TextBlock { FontSize = 13, FontWeight = FontWeights.SemiBold, MinWidth = 70, TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            void show() => text.Text = unit.StartsWith(":") ? $"{v:00}{unit}" : $"{v} {unit}";
            show();
            Button btn(string glyph, int dir)
            {
                var b = new Button { Style = (Style)FindResource("GlyphPill"), Content = glyph, FontSize = 10, Padding = new Thickness(9, 6, 9, 6) };
                b.Click += (_, _) => { v = Math.Clamp(v + dir * step, min, max); show(); set(v); };
                return b;
            }
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            sp.Children.Add(btn("", -1));
            sp.Children.Add(text);
            sp.Children.Add(btn("", +1));
            return Row(label, desc, sp);
        }

        UIElement TextField(string label, string? desc, string value, Action<string> set)
        {
            var box = new TextBox
            {
                Style = (Style)FindResource("IslandTextBox"),
                Text = value, Width = 220, Height = 34, FontSize = 13,
                Tag = label switch { "City" => "e.g. Ahmedabad", "PIN code" => "e.g. 380015", _ => "Type here" },
            };
            box.LostFocus += (_, _) => { if (box.Text != value) { value = box.Text; set(box.Text); } };
            box.KeyDown += (_, e) => { if (e.Key == Key.Enter) { value = box.Text; set(box.Text); Keyboard.ClearFocus(); } };
            return Row(label, desc, box);
        }

        static ControlTemplate TextBoxTemplate()
        {
            var t = new ControlTemplate(typeof(TextBox));
            var border = new FrameworkElementFactory(typeof(Border));
            border.SetValue(Border.CornerRadiusProperty, new CornerRadius(10));
            border.SetValue(Border.BackgroundProperty, new TemplateBindingExtension(Control.BackgroundProperty));
            border.SetValue(Border.PaddingProperty, new TemplateBindingExtension(Control.PaddingProperty));
            var host = new FrameworkElementFactory(typeof(ScrollViewer)) { Name = "PART_ContentHost" };
            border.AppendChild(host);
            t.VisualTree = border;
            return t;
        }

        /// <summary>Pill-style segmented control (e.g. City | PIN code).</summary>
        UIElement Segmented(string label, string? desc, string[] options, string selected, Action<string> set)
        {
            var track = new Border { CornerRadius = new CornerRadius(14), Background = Brush(0x26, 0x27, 0x2B), Padding = new Thickness(3) };
            var sp = new StackPanel { Orientation = Orientation.Horizontal };
            foreach (var opt in options)
            {
                bool on = opt == selected;
                var seg = new Border
                {
                    CornerRadius = new CornerRadius(11), Padding = new Thickness(14, 5, 14, 5), Cursor = Cursors.Hand,
                    Background = on ? (Brush)FindResource("Water") : Brushes.Transparent,
                    Child = new TextBlock { Text = opt, FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = on ? Brush(0x06, 0x20, 0x2E) : (Brush)FindResource("Dim") },
                };
                string value = opt;
                if (!on) seg.MouseLeftButtonUp += (_, _) => set(value);
                sp.Children.Add(seg);
            }
            track.Child = sp;
            return Row(label, desc, track);
        }

        UIElement ActionRow(string label, string? desc, string button, bool accent, Action click)
        {
            var b = new Button { Style = (Style)FindResource(accent ? "AccentButton" : "PillButton"), Content = button, Padding = new Thickness(14, 6, 14, 6) };
            b.Click += (_, _) => click();
            return Row(label, desc, b);
        }

        static void OpenPath(string p) { try { Process.Start(new ProcessStartInfo(p) { UseShellExecute = true }); } catch { } }

        /// <summary>4 stat tiles in a row.</summary>
        sealed class UniformGrid4
        {
            public readonly System.Windows.Controls.Primitives.UniformGrid Root = new() { Columns = 4 };
            public void Add(string label, string big, string sub)
            {
                var b = new Border { CornerRadius = new CornerRadius(16), Background = (Brush)Application.Current.FindResource("Card"), Margin = new Thickness(0, 0, 8, 8), Padding = new Thickness(14, 10, 14, 10) };
                var sp = new StackPanel();
                sp.Children.Add(new TextBlock { Text = label.ToUpperInvariant(), FontSize = 10, FontWeight = FontWeights.SemiBold, Foreground = (Brush)Application.Current.FindResource("Dim") });
                sp.Children.Add(new TextBlock { Text = big, FontSize = 20, FontWeight = FontWeights.SemiBold });
                if (sub.Length > 0) sp.Children.Add(new TextBlock { Text = sub, FontSize = 10, Foreground = (Brush)Application.Current.FindResource("Dim") });
                b.Child = sp;
                Root.Children.Add(b);
            }
        }

        // ================= hook installers =================

        static string ClaudeSettingsPath => System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".claude", "settings.json");
        static readonly string[] HookEvents = { "UserPromptSubmit", "Notification", "Stop", "PermissionRequest", "PostToolUse", "PostToolUseFailure" };
        static readonly JsonDocumentOptions JsonRead = new() { CommentHandling = JsonCommentHandling.Skip, AllowTrailingCommas = true };

        static void CopyHookScripts()
        {
            Directory.CreateDirectory(HookDir);
            foreach (var f in Directory.GetFiles(System.IO.Path.Combine(AppContext.BaseDirectory, "hooks"), "*.ps1"))
                File.Copy(f, System.IO.Path.Combine(HookDir, System.IO.Path.GetFileName(f)), true);
        }

        static bool IsOurs(JsonNode? group) => group?.ToJsonString().Contains("claude-island.ps1", StringComparison.OrdinalIgnoreCase) == true;

        static bool ClaudeHooksInstalled()
        {
            try
            {
                if (!File.Exists(ClaudeSettingsPath)) return false;
                var root = JsonNode.Parse(File.ReadAllText(ClaudeSettingsPath), null, JsonRead);
                return root?["hooks"]?["Stop"] is JsonArray a && a.Any(IsOurs);
            }
            catch { return false; }
        }

        void InstallClaudeHooks()
        {
            var err = WriteClaudeHooks();
            if (err != null) Notice("Couldn't install hooks: " + err);
        }

        /// <summary>
        /// Called at app start: if our hooks are installed but from an older version (missing events),
        /// refresh the scripts and re-register them. Backup kept as settings.json.island-backup.
        /// </summary>
        public static void UpgradeHooksIfInstalled()
        {
            try
            {
                if (!ClaudeHooksInstalled()) return;
                CopyHookScripts();   // always refresh the script itself
                var root = JsonNode.Parse(File.ReadAllText(ClaudeSettingsPath), null, JsonRead);
                bool current = HookEvents.All(ev => root?["hooks"]?[ev] is JsonArray a && a.Any(IsOurs));
                if (!current) WriteClaudeHooks();
            }
            catch { }
        }

        /// <summary>Registers our hooks (replacing older copies of ours). Returns an error message or null.</summary>
        static string? WriteClaudeHooks()
        {
            try
            {
                CopyHookScripts();
                var path = ClaudeSettingsPath;
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path)!);
                JsonObject root = new();
                if (File.Exists(path))
                {
                    File.Copy(path, path + ".island-backup", true);
                    root = JsonNode.Parse(File.ReadAllText(path), null, JsonRead) as JsonObject ?? new JsonObject();
                }
                var hooks = root["hooks"] as JsonObject ?? new JsonObject();
                root["hooks"] = hooks;
                string script = System.IO.Path.Combine(HookDir, "claude-island.ps1").Replace('\\', '/');
                foreach (var ev in HookEvents)
                {
                    var arr = hooks[ev] as JsonArray ?? new JsonArray();
                    foreach (var old in arr.Where(IsOurs).ToList()) arr.Remove(old);
                    var group = new JsonObject();
                    if (ev is "PermissionRequest" or "PostToolUse" or "PostToolUseFailure") group["matcher"] = "*";
                    var hook = new JsonObject
                    {
                        ["type"] = "command",
                        ["command"] = $"powershell -NoProfile -ExecutionPolicy Bypass -File \"{script}\"",
                        ["timeout"] = ev == "PermissionRequest" ? 60 : 10,
                    };
                    // Only the permission hook has to answer; everything else runs in the background
                    // so Claude never waits for PowerShell to start.
                    if (ev != "PermissionRequest") hook["async"] = true;
                    group["hooks"] = new JsonArray(hook);
                    arr.Add(group);
                    hooks[ev] = arr;
                }
                File.WriteAllText(path, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }

        void RemoveClaudeHooks()
        {
            try
            {
                var path = ClaudeSettingsPath;
                if (!File.Exists(path)) return;
                File.Copy(path, path + ".island-backup", true);
                if (JsonNode.Parse(File.ReadAllText(path), null, JsonRead) is not JsonObject root || root["hooks"] is not JsonObject hooks) return;
                foreach (var ev in HookEvents)
                {
                    if (hooks[ev] is not JsonArray arr) continue;
                    foreach (var old in arr.Where(IsOurs).ToList()) arr.Remove(old);
                    if (arr.Count == 0) hooks.Remove(ev);
                }
                if (hooks.Count == 0) root.Remove("hooks");
                File.WriteAllText(path, root.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
            }
            catch (Exception ex) { Notice("Couldn't remove hooks: " + ex.Message); }
        }

        static string[] ProfilePaths()
        {
            string docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
            return new[]
            {
                System.IO.Path.Combine(docs, "WindowsPowerShell", "Microsoft.PowerShell_profile.ps1"),
                System.IO.Path.Combine(docs, "PowerShell", "Microsoft.PowerShell_profile.ps1"),
            };
        }

        const string ProfileLine = ". \"$env:APPDATA\\DynamicIsland\\hooks\\island-terminal.ps1\"  # Dynamic Island";

        static bool ProfileHookInstalled() => ProfilePaths().Any(p => File.Exists(p) && File.ReadAllText(p).Contains("island-terminal.ps1"));

        void InstallProfileHook()
        {
            try
            {
                CopyHookScripts();
                foreach (var p in ProfilePaths())
                {
                    Directory.CreateDirectory(System.IO.Path.GetDirectoryName(p)!);
                    string text = File.Exists(p) ? File.ReadAllText(p) : "";
                    if (text.Contains("island-terminal.ps1")) continue;
                    if (File.Exists(p)) File.Copy(p, p + ".island-backup", true);
                    File.WriteAllText(p, text.TrimEnd() + (text.Length > 0 ? Environment.NewLine : "") + ProfileLine + Environment.NewLine);
                }
            }
            catch (Exception ex) { Notice("Couldn't update your PowerShell profile: " + ex.Message); }
        }

        void RemoveProfileHook()
        {
            try
            {
                foreach (var p in ProfilePaths().Where(File.Exists))
                {
                    var lines = File.ReadAllLines(p).Where(l => !l.Contains("island-terminal.ps1")).ToArray();
                    File.WriteAllLines(p, lines);
                }
            }
            catch (Exception ex) { Notice("Couldn't update your PowerShell profile: " + ex.Message); }
        }

        void Notice(string msg)
        {
            Page.Children.Insert(0, new Border
            {
                CornerRadius = new CornerRadius(12), Background = Brush(0x4A, 0x1F, 0x1F), Padding = new Thickness(14, 10, 14, 10), Margin = new Thickness(0, 0, 0, 10),
                Child = new TextBlock { Text = msg, TextWrapping = TextWrapping.Wrap, FontSize = 12 },
            });
        }
    }
}
