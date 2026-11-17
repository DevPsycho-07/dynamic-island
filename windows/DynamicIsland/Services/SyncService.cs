using System;
using System.IO;
using System.Windows.Threading;
using DynamicIsland.Pets;

namespace DynamicIsland.Services
{
    /// <summary>
    /// Laptop ⇄ phone sync through stock TwinDesk's "Shared folders": TwinDesk shows a PC folder
    /// (default %USERPROFILE%\MacShare) on the phone. Each device writes ONLY its own file
    /// (pc.json / phone.json) and merges the other's, so nothing is ever overwritten.
    /// Runs on the UI thread (Hydration isn't thread-safe); file work is tiny.
    /// </summary>
    public sealed class SyncService : IDisposable
    {
        public const string MyFile = "pc.json", PhoneFile = "phone.json";
        public static string TwinDeskShare => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "MacShare");
        public static string DefaultFolder => Path.Combine(TwinDeskShare, "DynamicIsland");

        readonly Settings _s;
        readonly Hydration _h;
        readonly Dispatcher _ui;
        readonly DispatcherTimer _poll = new() { Interval = TimeSpan.FromSeconds(30) };
        readonly DispatcherTimer _debounce = new() { Interval = TimeSpan.FromSeconds(2) };
        FileSystemWatcher? _fsw;
        DateTime _phoneStamp;

        public DateTime? LastPush { get; private set; }
        public DateTime? LastPhoneSeen { get; private set; }
        public string? LastError { get; private set; }

        /// <summary>Raised after the phone's changes were merged in (UI thread).</summary>
        public event Action? Merged;

        public SyncService(Settings s, Hydration h, Dispatcher ui)
        {
            _s = s; _h = h; _ui = ui;
            _poll.Tick += (_, _) => { Pull(); };
            _debounce.Tick += (_, _) => { _debounce.Stop(); Push(); };
        }

        public string Folder => string.IsNullOrWhiteSpace(_s.SyncFolder) ? DefaultFolder : _s.SyncFolder;

        /// <summary>The default folder lives inside TwinDesk's share; only use it once that share exists.</summary>
        public bool FolderUsable => !string.IsNullOrWhiteSpace(_s.SyncFolder) ? true : Directory.Exists(TwinDeskShare);

        public void Start()
        {
            Stop();
            if (!_s.SyncEnabled || !FolderUsable) return;
            try
            {
                Directory.CreateDirectory(Folder);
                _fsw = new FileSystemWatcher(Folder, PhoneFile) { NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.FileName | NotifyFilters.Size };
                _fsw.Changed += (_, _) => _ui.BeginInvoke(() => Pull());
                _fsw.Created += (_, _) => _ui.BeginInvoke(() => Pull());
                _fsw.Renamed += (_, _) => _ui.BeginInvoke(() => Pull());
                _fsw.EnableRaisingEvents = true;
            }
            catch (Exception ex) { LastError = ex.Message; }
            _poll.Start();
            Pull();
            Push();
        }

        public void Stop()
        {
            _poll.Stop();
            _debounce.Stop();
            if (_fsw != null) { _fsw.EnableRaisingEvents = false; _fsw.Dispose(); _fsw = null; }
        }

        /// <summary>Something changed locally — write our file soon.</summary>
        public void RequestPush()
        {
            if (!_s.SyncEnabled || !FolderUsable) return;
            _debounce.Stop();
            _debounce.Start();
        }

        public void Push()
        {
            if (!_s.SyncEnabled || !FolderUsable) return;
            try
            {
                Directory.CreateDirectory(Folder);
                string path = Path.Combine(Folder, MyFile), tmp = path + ".tmp";
                File.WriteAllText(tmp, _h.ExportSync());
                File.Move(tmp, path, true);
                LastPush = DateTime.Now;
                LastError = null;
            }
            catch (Exception ex) { LastError = ex.Message; }
        }

        public bool Pull()
        {
            if (!_s.SyncEnabled || !FolderUsable) return false;
            string path = Path.Combine(Folder, PhoneFile);
            try
            {
                if (!File.Exists(path)) return false;
                var stamp = File.GetLastWriteTimeUtc(path);
                LastPhoneSeen = stamp.ToLocalTime();
                if (stamp == _phoneStamp) return false;
                string json = ReadShared(path);
                _phoneStamp = stamp;
                if (!_h.ImportSync(json)) return false;
                Merged?.Invoke();
                RequestPush();   // echo the merged state so both files converge
                return true;
            }
            catch (Exception ex) { LastError = ex.Message; return false; }
        }

        /// <summary>The phone may be mid-write; retry briefly and allow shared access.</summary>
        static string ReadShared(string path)
        {
            for (int i = 0; ; i++)
            {
                try
                {
                    using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var sr = new StreamReader(fs);
                    return sr.ReadToEnd();
                }
                catch (IOException) when (i < 4) { System.Threading.Thread.Sleep(150); }
            }
        }

        public void Dispose() => Stop();
    }
}
