using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;

namespace DynamicIsland.Services
{
    public sealed record ToastInfo(string App, string Title, string Body, string AppId, DateTime Arrived);

    /// <summary>
    /// Shows other apps' Windows toasts without package identity: unpackaged apps can't use
    /// UserNotificationListener, so this reads the system notification database
    /// (%LOCALAPPDATA%\Microsoft\Windows\Notifications\wpndatabase.db) read-only and reports new toasts.
    /// </summary>
    public sealed class NotificationService : IDisposable
    {
        static readonly string DbDir = Environment.ExpandEnvironmentVariables(@"%LOCALAPPDATA%\Microsoft\Windows\Notifications");
        static readonly string DbPath = Path.Combine(DbDir, "wpndatabase.db");

        FileSystemWatcher? _watcher;
        Timer? _debounce, _poll;
        long _lastId;
        bool _baseline, _disposed;
        int _busy;

        /// <summary>Raised on a background thread for each new toast.</summary>
        public event Action<ToastInfo>? Arrived;

        public void Start()
        {
            try
            {
                if (!File.Exists(DbPath)) return;
                try { SQLitePCL.Batteries_V2.Init(); } catch { }

                // Remember where the database is now so existing toasts aren't replayed.
                if (QueryMaxId() is long max) { _lastId = max; _baseline = true; }

                _debounce = new Timer(_ => Check(), null, Timeout.Infinite, Timeout.Infinite);
                _poll = new Timer(_ => Check(), null, 5000, 5000);
                _watcher = new FileSystemWatcher(DbDir, "wpndatabase.db*")
                {
                    NotifyFilter = NotifyFilters.LastWrite | NotifyFilters.Size,
                    EnableRaisingEvents = true,
                };
                _watcher.Changed += (_, _) => _debounce?.Change(300, Timeout.Infinite);
            }
            catch { /* notifications simply won't show */ }
        }

        static SqliteConnection Open()
        {
            var c = new SqliteConnection($"Data Source={DbPath};Mode=ReadOnly;Cache=Shared;Default Timeout=2");
            c.Open();
            return c;
        }

        static long? QueryMaxId()
        {
            try
            {
                using var c = Open();
                using var cmd = c.CreateCommand();
                cmd.CommandText = "SELECT IFNULL(MAX(Id), 0) FROM Notification";
                return Convert.ToInt64(cmd.ExecuteScalar());
            }
            catch { return null; } // busy / locked: try again on the next tick
        }

        void Check()
        {
            if (_disposed || Interlocked.Exchange(ref _busy, 1) == 1) return;
            try
            {
                if (!_baseline)
                {
                    if (QueryMaxId() is long max) { _lastId = max; _baseline = true; }
                    return;
                }

                using var c = Open();
                using var cmd = c.CreateCommand();
                cmd.CommandText =
                    "SELECT n.Id, n.Payload, n.ArrivalTime, h.PrimaryId " +
                    "FROM Notification n LEFT JOIN NotificationHandler h ON h.RecordId = n.HandlerId " +
                    "WHERE n.Type = 'toast' AND n.Id > $id ORDER BY n.Id LIMIT 20";
                cmd.Parameters.AddWithValue("$id", _lastId);

                using var r = cmd.ExecuteReader();
                while (r.Read())
                {
                    long id = r.GetInt64(0);
                    if (id > _lastId) _lastId = id;

                    var raw = r.IsDBNull(1) ? null : r.GetValue(1);
                    string xml = raw is byte[] b ? Encoding.UTF8.GetString(b) : raw?.ToString() ?? "";
                    var (title, body) = ParsePayload(xml);
                    if (string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(body)) continue;
                    if (string.IsNullOrWhiteSpace(title)) { title = body; body = ""; }

                    DateTime arrived = DateTime.Now;
                    if (!r.IsDBNull(2))
                    {
                        try { arrived = DateTime.FromFileTimeUtc(r.GetInt64(2)).ToLocalTime(); } catch { }
                    }
                    string appId = r.IsDBNull(3) ? "" : r.GetString(3);
                    Arrived?.Invoke(new ToastInfo(Friendly(appId), title, body, appId, arrived));
                }
            }
            catch (SqliteException) { /* SQLITE_BUSY / locked while Windows writes; next tick retries */ }
            catch { }
            finally { Interlocked.Exchange(ref _busy, 0); }
        }

        /// <summary>First &lt;text&gt; = title, the rest (minus attribution) = body.</summary>
        static (string title, string body) ParsePayload(string xml)
        {
            try
            {
                xml = xml.Trim('\0', '﻿', ' ', '\r', '\n');
                if (xml.Length == 0) return ("", "");
                var doc = XDocument.Parse(xml);
                var binding = doc.Descendants().FirstOrDefault(e => e.Name.LocalName == "binding") ?? doc.Root;
                if (binding == null) return ("", "");
                var texts = binding.Descendants()
                    .Where(e => e.Name.LocalName == "text" && (string?)e.Attribute("placement") != "attribution")
                    .Select(e => e.Value.Trim())
                    .Where(t => t.Length > 0)
                    .ToList();
                if (texts.Count == 0) return ("", "");
                return (texts[0], string.Join(" ", texts.Skip(1)));
            }
            catch { return ("", ""); }
        }

        /// <summary>"MSTeams_8wekyb3d8bbwe!MSTeams" → "Teams", "Microsoft.WindowsTerminal_..." → "Terminal".</summary>
        public static string Friendly(string appId)
        {
            if (string.IsNullOrWhiteSpace(appId)) return "Notification";
            string s = appId.Trim();

            // Win32 AUMIDs can be paths like "{GUID}\App\app.exe"
            if (s.Contains('\\') || s.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
            {
                var file = Path.GetFileNameWithoutExtension(s.Split('!')[0]);
                if (!string.IsNullOrEmpty(file)) s = file;
            }

            string pkg = s.Split('!')[0].Split('_')[0];
            string l = s.ToLowerInvariant();

            (string key, string name)[] known =
            {
                ("msteams", "Teams"), ("teams", "Teams"), ("whatsapp", "WhatsApp"), ("telegram", "Telegram"),
                ("spotify", "Spotify"), ("msedge", "Edge"), ("microsoftedge", "Edge"), ("chrome", "Chrome"),
                ("firefox", "Firefox"), ("brave", "Brave"), ("opera", "Opera"), ("outlook", "Outlook"),
                ("windowsterminal", "Terminal"), ("discord", "Discord"), ("slack", "Slack"), ("zoom", "Zoom"),
                ("vscode", "VS Code"), ("visualstudio", "Visual Studio"), ("windows.explorer", "Explorer"),
                ("windowsstore", "Store"), ("securitycenter", "Security"), ("windowsdefender", "Security"),
                ("people", "People"), ("windowscommunicationsapps", "Mail"), ("yourphone", "Phone Link"),
            };
            foreach (var (key, name) in known)
                if (l.Contains(key)) return name;

            // Fallback: last readable dotted segment, minus "Microsoft"/"Windows" prefixes.
            var segs = pkg.Split('.', StringSplitOptions.RemoveEmptyEntries)
                          .Where(x => !x.Equals("exe", StringComparison.OrdinalIgnoreCase) &&
                                      !x.Equals("app", StringComparison.OrdinalIgnoreCase))
                          .ToList();
            string last = segs.Count > 0 ? segs[^1] : pkg;
            foreach (var prefix in new[] { "Microsoft", "Windows" })
                if (last.Length > prefix.Length && last.StartsWith(prefix, StringComparison.Ordinal))
                    last = last[prefix.Length..];
            if (last.Length == 0) return "Notification";
            return char.ToUpperInvariant(last[0]) + last[1..];
        }

        public void Dispose()
        {
            _disposed = true;
            try { if (_watcher != null) { _watcher.EnableRaisingEvents = false; _watcher.Dispose(); } } catch { }
            _debounce?.Dispose();
            _poll?.Dispose();
        }
    }
}
