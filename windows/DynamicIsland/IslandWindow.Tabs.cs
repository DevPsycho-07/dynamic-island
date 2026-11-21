using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Media;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using DynamicIsland.Services;
using Activity = DynamicIsland.Services.Activity;

namespace DynamicIsland
{
    /// <summary>Focus timer, reminders, file shelf, clipboard history, Dev tab + Island API.</summary>
    public partial class IslandWindow
    {
        void InitTabs()
        {
            RefreshShelf();
            RefreshClipList();
            RefreshReminders();
            RefreshDevList();
            InitShortcuts();
        }

        // ================= FOCUS TIMER =================

        DateTime? _timerEnd;
        TimeSpan? _timerPausedLeft;
        int _timerMinutes = 25;

        void TimerPreset_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button b && int.TryParse(b.Tag?.ToString(), out int min)) StartTimer(min);
        }

        void StartTimer(int minutes)
        {
            _timerMinutes = minutes;
            _timerEnd = DateTime.Now.AddMinutes(minutes);
            _timerPausedLeft = null;
            UpdateTimer();
            if (_expanded) Collapse(); else RevertToBase();
        }

        void TimerPause_Click(object sender, RoutedEventArgs e)
        {
            if (_timerPausedLeft is TimeSpan left) { _timerEnd = DateTime.Now + left; _timerPausedLeft = null; }
            else if (_timerEnd is DateTime end) { _timerPausedLeft = end - DateTime.Now; _timerEnd = null; }
            UpdateTimer();
        }

        void TimerStop_Click(object sender, RoutedEventArgs e) { _timerEnd = null; _timerPausedLeft = null; UpdateTimer(); RevertToBase(); }

        void UpdateTimer()
        {
            string today = DateTime.Now.ToString("yyyy-MM-dd");
            if (_s.FocusDay != today) { _s.FocusDay = today; _s.FocusSessions = 0; _s.FocusMinutes = 0; }
            FocusStats.Text = _s.FocusSessions == 0 ? "No sessions yet today" : $"{_s.FocusSessions} session{(_s.FocusSessions == 1 ? "" : "s")} · {_s.FocusMinutes} min focused today";

            bool running = _timerEnd != null, paused = _timerPausedLeft != null;
            FocusPresets.Visibility = running || paused ? Visibility.Collapsed : Visibility.Visible;
            FocusControls.Visibility = running || paused ? Visibility.Visible : Visibility.Collapsed;
            FocusPauseBtn.Content = paused ? "Resume" : "Pause";

            TimeSpan left;
            if (paused) left = _timerPausedLeft!.Value;
            else if (running)
            {
                left = _timerEnd!.Value - DateTime.Now;
                if (left <= TimeSpan.Zero)
                {
                    _timerEnd = null;
                    _s.FocusSessions++; _s.FocusMinutes += _timerMinutes; _s.Save();
                    UpdateTimer();
                    SystemSounds.Exclamation.Play();
                    ShowMessage("", Orange, "Time's up!", $"{_timerMinutes} min done. Stretch and sip?", "", 7);
                    if (_view == View.Timer) RevertToBase();
                    return;
                }
            }
            else
            {
                FocusTime.Text = $"{_timerMinutes}:00";
                FocusSub.Text = "ready";
                FocusRing.Data = Geometry.Empty;
                return;
            }

            string t = left.ToString(left.TotalHours >= 1 ? @"h\:mm\:ss" : @"mm\:ss");
            double frac = left.TotalSeconds / (_timerMinutes * 60.0);
            TimerText.Text = t;
            TimerLabel.Text = paused ? "Paused" : $"{_timerMinutes} min focus";
            TimerRing.Data = Arc(frac, 18, 2.5);
            FocusTime.Text = t;
            FocusSub.Text = paused ? "paused" : "focusing";
            FocusRing.Data = Arc(frac, 146, 9);
        }

        // ================= REMINDERS =================

        void ReminderInput_PreviewMouseDown(object sender, MouseButtonEventArgs e) => BeginTyping(ReminderInput);
        void ReminderInput_LostFocus(object sender, KeyboardFocusChangedEventArgs e) { }

        void ReminderInput_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) { AddReminder(ParseMinutes(ReminderInput.Text) ?? 30); e.Handled = true; }
            else if (e.Key == Key.Escape) { EndTyping(); e.Handled = true; }
        }

        void ReminderAdd_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button b && int.TryParse(b.Tag?.ToString(), out int min)) AddReminder(min);
        }

        /// <summary>"call mom in 20m" / "stand up 1h" → minutes; null if none found.</summary>
        static int? ParseMinutes(string text)
        {
            var m = Regex.Match(text, @"(\d+)\s*(h|hr|hrs|hour|hours|m|min|mins|minutes)\b", RegexOptions.IgnoreCase);
            if (!m.Success) return null;
            int n = int.Parse(m.Groups[1].Value);
            return m.Groups[2].Value.StartsWith("h", StringComparison.OrdinalIgnoreCase) ? n * 60 : n;
        }

        void AddReminder(int minutes)
        {
            string text = Regex.Replace(ReminderInput.Text, @"\b(in\s+)?\d+\s*(h|hr|hrs|hour|hours|m|min|mins|minutes)\b", "", RegexOptions.IgnoreCase).Trim();
            if (string.IsNullOrWhiteSpace(text)) text = "Reminder";
            _s.ReminderItems.Add(new ReminderItem { Text = text, Due = DateTime.Now.AddMinutes(minutes) });
            _s.Save();
            ReminderInput.Text = "";
            EndTyping();
            RefreshReminders();
        }

        void RefreshReminders()
        {
            ReminderList.Children.Clear();
            foreach (var r in _s.ReminderItems.OrderBy(r => r.Due).Take(3))
            {
                var row = new Grid { Margin = new Thickness(0, 0, 0, 4) };
                row.Children.Add(new TextBlock { Text = r.Text, FontSize = 11, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 0, 66, 0), VerticalAlignment = VerticalAlignment.Center });
                row.Children.Add(new TextBlock { Text = r.Due.ToString("h:mm tt"), FontSize = 10, Foreground = (Brush)FindResource("Dim"), HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 0, 18, 0), VerticalAlignment = VerticalAlignment.Center });
                var x = new Button { Style = (Style)FindResource("IconButton"), Content = "", FontSize = 9, Width = 16, Height = 16, HorizontalAlignment = HorizontalAlignment.Right };
                var item = r;
                x.Click += (_, _) => { _s.ReminderItems.Remove(item); _s.Save(); RefreshReminders(); };
                row.Children.Add(x);
                ReminderList.Children.Add(row);
            }
        }

        void CheckReminders()
        {
            if (_hiddenForFullscreen) return;
            var due = _s.ReminderItems.Where(r => r.Due <= DateTime.Now).OrderBy(r => r.Due).FirstOrDefault();
            if (due == null || (_alert?.Reminder == due) || _pendingAlerts.Any(p => p.info.Reminder == due)) return;
            SystemSounds.Asterisk.Play();
            ShowAlert(new AlertInfo { Kind = AlertKind.Reminder, Reminder = due }, "Reminder", due.Text, "Done", "Snooze 10 min", "", Orange);
        }

        // ================= SHELF =================

        Point _dragStart;

        void InitDragDrop()
        {
            foreach (UIElement target in new UIElement[] { Pill, HoverZone })
            {
                target.AllowDrop = true;
                target.DragEnter += (_, e) =>
                {
                    if (!e.Data.GetDataPresent(DataFormats.FileDrop)) { e.Effects = DragDropEffects.None; return; }
                    e.Effects = DragDropEffects.Copy;
                    if (!_dragging && (!_expanded || _tab != Tab.Shelf)) Expand(Tab.Shelf);
                    _collapse.Stop();
                };
                target.DragOver += (_, e) => { e.Effects = e.Data.GetDataPresent(DataFormats.FileDrop) ? DragDropEffects.Copy : DragDropEffects.None; e.Handled = true; };
                target.Drop += (_, e) =>
                {
                    if (_dragging || e.Data.GetData(DataFormats.FileDrop) is not string[] files) return;
                    foreach (var f in files)
                        if ((File.Exists(f) || Directory.Exists(f)) && !_s.ShelfFiles.Contains(f, StringComparer.OrdinalIgnoreCase))
                            _s.ShelfFiles.Add(f);
                    _s.Save();
                    RefreshShelf();
                    if (!_expanded) Expand(Tab.Shelf); else SelectTab(Tab.Shelf);
                };
            }
        }

        void RefreshShelf()
        {
            _s.ShelfFiles.RemoveAll(f => !File.Exists(f) && !Directory.Exists(f));
            ShelfPanel.Children.Clear();
            bool any = _s.ShelfFiles.Count > 0;
            ShelfEmpty.Visibility = any ? Visibility.Collapsed : Visibility.Visible;
            ShelfFull.Visibility = any ? Visibility.Visible : Visibility.Collapsed;
            ShelfCount.Text = $"{_s.ShelfFiles.Count} item{(_s.ShelfFiles.Count == 1 ? "" : "s")} · drag out to use · double-click to open";
            foreach (var path in _s.ShelfFiles) ShelfPanel.Children.Add(MakeTile(path));
        }

        FrameworkElement MakeTile(string path)
        {
            var tile = new Border { Width = 84, Height = 86, CornerRadius = new CornerRadius(16), Margin = new Thickness(0, 0, 8, 8), Background = (Brush)FindResource("Card"), Cursor = Cursors.Hand, ToolTip = path };
            var g = new Grid();
            var stack = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(6, 4, 6, 4) };
            stack.Children.Add(new Image { Source = FileIcon(path), Width = 34, Height = 34 });
            stack.Children.Add(new TextBlock { Text = Path.GetFileName(path.TrimEnd('\\')), FontSize = 10, TextWrapping = TextWrapping.Wrap, TextTrimming = TextTrimming.CharacterEllipsis, MaxHeight = 28, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 4, 0, 0) });
            g.Children.Add(stack);
            var x = new Button { Style = (Style)FindResource("IconButton"), Content = "", FontSize = 8, Width = 18, Height = 18, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, 4, 4, 0), Opacity = 0 };
            x.Click += (_, _) => { _s.ShelfFiles.Remove(path); _s.Save(); RefreshShelf(); };
            g.Children.Add(x);
            tile.Child = g;
            tile.MouseEnter += (_, _) => { x.Opacity = 1; tile.Background = (Brush)FindResource("CardHover"); };
            tile.MouseLeave += (_, _) => { x.Opacity = 0; tile.Background = (Brush)FindResource("Card"); };
            tile.MouseLeftButtonDown += (_, e) =>
            {
                if (e.ClickCount == 2) { try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); } catch { } return; }
                _dragStart = e.GetPosition(this);
            };
            tile.MouseMove += (_, e) =>
            {
                if (e.LeftButton != MouseButtonState.Pressed) return;
                var p = e.GetPosition(this);
                if (Math.Abs(p.X - _dragStart.X) < 5 && Math.Abs(p.Y - _dragStart.Y) < 5) return;
                _dragging = true;
                try { DragDrop.DoDragDrop(tile, new DataObject(DataFormats.FileDrop, new[] { path }), DragDropEffects.Copy | DragDropEffects.Move | DragDropEffects.Link); }
                catch { }
                finally { _dragging = false; RefreshShelf(); }
            };
            return tile;
        }

        static BitmapSource? FileIcon(string path)
        {
            try
            {
                if (Directory.Exists(path)) path = Environment.GetFolderPath(Environment.SpecialFolder.Windows) + "\\explorer.exe";
                using var ico = System.Drawing.Icon.ExtractAssociatedIcon(path);
                if (ico == null) return null;
                var src = Imaging.CreateBitmapSourceFromHIcon(ico.Handle, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
                src.Freeze();
                return src;
            }
            catch { return null; }
        }

        void ShelfClear_Click(object sender, RoutedEventArgs e) { _s.ShelfFiles.Clear(); _s.Save(); RefreshShelf(); }

        // ================= CLIPBOARD =================

        sealed record ClipItem(string Text, DateTime At);
        readonly List<ClipItem> _clips = new();
        bool _selfCopy;

        void OnClipboardChanged()
        {
            if (_selfCopy) { _selfCopy = false; return; }
            try
            {
                var data = Clipboard.GetDataObject();
                if (data == null) return;
                var formats = data.GetFormats();
                // Password managers mark secrets with these formats — never keep or show them.
                if (formats.Any(f => f is "ExcludeClipboardContentFromMonitorProcessing" or "Clipboard Viewer Ignore" or "CanIncludeInClipboardHistory")) return;

                if (data.GetDataPresent(DataFormats.UnicodeText) && data.GetData(DataFormats.UnicodeText) is string text && !string.IsNullOrWhiteSpace(text))
                {
                    _clips.RemoveAll(c => c.Text == text);
                    _clips.Insert(0, new ClipItem(text, DateTime.Now));
                    if (_clips.Count > 25) _clips.RemoveAt(_clips.Count - 1);
                    if (_expanded && _tab == Tab.Clip) RefreshClipList();
                    if (_s.ShowClipboardPeek && !_expanded)
                        ShowMessage("", WhiteB, "Copied", LooksSecret(text) ? "Hidden — looks like a password" : OneLine(text, 70), "", 1.6);
                }
                else if (data.GetDataPresent(DataFormats.FileDrop) && data.GetData(DataFormats.FileDrop) is string[] files && _s.ShowClipboardPeek && !_expanded)
                    ShowMessage("", WhiteB, "Copied", files.Length == 1 ? Path.GetFileName(files[0]) : $"{files.Length} files", "", 1.6);
                else if (data.GetDataPresent(DataFormats.Bitmap) && _s.ShowClipboardPeek && !_expanded)
                    ShowMessage("", WhiteB, "Copied", "Image", "", 1.6);
            }
            catch { /* clipboard busy — ignore this change */ }
        }

        static string OneLine(string s, int max)
        {
            s = Regex.Replace(s.Trim(), @"\s+", " ");
            return s.Length > max ? s[..max] + "…" : s;
        }

        static bool LooksSecret(string s)
        {
            s = s.Trim();
            if (s.Length < 8 || s.Length > 128 || s.Any(char.IsWhiteSpace)) return false;
            if (s.StartsWith("http", StringComparison.OrdinalIgnoreCase) || s.Contains('\\') || s.Contains('/')) return false;
            int classes = (s.Any(char.IsUpper) ? 1 : 0) + (s.Any(char.IsLower) ? 1 : 0) + (s.Any(char.IsDigit) ? 1 : 0) + (s.Any(c => !char.IsLetterOrDigit(c)) ? 1 : 0);
            return classes >= 3 || (s.Length >= 24 && classes >= 2);
        }

        void RefreshClipList()
        {
            ClipList.Children.Clear();
            ClipEmpty.Visibility = _clips.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            foreach (var c in _clips)
            {
                var row = new Border { CornerRadius = new CornerRadius(12), Background = (Brush)FindResource("Card"), Padding = new Thickness(12, 7, 12, 7), Margin = new Thickness(0, 0, 0, 5), Cursor = Cursors.Hand };
                var g = new Grid();
                g.Children.Add(new TextBlock { Text = LooksSecret(c.Text) ? "••••••••  (hidden)" : OneLine(c.Text, 90), FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, Margin = new Thickness(0, 0, 70, 0) });
                g.Children.Add(new TextBlock { Text = Ago(c.At), FontSize = 10, Foreground = (Brush)FindResource("Dim"), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center });
                row.Child = g;
                var item = c;
                row.MouseEnter += (_, _) => row.Background = (Brush)FindResource("CardHover");
                row.MouseLeave += (_, _) => row.Background = (Brush)FindResource("Card");
                row.MouseLeftButtonUp += (_, _) =>
                {
                    try { _selfCopy = true; Clipboard.SetText(item.Text); } catch { _selfCopy = false; }
                    _clips.Remove(item); _clips.Insert(0, item with { At = DateTime.Now });
                    RefreshClipList();
                    PetSpeech.Text = "Copied!";
                };
                ClipList.Children.Add(row);
            }
        }

        static string Ago(DateTime t)
        {
            var d = DateTime.Now - t;
            return d.TotalMinutes < 1 ? "now" : d.TotalHours < 1 ? $"{(int)d.TotalMinutes}m" : d.TotalDays < 1 ? $"{(int)d.TotalHours}h" : t.ToString("d MMM");
        }

        void ClipClear_Click(object sender, RoutedEventArgs e) { _clips.Clear(); RefreshClipList(); }

        // ================= DEV: live activities via the Island API =================

        readonly Dictionary<string, Activity> _activities = new();
        readonly Dictionary<string, DateTime> _activityEnds = new();

        void StartApi()
        {
            _api.ActivityPosted += a => Dispatcher.BeginInvoke(() => OnActivity(a));
            _api.NotifyPosted += n => Dispatcher.BeginInvoke(() =>
            {
                if (!Quiet) ShowMessage(IconGlyph(n.Icon), IconBrush(n.Icon), n.Title, n.Body ?? "", "", Math.Clamp(n.Ttl ?? 4, 2, 15));
            });
            _api.AskPosted += q => Dispatcher.BeginInvoke(() =>
            {
                var info = new AlertInfo { Kind = AlertKind.Ask, Ask = q };
                // Do not disturb: no sound and no pop-up. The session's pill still shows "waiting"; clicking the
                // island brings the question up, and so does DND ending. You can also answer in the chat as usual.
                bool quiet = Quiet;
                if (!quiet) SystemSounds.Asterisk.Play();
                // Name the session that's asking: "Allow Bash? · Dynamic Island #2"
                string title = q.Activity != null && _activities.TryGetValue(q.Activity, out var owner) && !string.IsNullOrEmpty(owner.Label)
                    ? $"{q.Title} · {LabelOf(owner)}" : q.Title;
                ShowAlert(info, title, q.Body ?? "", q.Buttons[0], q.Buttons.Length > 1 ? q.Buttons[1] : null, IconGlyph(q.Icon), IconBrush(q.Icon), AppIcons.ForIcon(q.Icon), hold: quiet);
                // If the caller gives up (timeout), drop the question.
                q.Answer.Task.ContinueWith(_ => { }, System.Threading.Tasks.TaskScheduler.Default);
                var t = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(Math.Clamp(q.Timeout, 5, 600)) };
                t.Tick += (_, _) =>
                {
                    t.Stop();
                    if (_alert == info) { IslandApi.Answer(q, null); CloseAlert(); }
                    else { var keep = _pendingAlerts.Where(p => p.info != info).ToList(); _pendingAlerts.Clear(); foreach (var p in keep) _pendingAlerts.Enqueue(p); }
                };
                t.Start();
            });
            _api.ResolvePosted += id => Dispatcher.BeginInvoke(() => DropQuestions(id));
            _api.AskAbandoned += q => Dispatcher.BeginInvoke(() => DropAsk(q));
            _api.ShowPosted += tab => Dispatcher.BeginInvoke(() =>
            {
                if (tab == "close") { Collapse(); return; }
                if (tab == "demo") { RunDemo(); return; }
                if (tab == "settings") { SettingsRequested?.Invoke(); return; }
                Expand(Enum.TryParse<Tab>(tab, true, out var t) ? t : Tab.Home);
            });
            if (_s.ApiEnabled) _api.Start();
            ApiStatus.Text = _api.Running ? $"API on 127.0.0.1:{IslandApi.Port}" : "API off";
        }

        /// <summary>The session moved on (answered in the terminal/chat, tool ran, Claude stopped): clear its questions.</summary>
        void DropQuestions(string activityId)
        {
            bool Mine(AlertInfo i) => i.Kind == AlertKind.Ask && i.Ask?.Activity == activityId;
            var keep = _pendingAlerts.Where(p => !Mine(p.info)).ToList();
            foreach (var p in _pendingAlerts.Where(p => Mine(p.info))) IslandApi.Answer(p.info.Ask!, null);
            _pendingAlerts.Clear();
            foreach (var p in keep) _pendingAlerts.Enqueue(p);
            if (_alert != null && Mine(_alert)) { IslandApi.Answer(_alert.Ask!, null); CloseAlert(); }
        }

        /// <summary>One specific question whose asker went away (answered in the chat, hook killed).</summary>
        void DropAsk(AskRequest q)
        {
            var keep = _pendingAlerts.Where(p => p.info.Ask != q).ToList();
            _pendingAlerts.Clear();
            foreach (var p in keep) _pendingAlerts.Enqueue(p);
            if (_alert?.Ask == q) CloseAlert();
        }

        void OnActivity(Activity a)
        {
            if (_activities.TryGetValue(a.Id, out var old))
            {
                old.Title = string.IsNullOrWhiteSpace(a.Title) ? old.Title : a.Title;
                old.Body = a.Body ?? old.Body;
                old.Icon = a.Icon ?? old.Icon;
                old.State = a.State ?? old.State;
                old.Progress = a.Progress;
                old.Focus = a.Focus ?? old.Focus;
                old.Ttl = a.Ttl ?? old.Ttl;
                old.Label = a.Label ?? old.Label;
                old.Window = a.Window ?? old.Window;
                old.Topic ??= a.Topic;            // keep the session's first prompt as its topic
                a = old;
            }
            else
            {
                _activities[a.Id] = a;
                AssignNumber(a);
            }

            string state = (a.State ?? "running").ToLowerInvariant();
            if (state is "done" or "error")
            {
                _activityEnds[a.Id] = DateTime.Now.AddSeconds(Math.Clamp(a.Ttl ?? 6, 1, 3600));
                var elapsed = DateTime.Now - a.Started;
                string took = elapsed.TotalSeconds >= 5 ? $" · {FmtElapsed(elapsed)}" : "";
                if (!Quiet) ShowMessage(state == "done" ? "" : "", state == "done" ? Green : Red, Display(a), (a.Body ?? "") + took, "", 4,
                    image: AppIcons.ForIcon(a.Icon),
                    action: a.Focus != null ? "Open" : null, onAction: a.Focus != null ? () => Native.FocusProcess(a.Focus, a.Window) : null);
            }
            else
            {
                _activityEnds.Remove(a.Id);
                if (state == "waiting" && !Quiet)   // Do not disturb: the pill just turns orange, no peek or sound
                {
                    SystemSounds.Asterisk.Play();
                    ShowMessage(IconGlyph(a.Icon), IconBrush(a.Icon), Display(a), a.Body ?? "Needs your input", "", 6,
                        image: AppIcons.ForIcon(a.Icon),
                        action: a.Focus != null ? "Open" : null, onAction: a.Focus != null ? () => Native.FocusProcess(a.Focus, a.Window) : null);
                }
                else RevertToBaseUnlessTransient();
            }
            UpdateActivityView();
            if (_expanded && _tab == Tab.Dev) RefreshDevList();
        }

        void ExpireActivities()
        {
            var gone = _activityEnds.Where(kv => kv.Value <= DateTime.Now).Select(kv => kv.Key).ToList();
            if (gone.Count == 0) return;
            foreach (var id in gone) { _activityEnds.Remove(id); _activities.Remove(id); }
            if (_view == View.Activity && CurrentActivity() == null) RevertToBase();
            if (_expanded && _tab == Tab.Dev) RefreshDevList();
        }

        Activity? CurrentActivity() =>
            _activities.Values.Where(a => (a.State ?? "running").ToLowerInvariant() is "running" or "waiting")
                              .OrderByDescending(a => (a.State ?? "").ToLowerInvariant() == "waiting").ThenByDescending(a => a.Started).FirstOrDefault();

        void UpdateActivityView()
        {
            var a = CurrentActivity();
            if (a == null) return;
            var brush = IconBrush(a.Icon);
            var logo = AppIcons.ForIcon(a.Icon);   // real Claude / Terminal logo when available
            SetGlyph(ActIcon, IconGlyph(a.Icon));
            ActIcon.Foreground = brush;
            ActIcon.Visibility = logo == null ? Visibility.Visible : Visibility.Collapsed;
            ActImage.Source = logo;
            ActImage.Visibility = logo != null ? Visibility.Visible : Visibility.Collapsed;
            ActIconBox.Background = logo != null ? Brushes.Transparent : Frozen(WithAlpha(brush, 0x33));
            bool waiting = (a.State ?? "").ToLowerInvariant() == "waiting";
            ActTitle.Text = Display(a);
            ActElapsed.Text = waiting ? "waiting" : FmtElapsed(DateTime.Now - a.Started);
            int others = _activities.Values.Count(x => x != a && (x.State ?? "running").ToLowerInvariant() is "running" or "waiting");
            ActCountBox.Visibility = others > 0 ? Visibility.Visible : Visibility.Collapsed;
            ActCount.Text = $"+{others}";
            ActRing.Stroke = waiting ? Orange : brush;
            ActRing.Data = a.Progress is double p ? Arc(p, 16, 2.2) : Arc(waiting ? 1 : 0.28, 16, 2.2);
            UpdateActivitySpinner();
        }

        bool _spinning;
        void UpdateActivitySpinner()
        {
            var a = _view == View.Activity ? CurrentActivity() : null;
            bool spin = a != null && a.Progress == null && (a.State ?? "running").ToLowerInvariant() == "running" && !_hiddenForFullscreen;
            if (spin == _spinning) return;
            _spinning = spin;
            if (spin)
            {
                var r = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1)) { RepeatBehavior = RepeatBehavior.Forever };
                Timeline.SetDesiredFrameRate(r, 30);
                ActSpin.BeginAnimation(RotateTransform.AngleProperty, r);
            }
            else ActSpin.BeginAnimation(RotateTransform.AngleProperty, null);
        }

        void RefreshDevList()
        {
            DevList.Children.Clear();
            var items = _activities.Values.OrderByDescending(a => a.Started).ToList();
            DevEmpty.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            foreach (var a in items)
            {
                string state = (a.State ?? "running").ToLowerInvariant();
                var brush = state switch { "done" => Green, "error" => Red, "waiting" => Orange, _ => IconBrush(a.Icon) };
                var row = new Border { CornerRadius = new CornerRadius(14), Background = (Brush)FindResource("Card"), Padding = new Thickness(10, 8, 12, 8), Margin = new Thickness(0, 0, 0, 6), Cursor = a.Focus != null ? Cursors.Hand : Cursors.Arrow };
                var g = new Grid();
                var iconBox = new Border { Width = 28, Height = 28, CornerRadius = new CornerRadius(9), Background = Frozen(WithAlpha(IconBrush(a.Icon), 0x33)), HorizontalAlignment = HorizontalAlignment.Left };
                if (AppIcons.ForIcon(a.Icon) is BitmapSource logo)
                {
                    iconBox.Background = Brushes.Transparent;
                    iconBox.Child = new Image { Source = logo, Width = 26, Height = 26 };
                }
                else
                {
                    var iconText = new TextBlock { FontSize = 13, Foreground = IconBrush(a.Icon), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                    SetGlyph(iconText, IconGlyph(a.Icon));
                    iconBox.Child = iconText;
                }
                g.Children.Add(iconBox);
                var text = new StackPanel { Margin = new Thickness(40, 0, 110, 0), VerticalAlignment = VerticalAlignment.Center };
                text.Children.Add(new TextBlock { Text = Display(a), FontSize = 12, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
                string sub = !string.IsNullOrWhiteSpace(a.Topic) && a.Topic != a.Body ? $"{a.Topic}  ·  {a.Body}" : a.Body ?? "";
                if (!string.IsNullOrWhiteSpace(sub)) text.Children.Add(new TextBlock { Text = OneLine(sub, 110), FontSize = 11, Foreground = (Brush)FindResource("Dim"), TextTrimming = TextTrimming.CharacterEllipsis });
                g.Children.Add(text);
                var chip = new Border { CornerRadius = new CornerRadius(8), Background = Frozen(WithAlpha(brush, 0x30)), Padding = new Thickness(8, 2, 8, 2), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center };
                chip.Child = new TextBlock { Text = state == "running" ? FmtElapsed(DateTime.Now - a.Started) : state, FontSize = 10, Foreground = brush };
                g.Children.Add(chip);
                row.Child = g;
                var act = a;
                row.MouseLeftButtonUp += (_, _) => Native.FocusProcess(act.Focus, act.Window);
                DevList.Children.Add(row);
            }
        }

        // ---------- telling parallel sessions apart ----------

        readonly Dictionary<string, int> _actNumber = new();

        /// <summary>First free number among live activities with the same label (so a 2nd session in one project is "#2").</summary>
        void AssignNumber(Activity a)
        {
            if (string.IsNullOrEmpty(a.Label)) return;
            var used = _activities.Values.Where(x => x != a && x.Label == a.Label && _actNumber.ContainsKey(x.Id)).Select(x => _actNumber[x.Id]).ToHashSet();
            int n = 1;
            while (used.Contains(n)) n++;
            _actNumber[a.Id] = n;
        }

        string LabelOf(Activity a)
        {
            if (string.IsNullOrEmpty(a.Label)) return "";
            bool several = _activities.Values.Count(x => x.Label == a.Label) > 1;
            return several && _actNumber.TryGetValue(a.Id, out int n) ? $"{a.Label} #{n}" : a.Label;
        }

        /// <summary>"Claude finished · Dynamic Island #2" — title plus which session/project it came from.</summary>
        string Display(Activity a) => string.IsNullOrEmpty(a.Label) ? a.Title : $"{a.Title} · {LabelOf(a)}";

        static string FmtElapsed(TimeSpan t) => t.TotalHours >= 1 ? $"{(int)t.TotalHours}h {t.Minutes}m" : t.TotalMinutes >= 1 ? $"{(int)t.TotalMinutes}m {t.Seconds}s" : $"{t.Seconds}s";

        static string IconGlyph(string? icon) => (icon ?? "").ToLowerInvariant() switch
        {
            "claude" => "✳",
            "terminal" => "",
            "build" => "",
            "check" => "",
            "error" => "",
            "git" => "",
            "test" => "",
            "deploy" => "",
            _ => "",
        };

        static Brush IconBrush(string? icon) => (icon ?? "").ToLowerInvariant() switch
        {
            "claude" => ClaudeOrange,
            "terminal" => Frozen(Color.FromRgb(0xA0, 0xA0, 0xA8)),
            "build" => Blue,
            "check" => Green,
            "error" => Red,
            "git" => Frozen(Color.FromRgb(0xF0, 0x50, 0x33)),
            "deploy" => Frozen(Color.FromRgb(0xAF, 0x52, 0xDE)),
            _ => Blue,
        };
    }
}
