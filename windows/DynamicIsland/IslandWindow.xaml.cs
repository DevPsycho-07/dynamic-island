using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using DynamicIsland.Pets;
using DynamicIsland.Services;
using Microsoft.Win32;

namespace DynamicIsland
{
    enum View { Dock, Peek, Media, Timer, Activity, Level, Message, Notify, Alert, Expanded }
    enum Tab { Home, Water, Focus, Shelf, Clip, System, Dev, Tools }

    /// <summary>
    /// The island. This file: window plumbing, the notch shape, view switching, hover/dock/expand.
    /// Feature logic lives in the other IslandWindow.*.cs partial files.
    /// </summary>
    public partial class IslandWindow : Window
    {
        readonly Settings _s;
        readonly MediaService _mediaSvc = new();
        readonly OsdService _osd = new();
        readonly PowerService _power = new();
        readonly NotificationService _toasts = new();
        readonly BluetoothService _bt = new();
        readonly WeatherService _weatherSvc = new();
        readonly IslandApi _api = new();
        readonly SystemStats _stats = new();

        readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
        readonly DispatcherTimer _transient = new();
        readonly DispatcherTimer _collapse = new() { Interval = TimeSpan.FromMilliseconds(700) };
        readonly DispatcherTimer _hoverIn = new() { Interval = TimeSpan.FromMilliseconds(110) };
        readonly DispatcherTimer _hoverOut = new() { Interval = TimeSpan.FromMilliseconds(450) };
        readonly DispatcherTimer _dockFade = new() { Interval = TimeSpan.FromSeconds(4) }; // docked view → invisible

        readonly Dictionary<View, (FrameworkElement el, double w, double h)> _views;
        View _view = View.Dock;
        Tab _tab = Tab.Home;
        bool _expanded, _peeking, _hiddenForFullscreen, _typing, _dragging;
        int _ticks;
        IntPtr _hwnd;

        public event Action? SettingsRequested;

        static readonly Brush Green = Frozen(Color.FromRgb(0x34, 0xC7, 0x59));
        static readonly Brush Orange = Frozen(Color.FromRgb(0xFF, 0xA7, 0x26));
        static readonly Brush Red = Frozen(Color.FromRgb(0xFF, 0x45, 0x3A));
        static readonly Brush Yellow = Frozen(Color.FromRgb(0xFF, 0xD6, 0x0A));
        static readonly Brush Blue = Frozen(Color.FromRgb(0x5B, 0x8D, 0xEF));
        static readonly Brush ClaudeOrange = Frozen(Color.FromRgb(0xD9, 0x77, 0x57));
        static readonly Brush WhiteB = Brushes.White;
        Brush WaterB => (Brush)FindResource("Water");

        public IslandWindow(Settings settings)
        {
            InitializeComponent();
            _s = settings;

            _views = new()
            {
                [View.Dock] = (DockView, 130, 6),
                [View.Peek] = (PeekView, 290, 34),
                [View.Media] = (MediaView, 310, 36),
                [View.Timer] = (TimerView, 230, 36),
                [View.Activity] = (ActivityView, 330, 36),
                [View.Level] = (LevelView, 300, 40),
                [View.Message] = (MessageView, 390, 56),
                [View.Notify] = (NotifyView, 450, 82),
                [View.Alert] = (AlertView, 450, 128),
                [View.Expanded] = (ExpandedView, 600, 262),
            };
            foreach (var (k, v) in _views)
            {
                v.el.Opacity = k == View.Dock ? 1 : 0;
                v.el.Visibility = k == View.Dock ? Visibility.Visible : Visibility.Hidden;
                v.el.IsHitTestVisible = k == View.Dock;
            }
            Shape.Opacity = 0; // starts docked and already faded (see Show)
            DockFill.Opacity = 0;
            _dockFade.Tick += (_, _) =>
            {
                _dockFade.Stop();
                if (_view != View.Dock) return;
                Shape.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(500)));
                DockFill.BeginAnimation(OpacityProperty, new DoubleAnimation(0, TimeSpan.FromMilliseconds(500)));
            };

            Pill.SizeChanged += (_, _) => UpdateShape();
            Pill.MouseEnter += (_, _) => OnHover(true);
            Pill.MouseLeave += (_, _) => OnHover(false);
            HoverZone.MouseEnter += (_, _) => OnHover(true);
            HoverZone.MouseLeave += (_, _) => OnHover(false);
            HoverZone.MouseLeftButtonUp += (_, _) => Expand(_tab);
            Pill.MouseLeftButtonUp += Pill_Click;
            Pill.MouseRightButtonUp += (_, _) => SettingsRequested?.Invoke();
            Pill.MouseWheel += Pill_MouseWheel;
            MouseMove += (_, e) => PetsLookAt(e);
            InitDragDrop();

            _tick.Tick += (_, _) => OnTick();
            _transient.Tick += (_, _) => { _transient.Stop(); RevertToBase(); };
            _collapse.Tick += (_, _) => { _collapse.Stop(); if (!Pill.IsMouseOver && !_typing && !_dragging) Collapse(); };
            _hoverIn.Tick += (_, _) => { _hoverIn.Stop(); if (_view == View.Dock && !_expanded) { _peeking = true; Show(View.Peek); } };
            _hoverOut.Tick += (_, _) =>
            {
                _hoverOut.Stop();
                if (_peeking && !Pill.IsMouseOver && !HoverZone.IsMouseOver) { _peeking = false; RevertToBase(); }
            };

            SourceInitialized += OnSourceInitialized;
            Loaded += async (_, _) =>
            {
                FitWindow(150, 6);
                Reposition();
                UpdateShape();
                InitMedia();
                InitWater();
                InitTabs();
                StartServices();
                RevertToBase();
                _tick.Start();
                await _mediaSvc.StartAsync();
                await RefreshWeatherAsync();
            };
            SystemEvents.DisplaySettingsChanged += (_, _) => Dispatcher.BeginInvoke(Reposition);
            SystemEvents.PowerModeChanged += (_, e) => { if (e.Mode == PowerModes.Resume) Dispatcher.BeginInvoke(OnResume); };
            DpiChanged += (_, _) => Reposition();
        }

        // ---------- window plumbing ----------

        void OnSourceInitialized(object? sender, EventArgs e)
        {
            _hwnd = new WindowInteropHelper(this).Handle;
            int ex = Native.GetWindowLong(_hwnd, Native.GWL_EXSTYLE);
            Native.SetWindowLong(_hwnd, Native.GWL_EXSTYLE, ex | Native.WS_EX_TOOLWINDOW | Native.WS_EX_NOACTIVATE);
            HwndSource.FromHwnd(_hwnd)?.AddHook(WndProc);
            Native.RegisterHotKey(_hwnd, 1, Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT, 0x57); // Ctrl+Alt+W  log water
            Native.RegisterHotKey(_hwnd, 2, Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT, 0x49); // Ctrl+Alt+I  open island
            Native.RegisterHotKey(_hwnd, 3, Native.MOD_CONTROL | Native.MOD_ALT | Native.MOD_NOREPEAT, 0x56); // Ctrl+Alt+V  clipboard
            Native.AddClipboardFormatListener(_hwnd);
        }

        IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == Native.WM_HOTKEY)
            {
                switch (wParam.ToInt32())
                {
                    case 1: LogWater(); break;
                    case 2: ToggleExpanded(); break;
                    case 3: if (_expanded && _tab == Tab.Clip) Collapse(); else Expand(Tab.Clip); break;
                }
                handled = true;
            }
            else if (msg == Native.WM_CLIPBOARDUPDATE)
            {
                Dispatcher.BeginInvoke(OnClipboardChanged, DispatcherPriority.Background);
            }
            return IntPtr.Zero;
        }

        void Reposition()
        {
            var wa = SystemParameters.WorkArea;
            Left = wa.Left + (wa.Width - Width) / 2;
            Top = wa.Top;
        }

        /// <summary>After sleep the shell sometimes drops topmost windows — re-assert everything.</summary>
        void OnResume()
        {
            Reposition();
            Topmost = false; Topmost = true;
            _osd.HookDefaultDevice();
            _ = RefreshWeatherAsync();
        }

        public void Shutdown()
        {
            try
            {
                Native.UnregisterHotKey(_hwnd, 1); Native.UnregisterHotKey(_hwnd, 2); Native.UnregisterHotKey(_hwnd, 3);
                Native.RemoveClipboardFormatListener(_hwnd);
            }
            catch { }
            _sync.Push(); _sync.Dispose();
            _osd.Dispose(); _toasts.Dispose(); _bt.Dispose(); _api.Dispose();
            _tick.Stop();
            _s.Save();
            Close();
        }

        public void OnSettingsChanged()
        {
            _water.StampSettings();   // newest edit wins when the phone syncs
            _s.Save();
            _sync.Start();            // picks up sync on/off and folder changes, then pushes
            RefreshWater(false);
            BuildWaterChips();
            _ = RefreshWeatherAsync();
            RevertToBase();
            if (!Quiet) ReleaseHeld();   // "Respect Do not disturb" just switched off
        }

        // ---------- Do not disturb ----------

        /// <summary>
        /// Windows' Do not disturb (or a focus session) is on and the island respects it: nothing pops up or plays a
        /// sound by itself. Permission prompts are held (see ShowAlert's hold) until you click the island or it ends.
        /// Things you do yourself (volume, copying, logging water) and your own timers / reminders still show.
        /// </summary>
        bool Quiet => _s.RespectDnd && Native.IsDoNotDisturbOn();

        bool _wasQuiet;

        /// <summary>Show the next question Do not disturb held back (it ended, or you clicked the island).</summary>
        void ReleaseHeld()
        {
            if (_alert == null && _pendingAlerts.Count > 0) _pendingAlerts.Dequeue().show();
        }

        // ---------- the notch shape ----------

        /// <summary>Notch flush with the top edge: concave "ears" at the top corners, rounded bottom.</summary>
        void UpdateShape()
        {
            double w = Pill.ActualWidth, h = Pill.ActualHeight;
            if (w <= 0 || h <= 0) return;
            double e = Math.Min(10, h);
            double r = h <= 60 ? h / 2 : 30;
            double bw = Math.Max(0, w - 2 * e);
            r = Math.Min(r, bw / 2);

            var f = new PathFigure { StartPoint = new Point(0, 0), IsClosed = true };
            f.Segments.Add(new QuadraticBezierSegment(new Point(e, 0), new Point(e, Math.Min(e, h - r)), true));
            f.Segments.Add(new LineSegment(new Point(e, h - r), true));
            f.Segments.Add(new ArcSegment(new Point(e + r, h), new Size(r, r), 0, false, SweepDirection.Counterclockwise, true));
            f.Segments.Add(new LineSegment(new Point(w - e - r, h), true));
            f.Segments.Add(new ArcSegment(new Point(w - e, h - r), new Size(r, r), 0, false, SweepDirection.Counterclockwise, true));
            f.Segments.Add(new LineSegment(new Point(w - e, Math.Min(e, h - r)), true));
            f.Segments.Add(new QuadraticBezierSegment(new Point(w - e, 0), new Point(w, 0), true));
            var g = new PathGeometry(); g.Figures.Add(f); g.Freeze();
            Shape.Data = g;

            Host.Margin = new Thickness(e, 0, e, 0);
            var clip = new PathFigure { StartPoint = new Point(0, 0), IsClosed = true };
            clip.Segments.Add(new LineSegment(new Point(bw, 0), true));
            clip.Segments.Add(new LineSegment(new Point(bw, h - r), true));
            clip.Segments.Add(new ArcSegment(new Point(bw - r, h), new Size(r, r), 0, false, SweepDirection.Clockwise, true));
            clip.Segments.Add(new LineSegment(new Point(r, h), true));
            clip.Segments.Add(new ArcSegment(new Point(0, h - r), new Size(r, r), 0, false, SweepDirection.Clockwise, true));
            var cg = new PathGeometry(); cg.Figures.Add(clip); cg.Freeze();
            Host.Clip = cg;
        }

        // ---------- view switching ----------

        View BaseView()
        {
            if (_timerEnd != null || _timerPausedLeft != null) return View.Timer;
            if (CurrentActivity() != null) return View.Activity;
            if (_s.ShowMedia && _media?.Playing == true && !_mediaInFront) return View.Media;
            if (_peeking) return View.Peek;
            return _s.DockWhenIdle ? View.Dock : View.Peek;
        }

        void RevertToBase()
        {
            if (_expanded) Show(View.Expanded);
            else if (_alert != null) Show(View.Alert);
            else Show(BaseView());
        }

        void Show(View v)
        {
            HoverZone.IsHitTestVisible = v == View.Dock;
            if (v == _view) { OnViewShown(); return; }
            var (_, w, h) = _views[v];
            double e = Math.Min(10, h);
            bool growing = w * h > Pill.ActualWidth * Pill.ActualHeight;
            // Window hugs the island: grow first (room for the animation), shrink after it settles.
            FitWindow(Math.Max(Pill.ActualWidth, w + 2 * e), Math.Max(Pill.ActualHeight, h));
            Morph(w + 2 * e, h, growing);
            // The near-transparent HoverZone still catches the mouse once the docked view has faded.
            // Docked view (bar + water line) stays a few seconds after closing, then fades to nothing.
            Shape.BeginAnimation(OpacityProperty, new DoubleAnimation(1, TimeSpan.FromMilliseconds(90)));
            DockFill.BeginAnimation(OpacityProperty, new DoubleAnimation(0.85, TimeSpan.FromMilliseconds(90)));
            _dockFade.Stop();
            if (v == View.Dock) _dockFade.Start();

            var prev = _view;
            _view = v;
            foreach (var (k, item) in _views)
            {
                var el = item.el;
                if (k == v)
                {
                    el.Visibility = Visibility.Visible;
                    el.IsHitTestVisible = true;
                    var fade = new DoubleAnimation(1, TimeSpan.FromMilliseconds(220)) { BeginTime = TimeSpan.FromMilliseconds(growing ? 90 : 40) };
                    el.BeginAnimation(OpacityProperty, fade);
                }
                else if (k == prev || el.Opacity > 0)
                {
                    el.IsHitTestVisible = false;
                    var fade = new DoubleAnimation(0, TimeSpan.FromMilliseconds(110));
                    var key = k;
                    fade.Completed += (_, _) => { if (_view != key) el.Visibility = Visibility.Hidden; };
                    el.BeginAnimation(OpacityProperty, fade);
                }
            }
            OnViewShown();
        }

        /// <summary>Start/stop the things that only run while a view is on screen (saves battery).</summary>
        void OnViewShown()
        {
            bool petsAlive = _expanded && (_tab == Tab.Home || _tab == Tab.Water);
            SetPetIdle(PetCard, petsAlive && _tab == Tab.Home);
            SetPetIdle(PetBig, petsAlive && _tab == Tab.Water);
            SetPetIdle(AlertPet, _view == View.Alert && _alert?.Kind == AlertKind.Water);
            UpdateVisualizerTimer();
            UpdateActivitySpinner();
        }

        void Morph(double w, double h, bool growing)
        {
            var dur = TimeSpan.FromMilliseconds(_s.ReduceMotion ? 160 : growing ? 470 : 340);
            IEasingFunction ease = _s.ReduceMotion || !growing
                ? new CubicEase { EasingMode = EasingMode.EaseOut }
                : new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.2 };
            Pill.BeginAnimation(WidthProperty, new DoubleAnimation(w, dur) { EasingFunction = ease });
            var ha = new DoubleAnimation(h, dur) { EasingFunction = ease };
            int seq = ++_morphSeq;
            ha.Completed += (_, _) => { if (seq == _morphSeq) FitWindow(w, h); };
            Pill.BeginAnimation(HeightProperty, ha);
        }

        int _morphSeq;

        /// <summary>
        /// Size the (transparent) window to the island plus a little room for hover-scale/overshoot, centred at the top.
        /// Keeps screenshots, window pickers and clicks clear of an invisible 680×300 box.
        /// </summary>
        void FitWindow(double pillW, double pillH)
        {
            double w = Math.Max(260, Math.Ceiling(pillW * 1.08 + 24));
            double h = Math.Max(16, Math.Ceiling(pillH * 1.08 + 10));
            if (Math.Abs(Width - w) < 1 && Math.Abs(Height - h) < 1) return;
            Width = w;
            Height = h;
            Reposition();
        }

        void ShowTransient(View v, double seconds)
        {
            if (_hiddenForFullscreen) return;
            Show(v);
            _transient.Stop();
            _transient.Interval = TimeSpan.FromSeconds(seconds);
            _transient.Start();
        }

        /// <summary>One-line peek: icon (glyph, image or pet) + title + subtitle + value/action.</summary>
        void ShowMessage(string glyph, Brush accent, string title, string sub, string value = "", double seconds = 2.6,
                         BitmapSource? image = null, bool pet = false, string? action = null, Action? onAction = null)
        {
            if (_hiddenForFullscreen || _alert != null) return;
            if (_expanded && !pet) return;
            SetGlyph(MsgIcon, glyph);
            MsgIcon.Foreground = accent;
            MsgImage.Source = image;
            MsgPet.Visibility = pet ? Visibility.Visible : Visibility.Collapsed;
            MsgIconBox.Background = image != null || pet ? Brushes.Transparent : Frozen(WithAlpha(accent, 0x30));
            MsgTitle.Text = title;
            MsgSub.Text = sub;
            MsgValue.Text = action == null ? value : "";
            MsgValue.Foreground = accent;
            MsgAction.Visibility = action == null ? Visibility.Collapsed : Visibility.Visible;
            MsgAction.Content = action;
            _msgAction = onAction;
            if (_expanded) return; // pet messages while expanded: the pet card already reacts
            ShowTransient(View.Message, seconds);
        }

        Action? _msgAction;
        void MsgAction_Click(object sender, RoutedEventArgs e) { _msgAction?.Invoke(); _transient.Stop(); RevertToBase(); }

        // ---------- interaction ----------

        void OnHover(bool over)
        {
            if (over)
            {
                _collapse.Stop();
                _hoverOut.Stop();
                if (_view == View.Dock && !_expanded) _hoverIn.Start();
                else if (!_expanded && _alert == null && !_s.ReduceMotion) ScalePill(1.04);
            }
            else
            {
                _hoverIn.Stop();
                ScalePill(1.0);
                if (_peeking) _hoverOut.Start();
                if (_expanded) { _collapse.Interval = TimeSpan.FromMilliseconds(700); _collapse.Start(); }
            }
        }

        void ScalePill(double s)
        {
            var a = new DoubleAnimation(s, TimeSpan.FromMilliseconds(220)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } };
            PillScale.BeginAnimation(ScaleTransform.ScaleXProperty, a);
            PillScale.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        void Pill_Click(object sender, MouseButtonEventArgs e)
        {
            if (_expanded || _alert != null) return;
            // Questions held back by Do not disturb (nothing else is queued while no alert shows): click to answer.
            if (_pendingAlerts.Count > 0) { ReleaseHeld(); return; }
            switch (_view)
            {
                case View.Activity:
                    // Always open the island (last-used tab). Jumping to the session's window is on the
                    // peek's "Open" button and on each row in the Dev tab.
                    Expand(_tab); return;
                case View.Notify:
                    _transient.Stop(); RevertToBase(); return;
                case View.Media:
                    Expand(Tab.Home); return;
                case View.Timer:
                    Expand(Tab.Focus); return;
                default:
                    Expand(_tab); return;
            }
        }

        void Pill_MouseWheel(object sender, MouseWheelEventArgs e)
        {
            if (_expanded)
            {
                if (Header.IsMouseOver) { SelectTab((Tab)Math.Clamp((int)_tab + (e.Delta < 0 ? 1 : -1), 0, (int)Tab.Tools)); e.Handled = true; }
                else if (_tab == Tab.Home) _osd.NudgeVolume(e.Delta > 0 ? 0.04f : -0.04f);
                return;
            }
            _osd.NudgeVolume(e.Delta > 0 ? 0.04f : -0.04f);
        }

        public Hydration Water => _water;

        /// <summary>Plays a fake Claude Code session so you can see the dev features without setup.</summary>
        public void RunDemo()
        {
            const string id = "demo";
            OnActivity(new Activity { Id = id, Title = "Claude · demo", Body = "Refactoring the media service", Icon = "claude", State = "running" });
            QueueAfter(4, () =>
            {
                var q = new AskRequest { Title = "Allow Bash? · demo", Body = "npm run build", Buttons = new[] { "Allow", "Deny" }, Icon = "claude", Timeout = 30 };
                ShowAlert(new AlertInfo { Kind = AlertKind.Ask, Ask = q }, q.Title, q.Body, "Allow", "Deny", IconGlyph("claude"), IconBrush("claude"), AppIcons.ForIcon("claude"));
                q.Answer.Task.ContinueWith(t => Dispatcher.BeginInvoke(() => QueueAfter(2.5, () =>
                    OnActivity(new Activity
                    {
                        Id = id, Icon = "claude", State = "done", Ttl = 8,
                        Title = t.Result == "Deny" ? "Claude stopped · demo" : "Claude finished · demo",
                        Body = t.Result == "Deny" ? "You denied the build" : "Build passed, 3 files changed",
                    }))));
            });
        }

        void Expand(Tab tab)
        {
            if (_hiddenForFullscreen) return;
            _expanded = true;
            _peeking = false;
            _transient.Stop();
            ScalePill(1.0);
            SelectTab(tab, animate: false);
            RefreshExpanded();
            Show(View.Expanded);
            _collapse.Interval = TimeSpan.FromSeconds(Pill.IsMouseOver ? 0.7 : 6);
            if (!Pill.IsMouseOver) _collapse.Start();
        }

        void Collapse()
        {
            if (!_expanded) return;
            EndTyping();
            _expanded = false;
            RevertToBase();
        }

        public void ToggleExpanded()
        {
            if (_expanded) Collapse();
            else Expand(_tab);
        }

        void Settings_Click(object s, RoutedEventArgs e) => SettingsRequested?.Invoke();

        // ---------- tabs ----------

        void Tab_Checked(object sender, RoutedEventArgs e)
        {
            if (_views == null) return; // during InitializeComponent
            Tab t = sender == TabWater ? Tab.Water : sender == TabFocus ? Tab.Focus : sender == TabShelf ? Tab.Shelf
                  : sender == TabClip ? Tab.Clip : sender == TabSystem ? Tab.System : sender == TabDev ? Tab.Dev
                  : sender == TabTools ? Tab.Tools : Tab.Home;
            SelectTab(t);
        }

        void SelectTab(Tab t, bool animate = true)
        {
            _tab = t;
            var rb = t switch { Tab.Water => TabWater, Tab.Focus => TabFocus, Tab.Shelf => TabShelf, Tab.Clip => TabClip, Tab.System => TabSystem, Tab.Dev => TabDev, Tab.Tools => TabTools, _ => TabHome };
            if (rb.IsChecked != true) rb.IsChecked = true;
            double x = -(int)t * 600;
            if (animate && !_s.ReduceMotion)
                PageShift.BeginAnimation(TranslateTransform.XProperty, new DoubleAnimation(x, TimeSpan.FromMilliseconds(320)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            else { PageShift.BeginAnimation(TranslateTransform.XProperty, null); PageShift.X = x; }
            Glow.Visibility = t == Tab.Home ? Visibility.Visible : Visibility.Hidden;
            RefreshExpanded();
            OnViewShown();
        }

        void RefreshExpanded()
        {
            UpdateHeader();
            switch (_tab)
            {
                case Tab.Home: UpdateProgress(); RefreshWater(false); UpdateClock(); break;
                case Tab.Water: RefreshWater(false); break;
                case Tab.Focus: UpdateTimer(); RefreshReminders(); break;
                case Tab.Shelf: RefreshShelf(); break;
                case Tab.Clip: RefreshClipList(); break;
                case Tab.System: SampleSystem(); break;
                case Tab.Dev: RefreshDevList(); break;
            }
        }

        // ---------- typing (reminder box) needs real keyboard focus ----------

        void BeginTyping(TextBox box)
        {
            if (_typing) return;
            _typing = true;
            Native.SetNoActivate(_hwnd, false);
            Activate();
            box.Focus();
            Keyboard.Focus(box);
        }

        void EndTyping()
        {
            if (!_typing) return;
            _typing = false;
            Native.SetNoActivate(_hwnd, true);
            Keyboard.ClearFocus();
        }

        // ---------- heartbeat ----------

        void OnTick()
        {
            _ticks++;
            UpdateTimer();
            if (_expanded)
            {
                UpdateHeader();
                if (_tab == Tab.Home) { UpdateProgress(); UpdateClock(); }
                if (_tab == Tab.System) SampleSystem();
                if (_tab == Tab.Dev && _ticks % 2 == 0) RefreshDevList();
            }
            if (_view == View.Peek) UpdatePeek();
            if (_view == View.Activity) UpdateActivityView();
            if (!_hiddenForFullscreen) CheckMediaInFront();

            if (_ticks % 2 == 0)
            {
                bool fs = _s.HideInFullscreen && Native.IsFullscreenAppRunning();
                if (fs != _hiddenForFullscreen)
                {
                    _hiddenForFullscreen = fs;
                    Visibility = fs ? Visibility.Hidden : Visibility.Visible;
                    if (fs) { _expanded = false; _peeking = false; _transient.Stop(); EndTyping(); }
                    RevertToBase();
                }
                if (_s.ShowPrivacy) CheckPrivacy();
                ExpireActivities();
                bool quiet = Quiet;
                if (_wasQuiet && !quiet) ReleaseHeld();   // Do not disturb ended: questions still waiting show now
                _wasQuiet = quiet;
            }
            if (_ticks % 15 == 0) { CheckWater(); CheckReminders(); }
            if (_ticks % 1200 == 0) _ = RefreshWeatherAsync();
            if (_ticks % 300 == 0) { Topmost = false; Topmost = true; }
        }

        // ---------- helpers ----------

        static Geometry Arc(double fraction, double size, double stroke)
        {
            fraction = Math.Clamp(fraction, 0, 0.9999);
            if (fraction <= 0.001) return Geometry.Empty;
            double r = (size - stroke) / 2, c = size / 2, a = fraction * 2 * Math.PI;
            var fig = new PathFigure { StartPoint = new Point(c, c - r), IsClosed = false };
            fig.Segments.Add(new ArcSegment(new Point(c + r * Math.Sin(a), c - r * Math.Cos(a)), new Size(r, r), 0, fraction > 0.5, SweepDirection.Clockwise, true));
            var g = new PathGeometry(); g.Figures.Add(fig); g.Freeze();
            return g;
        }

        static readonly FontFamily SymbolFont = new("Segoe UI Symbol");

        /// <summary>Icon-font glyphs live in the Private Use Area; anything else (e.g. Claude's ✳) needs a symbol font.</summary>
        void SetGlyph(TextBlock tb, string glyph)
        {
            tb.Text = glyph;
            tb.FontFamily = glyph.Length > 0 && glyph[0] < '' ? SymbolFont : (FontFamily)FindResource("IconFont");
        }

        static Brush Frozen(Color c) { var b = new SolidColorBrush(c); b.Freeze(); return b; }
        static Color WithAlpha(Brush b, byte a) => b is SolidColorBrush s ? Color.FromArgb(a, s.Color.R, s.Color.G, s.Color.B) : Color.FromArgb(a, 255, 255, 255);
        static void SetWidth(FrameworkElement fill, FrameworkElement track, double fraction) => fill.Width = Math.Max(0, track.ActualWidth * Math.Clamp(fraction, 0, 1));
    }
}
