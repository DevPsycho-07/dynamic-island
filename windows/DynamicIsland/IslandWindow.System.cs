using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using DynamicIsland.Services;

namespace DynamicIsland
{
    /// <summary>OSD, power, notifications, Bluetooth, weather, privacy dots, header/peek, system stats.</summary>
    public partial class IslandWindow
    {
        bool _lowWarned, _criticalWarned, _fullWarned;
        string? _micApp, _camApp;
        WeatherInfo? _weather;
        readonly List<double> _cpuHist = new(), _netHist = new();

        void StartServices()
        {
            _osd.VolumeChanged += (v, muted) => Dispatcher.BeginInvoke(() =>
            {
                if (!_s.ShowVolume) return;
                string icon = muted || v == 0 ? "\uE74F" : v < 34 ? "\uE993" : v < 67 ? "\uE994" : "\uE995";
                ShowLevel(icon, muted ? 0 : v, muted ? "Mute" : v.ToString());
            });
            _osd.BrightnessChanged += b => Dispatcher.BeginInvoke(() => { if (_s.ShowBrightness) ShowLevel("\uE706", b, b.ToString()); });
            _osd.DefaultDeviceChanged += () => Dispatcher.BeginInvoke(_osd.HookDefaultDevice);
            _osd.Start();

            _power.PluggedChanged += (plugged, pct) => Dispatcher.BeginInvoke(() =>
            {
                if (plugged) { _lowWarned = _criticalWarned = false; } else _fullWarned = false;
                if (!_s.ShowPower || !_power.HasBattery) return;
                if (plugged) ShowMessage("\uE945", Green, "Charging", "Plugged in", $"{pct}%", 3);
                else ShowMessage("\uE945", Brushes.Gray, "On battery", TimeLeftText() ?? "Charger disconnected", $"{pct}%", 3);
            });
            _power.PercentChanged += pct => Dispatcher.BeginInvoke(() =>
            {
                if (!_s.ShowPower) return;
                if (_power.Plugged)
                {
                    if (pct >= 100 && !_fullWarned) { _fullWarned = true; ShowMessage("\uE945", Green, "Fully charged", "You can unplug", "100%", 3); }
                    return;
                }
                if (pct <= 10 && !_criticalWarned) { _criticalWarned = _lowWarned = true; ShowMessage("\uE7BA", Red, "Battery critical", "Plug in now", $"{pct}%", 6); }
                else if (pct <= 20 && !_lowWarned) { _lowWarned = true; ShowMessage("\uE7BA", Orange, "Low battery", TimeLeftText() ?? "Consider plugging in", $"{pct}%", 5); }
            });
            _power.EnergySaverChanged += on => Dispatcher.BeginInvoke(() =>
            {
                if (_s.ShowPower) ShowMessage("\uE945", Yellow, on ? "Energy saver on" : "Energy saver off", on ? "Saving battery" : "Back to normal", "", 3);
            });
            _power.Start();

            _mediaSvc.Changed += (info, newTrack) => Dispatcher.BeginInvoke(() => OnMedia(info, newTrack));

            _toasts.Arrived += t => Dispatcher.BeginInvoke(() => OnToast(t));
            if (_s.ShowNotifications) _toasts.Start();

            _bt.Changed += ev => Dispatcher.BeginInvoke(() =>
            {
                if (!_s.ShowBluetooth) return;
                ShowMessage("\uE702", Blue, ev.Name, ev.Connected ? "Connected" : "Disconnected",
                    ev.Battery is int b && ev.Connected ? $"{b}%" : "", 3);
            });
            _bt.Start();

            StartApi();
        }

        // ---------- volume / brightness ----------

        void ShowLevel(string icon, int value, string label)
        {
            if (_expanded || _alert != null || _hiddenForFullscreen) return;
            LevelIcon.Text = icon;
            LevelText.Text = label;
            bool first = _view != View.Level;
            ShowTransient(View.Level, 1.6);
            void apply() => LevelFill.Width = Math.Max(0, LevelTrack.ActualWidth * Math.Clamp(value, 0, 100) / 100.0);
            if (first) Dispatcher.BeginInvoke(apply, System.Windows.Threading.DispatcherPriority.Loaded); else apply();
        }

        // ---------- notifications ----------

        void OnToast(ToastInfo t)
        {
            if (!_s.ShowNotifications || _hiddenForFullscreen || _expanded || _alert != null) return;
            NotifyApp.Text = t.App.ToUpperInvariant();
            NotifyTitle.Text = string.IsNullOrWhiteSpace(t.Title) ? t.App : t.Title;
            NotifyBody.Text = t.Body;
            var icon = AppIcons.For(t.AppId);
            NotifyAppIcon.Source = icon;
            NotifyAppIcon.Visibility = icon != null ? Visibility.Visible : Visibility.Collapsed;
            NotifyInitial.Visibility = icon != null ? Visibility.Collapsed : Visibility.Visible;
            NotifyInitial.Text = t.App.Length > 0 ? t.App[..1].ToUpperInvariant() : "?";
            NotifyBadge.Background = icon != null ? Brushes.Transparent : BadgeColor(t.App);
            ShowTransient(View.Notify, 5);
        }

        static Brush BadgeColor(string name)
        {
            Color[] palette = { Color.FromRgb(0x5B, 0x8D, 0xEF), Color.FromRgb(0x34, 0xC7, 0x59), Color.FromRgb(0xFF, 0x9F, 0x0A), Color.FromRgb(0xAF, 0x52, 0xDE),
                                Color.FromRgb(0xFF, 0x37, 0x5F), Color.FromRgb(0x30, 0xB0, 0xC7), Color.FromRgb(0xD9, 0x77, 0x57) };
            int h = 0; foreach (char c in name) h = h * 31 + c;
            return Frozen(palette[Math.Abs(h) % palette.Length]);
        }

        // ---------- header / peek / clock ----------

        void UpdateHeader()
        {
            HdrTime.Text = DateTime.Now.ToString("h:mm tt");
            HdrWeather.Text = _weather == null ? "" : $"{_weather.TempC:0}°  {_weather.Description}";
            UpdateBatteryText(HdrBattGlyph, HdrBatt);
            HdrMic.Visibility = _micApp != null ? Visibility.Visible : Visibility.Collapsed;
            HdrMicText.Text = _micApp ?? "";
            HdrCam.Visibility = _camApp != null ? Visibility.Visible : Visibility.Collapsed;
            HdrCamText.Text = _camApp ?? "";
        }

        void UpdatePeek()
        {
            PeekTime.Text = DateTime.Now.ToString("h:mm tt");
            PeekWeather.Text = _weather == null ? "" : $"{_weather.TempC:0}°";
            UpdateBatteryText(PeekBattGlyph, PeekBatt);
            PeekRing.Data = Arc(_water.Fraction, 15, 2.4);
            PeekCups.Text = $"{_water.Intake / 1000.0:0.0}L";
        }

        void UpdateClock()
        {
            if (ClockPanel.Visibility != Visibility.Visible) return;
            var now = DateTime.Now;
            BigClock.Inlines.Clear();
            BigClock.Inlines.Add(new System.Windows.Documents.Run(now.ToString("h:mm")));
            BigClock.Inlines.Add(new System.Windows.Documents.Run(" " + now.ToString("tt")) { FontSize = 22, Foreground = (Brush)FindResource("Dim") });
            BigDate.Text = DateTime.Now.ToString("dddd, d MMMM");
            WeatherLine.Text = _weather == null
                ? (string.IsNullOrWhiteSpace(_s.WeatherQuery) ? "Set your city or PIN code in settings for weather" : "")
                : $"{_weather.TempC:0}°  {_weather.Description}  ·  H {_weather.HighC:0}°  L {_weather.LowC:0}°  ·  {_weather.Place}";
        }

        void UpdateBatteryText(System.Windows.Controls.TextBlock glyph, System.Windows.Controls.TextBlock text)
        {
            if (!_power.HasBattery) { glyph.Text = ""; text.Text = ""; return; }
            int p = _power.Percent;
            glyph.Text = _power.Plugged ? "\uE945" : "";
            glyph.Foreground = Green;
            text.Text = $"{p}%";
            text.Foreground = !_power.Plugged && p <= 20 ? Red : (Brush)FindResource("Dim");
        }

        string? TimeLeftText()
        {
            try
            {
                var t = Windows.System.Power.PowerManager.RemainingDischargeTime;
                if (t <= TimeSpan.Zero || t.TotalHours > 48) return null;
                return t.TotalHours >= 1 ? $"About {(int)t.TotalHours}h {t.Minutes}m left" : $"About {t.Minutes} min left";
            }
            catch { return null; }
        }

        // ---------- weather ----------

        async Task RefreshWeatherAsync()
        {
            if (string.IsNullOrWhiteSpace(_s.WeatherQuery)) { _weather = null; return; }
            var w = await _weatherSvc.GetAsync(_s.WeatherQuery);
            if (w == null) return;
            _weather = w;
            _water.HotToday = w.HighC >= 30;
            if (_expanded) RefreshExpanded();
            UpdateClock();
        }

        // ---------- mic / camera ----------

        void CheckPrivacy()
        {
            string? mic = PrivacyService.InUse("microphone");
            string? cam = PrivacyService.InUse("webcam");
            if (cam != null && cam != _camApp) ShowMessage("\uE714", Green, "Camera in use", cam, "", 2.5);
            else if (mic != null && mic != _micApp) ShowMessage("\uE720", Orange, "Microphone in use", mic, "", 2.5);
            _micApp = mic;
            _camApp = cam;
            DockDot.Visibility = mic != null || cam != null ? Visibility.Visible : Visibility.Collapsed;
            DockDot.Fill = cam != null ? Green : Orange;
        }

        // ---------- system tab ----------

        void SampleSystem()
        {
            _stats.Sample();
            SysCpu.Text = $"{_stats.Cpu * 100:0}%";
            SysRam.Text = $"{_stats.Ram * 100:0}%";
            SysRamSub.Text = $"{_stats.RamUsedGb:0.0} / {_stats.RamTotalGb:0.0} GB";
            SetWidth(SysRamFill, SysRamTrack, _stats.Ram);
            SysDown.Text = "↓ " + SystemStats.Speed(_stats.DownBps);
            SysUp.Text = "↑ " + SystemStats.Speed(_stats.UpBps);
            Push(_cpuHist, _stats.Cpu);
            Push(_netHist, _stats.DownBps + _stats.UpBps);
            Spark(SysCpuGraph, _cpuHist, 1.0);
            Spark(SysNetGraph, _netHist, Math.Max(50_000, _netHist.Max()));

            if (_power.HasBattery)
            {
                SysBatt.Text = $"{_power.Percent}%";
                SysBattSub.Text = _power.Plugged ? "Charging" : TimeLeftText() ?? "On battery";
            }
            else { SysBatt.Text = "AC"; SysBattSub.Text = "Plugged in"; }
            var up = TimeSpan.FromMilliseconds(Environment.TickCount64);
            SysUptime.Text = $"Up {(int)up.TotalHours}h {up.Minutes}m";
            try
            {
                var c = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\");
                SysDisk.Text = $"{c.Name.TrimEnd('\\')} {c.AvailableFreeSpace / 1073741824.0:0} GB free";
            }
            catch { }
        }

        static void Push(List<double> list, double v) { list.Add(v); if (list.Count > 40) list.RemoveAt(0); }

        static void Spark(System.Windows.Shapes.Polyline line, List<double> data, double max)
        {
            double w = (line.Parent as FrameworkElement)?.ActualWidth ?? 100;
            double h = 50;
            var pts = new PointCollection();
            for (int i = 0; i < data.Count; i++)
                pts.Add(new Point(w * i / 39.0, h - h * Math.Clamp(data[i] / max, 0, 1) + 4));
            pts.Freeze();
            line.Points = pts;
        }
    }
}
