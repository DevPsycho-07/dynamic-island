using System;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;
using Windows.Media.Control;
using GsmtcManager = Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager;
using GsmtcSession = Windows.Media.Control.GlobalSystemMediaTransportControlsSession;

namespace DynamicIsland.Services
{
    public sealed record MediaInfo(string Title, string Artist, string App, bool Playing, BitmapImage? Art,
                                   TimeSpan Position, TimeSpan Duration, DateTimeOffset PositionStamp);

    /// <summary>Now-playing info from any app that talks to Windows media controls (Spotify, browsers, etc.).</summary>
    public sealed class MediaService
    {
        GsmtcManager? _mgr;
        GsmtcSession? _session;
        string _lastTrackKey = "";
        BitmapImage? _art;
        string _artKey = "";

        /// <summary>Raised on a background thread. Null info = nothing playing.</summary>
        public event Action<MediaInfo?, bool>? Changed; // (info, isNewTrack)

        public async Task StartAsync()
        {
            try
            {
                _mgr = await GsmtcManager.RequestAsync();
                _mgr.CurrentSessionChanged += (_, _) => Hook(_mgr.GetCurrentSession());
                _mgr.SessionsChanged += (_, _) => Hook(_mgr.GetCurrentSession());
                Hook(_mgr.GetCurrentSession());
            }
            catch { /* media controls unavailable */ }
        }

        void Hook(GsmtcSession? s)
        {
            if (ReferenceEquals(s, _session) && s != null) { _ = RefreshAsync(); return; }
            if (_session != null)
            {
                _session.MediaPropertiesChanged -= OnProps;
                _session.PlaybackInfoChanged -= OnPlayback;
                _session.TimelinePropertiesChanged -= OnTimeline;
            }
            _session = s;
            if (s != null)
            {
                s.MediaPropertiesChanged += OnProps;
                s.PlaybackInfoChanged += OnPlayback;
                s.TimelinePropertiesChanged += OnTimeline;
            }
            _ = RefreshAsync();
        }

        void OnProps(GsmtcSession s, MediaPropertiesChangedEventArgs e) => _ = RefreshAsync();
        void OnPlayback(GsmtcSession s, PlaybackInfoChangedEventArgs e) => _ = RefreshAsync();
        void OnTimeline(GsmtcSession s, TimelinePropertiesChangedEventArgs e) => _ = RefreshAsync();

        async Task RefreshAsync()
        {
            var s = _session;
            if (s == null) { _lastTrackKey = ""; Changed?.Invoke(null, false); return; }
            try
            {
                var props = await s.TryGetMediaPropertiesAsync();
                var pb = s.GetPlaybackInfo();
                var tl = s.GetTimelineProperties();
                string title = props?.Title ?? "";
                string artist = props?.Artist ?? "";
                if (string.IsNullOrWhiteSpace(title)) { Changed?.Invoke(null, false); return; }

                string key = title + "|" + artist;
                if (key != _artKey)
                {
                    _artKey = key;
                    _art = await LoadArtAsync(props);
                }
                bool newTrack = key != _lastTrackKey;
                _lastTrackKey = key;

                bool playing = pb?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
                var info = new MediaInfo(title, artist, PrettyApp(s.SourceAppUserModelId), playing, _art,
                    tl?.Position ?? TimeSpan.Zero, (tl?.EndTime ?? TimeSpan.Zero) - (tl?.StartTime ?? TimeSpan.Zero),
                    tl?.LastUpdatedTime ?? DateTimeOffset.Now);
                Changed?.Invoke(info, newTrack);
            }
            catch { }
        }

        static async Task<BitmapImage?> LoadArtAsync(GlobalSystemMediaTransportControlsSessionMediaProperties? props)
        {
            try
            {
                if (props?.Thumbnail == null) return null;
                using var ras = await props.Thumbnail.OpenReadAsync();
                using var src = ras.AsStreamForRead();
                var ms = new MemoryStream();
                await src.CopyToAsync(ms);
                ms.Position = 0;
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.DecodePixelWidth = 160;
                bmp.StreamSource = ms;
                bmp.EndInit();
                bmp.Freeze();
                return bmp;
            }
            catch { return null; }
        }

        static string PrettyApp(string id)
        {
            if (string.IsNullOrEmpty(id)) return "";
            var l = id.ToLowerInvariant();
            if (l.Contains("spotify")) return "Spotify";
            if (l.Contains("msedge")) return "Edge";
            if (l.Contains("chrome")) return "Chrome";
            if (l.Contains("firefox")) return "Firefox";
            if (l.Contains("vlc")) return "VLC";
            if (l.Contains("zune") || l.Contains("media")) return "Media Player";
            return Path.GetFileNameWithoutExtension(id.Split('!')[0]);
        }

        public void PlayPause() => _ = _session?.TryTogglePlayPauseAsync();
        public void Next() => _ = _session?.TrySkipNextAsync();
        public void Previous() => _ = _session?.TrySkipPreviousAsync();
        public void Seek(TimeSpan pos) => _ = _session?.TryChangePlaybackPositionAsync(pos.Ticks);
    }
}
