using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using DynamicIsland.Services;

namespace DynamicIsland
{
    /// <summary>Now playing: compact + full player, audio-reactive bars, album-art glow, seek.</summary>
    public partial class IslandWindow
    {
        MediaInfo? _media;
        bool _mediaSeen;
        readonly DispatcherTimer _viz = new(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(33) };
        readonly double[] _barH = new double[5], _barNoise = new double[5];
        static readonly Random Rng = new();

        void InitMedia()
        {
            foreach (var panel in new[] { Eq, BigEq })
            {
                for (int i = 0; i < 5; i++)
                {
                    panel.Children.Add(new Rectangle
                    {
                        Width = 3, Height = panel.Height, RadiusX = 1.5, RadiusY = 1.5, Margin = new Thickness(1, 0, 1, 0),
                        Fill = WaterB, RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform(1, 0.25),
                    });
                }
            }
            _viz.Tick += (_, _) => VisualizerFrame();
            ShowNoMedia();
        }

        void OnMedia(MediaInfo? info, bool newTrack)
        {
            bool artChanged = !ReferenceEquals(info?.Art, _media?.Art);
            _media = info;
            _mediaInFront = MediaAppInFront();
            if (info == null) { ShowNoMedia(); RevertToBaseUnlessTransient(); return; }

            MiniTitle.Text = info.Title;
            MiniArt.Source = info.Art;
            BigTitle.Text = info.Title;
            BigArtist.Text = info.Artist;
            BigApp.Text = string.IsNullOrEmpty(info.App) ? "NOW PLAYING" : info.App.ToUpperInvariant();
            BigArt.Source = info.Art;
            PlayBtn.Content = info.Playing ? "" : "";
            SeekBar.Visibility = info.Duration > TimeSpan.Zero ? Visibility.Visible : Visibility.Hidden;
            MediaPanel.Visibility = Visibility.Visible;
            ClockPanel.Visibility = Visibility.Collapsed;
            if (artChanged) ApplyArt(info.Art);
            UpdateProgress();

            if (newTrack && _mediaSeen && info.Playing && _s.ShowMedia && !_expanded && _alert == null && !_mediaInFront)
            {
                _mediaSeen = true;
                ShowMessage("", WhiteB, info.Title, info.Artist, "", 2.6, info.Art);
                return;
            }
            _mediaSeen = true;
            RevertToBaseUnlessTransient();
        }

        // ---------- the player is the window you're looking at ----------

        bool _mediaInFront;

        static readonly HashSet<string> Browsers = new(StringComparer.OrdinalIgnoreCase)
        {
            "chrome", "msedge", "firefox", "brave", "opera", "vivaldi", "arc", "chromium", "librewolf", "waterfox", "zen", "thorium", "floorp",
        };

        /// <summary>
        /// True when the app playing is the focused window (YouTube tab in front, Spotify focused…): then the
        /// island shows no now-playing pill for it — only once it's in the background.
        /// Browsers count only when the playing tab is the one showing (its title is the window title).
        /// </summary>
        bool MediaAppInFront()
        {
            var m = _media;
            if (m == null || !m.Playing) return false;
            var fg = Native.GetForegroundApp();
            if (fg == null || fg.Pid == (uint)Environment.ProcessId || fg.Process.Length == 0) return false;
            if (fg.Aumid != null && fg.Aumid.Equals(m.SourceId, StringComparison.OrdinalIgnoreCase)) return true;
            bool titleShown = m.Title.Length >= 3 && fg.Title.Contains(m.Title, StringComparison.OrdinalIgnoreCase);
            if (Browsers.Contains(fg.Process)) return titleShown;
            if (fg.Process.Length >= 3 && m.SourceId.Contains(fg.Process, StringComparison.OrdinalIgnoreCase)) return true;
            return titleShown;
        }

        /// <summary>Heartbeat: switching to / away from the player shows or hides its pill.</summary>
        void CheckMediaInFront()
        {
            bool now = _s.ShowMedia && MediaAppInFront();
            if (now == _mediaInFront) return;
            _mediaInFront = now;
            if (!_expanded) RevertToBaseUnlessTransient();
        }

        void RevertToBaseUnlessTransient()
        {
            if (_view is View.Level or View.Message or View.Notify) { OnViewShown(); return; }
            RevertToBase();
        }

        void ShowNoMedia()
        {
            BigArt.Source = null;
            Glow.Source = null;
            Glow.Opacity = 0;
            MediaPanel.Visibility = Visibility.Collapsed;
            ClockPanel.Visibility = Visibility.Visible;
            UpdateClock();
        }

        void UpdateProgress()
        {
            var m = _media;
            if (m == null || m.Duration <= TimeSpan.Zero) return;
            var pos = m.Position + (m.Playing ? DateTimeOffset.Now - m.PositionStamp : TimeSpan.Zero);
            if (pos < TimeSpan.Zero) pos = TimeSpan.Zero;
            if (pos > m.Duration) pos = m.Duration;
            PosText.Text = Fmt(pos);
            DurText.Text = "-" + Fmt(m.Duration - pos);
            SetWidth(ProgFill, ProgTrack, pos.TotalSeconds / m.Duration.TotalSeconds);
        }

        static string Fmt(TimeSpan t) => t.TotalHours >= 1 ? t.ToString(@"h\:mm\:ss") : t.ToString(@"m\:ss");

        void Seek_MouseDown(object sender, MouseButtonEventArgs e)
        {
            var m = _media;
            if (m == null || m.Duration <= TimeSpan.Zero || ProgTrack.ActualWidth <= 0) return;
            double f = Math.Clamp(e.GetPosition(ProgTrack).X / ProgTrack.ActualWidth, 0, 1);
            var target = TimeSpan.FromSeconds(m.Duration.TotalSeconds * f);
            _mediaSvc.Seek(target);
            _media = m with { Position = target, PositionStamp = DateTimeOffset.Now };
            UpdateProgress();
            e.Handled = true;
        }

        void Prev_Click(object s, RoutedEventArgs e) => _mediaSvc.Previous();
        void Next_Click(object s, RoutedEventArgs e) => _mediaSvc.Next();
        void Play_Click(object s, RoutedEventArgs e) => _mediaSvc.PlayPause();

        /// <summary>Accent colour from the cover + a soft glow: the art decoded at 8 px and stretched = free blur.</summary>
        void ApplyArt(BitmapSource? art)
        {
            Brush accent = WaterB;
            Glow.Source = null;
            Glow.Opacity = 0;
            if (art != null)
            {
                try
                {
                    var tiny = new TransformedBitmap(art, new ScaleTransform(8.0 / art.PixelWidth, 8.0 / art.PixelHeight));
                    tiny.Freeze();
                    Glow.Source = tiny;
                    Glow.Opacity = 0.55;

                    var src = new FormatConvertedBitmap(art, PixelFormats.Bgra32, null, 0);
                    int w = src.PixelWidth, h = src.PixelHeight, stride = w * 4;
                    var px = new byte[stride * h];
                    src.CopyPixels(px, stride, 0);
                    double r = 0, g = 0, b = 0, n = 0;
                    for (int i = 0; i < px.Length; i += 16)
                    {
                        double pb = px[i], pg = px[i + 1], pr = px[i + 2];
                        double max = Math.Max(pr, Math.Max(pg, pb)), min = Math.Min(pr, Math.Min(pg, pb));
                        double wgt = (max - min) / 255.0 + 0.05;
                        r += pr * wgt; g += pg * wgt; b += pb * wgt; n += wgt;
                    }
                    if (n > 0)
                    {
                        r /= n; g /= n; b /= n;
                        double mx = Math.Max(r, Math.Max(g, b)), mn = Math.Min(r, Math.Min(g, b));
                        if (mx - mn > 25)
                        {
                            double k = 230 / Math.Max(1, mx);
                            accent = Frozen(Color.FromRgb((byte)Math.Min(255, r * k), (byte)Math.Min(255, g * k), (byte)Math.Min(255, b * k)));
                        }
                    }
                }
                catch { }
            }
            foreach (var panel in new[] { Eq, BigEq })
                foreach (var c in panel.Children) if (c is Rectangle rect) rect.Fill = accent;
            ProgFill.Background = accent;
            BigApp.Foreground = accent;
        }

        // ---------- visualizer: driven by the real output level ----------

        void UpdateVisualizerTimer()
        {
            bool playing = _media?.Playing == true && !_hiddenForFullscreen;
            bool run = playing && (_view == View.Media || (_expanded && _tab == Tab.Home));
            if (run && !_viz.IsEnabled) _viz.Start();
            else if (!run && _viz.IsEnabled)
            {
                _viz.Stop();
                PetCard.StopDance();
                foreach (var panel in new[] { Eq, BigEq })
                    foreach (var c in panel.Children)
                        if (c is Rectangle { RenderTransform: ScaleTransform st }) st.ScaleY = 0.25;
            }
        }

        void VisualizerFrame()
        {
            double peak = _osd.Peak;
            for (int i = 0; i < 5; i++)
            {
                _barNoise[i] = _barNoise[i] * 0.55 + Rng.NextDouble() * 0.45;
                double target = Math.Clamp(Math.Pow(peak, 0.7) * (0.45 + 0.9 * _barNoise[i]), 0.18, 1);
                _barH[i] += (target - _barH[i]) * (target > _barH[i] ? 0.6 : 0.25);
            }
            var panel = _view == View.Media ? Eq : BigEq;
            int j = 0;
            foreach (var c in panel.Children)
                if (c is Rectangle { RenderTransform: ScaleTransform st }) st.ScaleY = _barH[j++ % 5];
            if (_expanded && _tab == Tab.Home && _s.PetDances) PetCard.Dance(peak);
        }
    }
}
