using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace DynamicIsland.Services
{
    /// <summary>A live activity pushed by a script, hook or tool (Claude Code, terminal, builds...).</summary>
    public sealed class Activity
    {
        public string Id { get; set; } = "";
        public string Title { get; set; } = "";
        public string? Body { get; set; }
        public string? Icon { get; set; }       // claude | terminal | build | check | error | info | git
        public string? State { get; set; }      // running | waiting | done | error
        public double? Progress { get; set; }   // 0..1, optional
        public string? Focus { get; set; }      // process name to bring forward on click, e.g. "Code", "WindowsTerminal"
        public int? Ttl { get; set; }           // seconds to keep showing (done/error default 6)
        public string? Label { get; set; }      // what it belongs to, e.g. the project folder ("Dynamic Island")
        public string? Topic { get; set; }      // what it's about, e.g. the session's first prompt (island keeps the first one)
        public string? Window { get; set; }     // window-title hint for jumping to the right window (e.g. the project name)
        public DateTime Started { get; set; } = DateTime.Now;
    }

    public sealed class AskRequest
    {
        public string Title { get; set; } = "";
        public string? Body { get; set; }
        public string[] Buttons { get; set; } = { "Allow", "Deny" };
        public string? Icon { get; set; }
        public string? Focus { get; set; }
        public string? Window { get; set; }
        public string? Activity { get; set; }   // id of the live activity this question belongs to (shows which session asks)
        public int Timeout { get; set; } = 120;
        /// <summary>
        /// Poll mode: /ask returns {"id"} at once and the caller checks /answer every ~0.6 s. If the checks
        /// stop (e.g. Claude Code killed the hook because you answered in the chat), the question is dropped.
        /// </summary>
        public bool Poll { get; set; }
        internal string Id { get; } = Guid.NewGuid().ToString("N");
        internal DateTime LastSeen { get; set; } = DateTime.UtcNow;
        internal TaskCompletionSource<string?> Answer { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    }

    /// <summary>
    /// Tiny local HTTP API on 127.0.0.1:47821 so anything can drive the island:
    ///   POST /activity  {id,title,body,icon,state,progress,focus,ttl}   start/update a live activity
    ///   POST /notify    {title,body,icon,focus}                          one-off peek
    ///   POST /ask       {title,body,buttons:[..],timeout}                waits for the user's click → {"choice":"Allow"}
    /// Every request must carry the header "X-Island: 1" — browsers can't add that cross-site,
    /// so a random web page can't poke the island.
    /// </summary>
    public sealed class IslandApi : IDisposable
    {
        public const int Port = 47821;
        readonly HttpListener _http = new();
        readonly CancellationTokenSource _cts = new();
        static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

        public event Action<Activity>? ActivityPosted;  // background thread
        public event Action<Activity>? NotifyPosted;    // background thread
        public event Action<AskRequest>? AskPosted;     // background thread; answer via Answer(req, choice)
        public event Action<string>? ShowPosted;        // background thread; "home" | "water" | ... | "close"
        public event Action<string>? ResolvePosted;     // background thread; an activity moved on — drop its pending questions
        public event Action<AskRequest>? AskAbandoned;  // background thread; a poll-mode asker stopped checking in

        readonly ConcurrentDictionary<string, AskRequest> _polls = new();
        Timer? _reaper;

        public bool Running { get; private set; }

        public void Start()
        {
            try
            {
                _http.Prefixes.Add($"http://127.0.0.1:{Port}/");
                _http.Prefixes.Add($"http://localhost:{Port}/");
                _http.Start();
                Running = true;
                _ = Task.Run(LoopAsync);
                _reaper = new Timer(_ => ReapPolls(), null, 1000, 1000);
            }
            catch { Running = false; }
        }

        async Task LoopAsync()
        {
            while (!_cts.IsCancellationRequested)
            {
                HttpListenerContext ctx;
                try { ctx = await _http.GetContextAsync(); }
                catch { break; }
                _ = Task.Run(() => HandleAsync(ctx));
            }
        }

        async Task HandleAsync(HttpListenerContext ctx)
        {
            var req = ctx.Request;
            var res = ctx.Response;
            try
            {
                if (req.Headers["X-Island"] == null || req.Headers["Origin"] != null)
                {
                    res.StatusCode = 403; res.Close(); return;
                }
                string path = req.Url?.AbsolutePath.TrimEnd('/').ToLowerInvariant() ?? "";
                if (req.HttpMethod == "GET" && path == "/ping") { await Reply(res, "{\"ok\":true}"); return; }
                if (req.HttpMethod != "POST") { res.StatusCode = 405; res.Close(); return; }

                string body;
                using (var sr = new StreamReader(req.InputStream, Encoding.UTF8)) body = await sr.ReadToEndAsync();
                if (body.Length > 64_000) { res.StatusCode = 413; res.Close(); return; }

                switch (path)
                {
                    case "/activity":
                        var a = JsonSerializer.Deserialize<Activity>(body, Json);
                        if (a == null || string.IsNullOrWhiteSpace(a.Title)) { res.StatusCode = 400; res.Close(); return; }
                        if (string.IsNullOrEmpty(a.Id)) a.Id = Guid.NewGuid().ToString("N");
                        ActivityPosted?.Invoke(a);
                        await Reply(res, $"{{\"id\":\"{a.Id}\"}}");
                        return;
                    case "/notify":
                        var n = JsonSerializer.Deserialize<Activity>(body, Json);
                        if (n == null || string.IsNullOrWhiteSpace(n.Title)) { res.StatusCode = 400; res.Close(); return; }
                        NotifyPosted?.Invoke(n);
                        await Reply(res, "{\"ok\":true}");
                        return;
                    case "/resolve":
                        // e.g. the user answered Claude's permission prompt in the terminal/chat instead of the island
                        using (var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body))
                            if (doc.RootElement.TryGetProperty("activity", out var act) && act.GetString() is string aid && aid.Length > 0)
                                ResolvePosted?.Invoke(aid);
                        await Reply(res, "{\"ok\":true}");
                        return;
                    case "/show":
                        using (var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body))
                            ShowPosted?.Invoke(doc.RootElement.TryGetProperty("tab", out var t) ? t.GetString() ?? "home" : "home");
                        await Reply(res, "{\"ok\":true}");
                        return;
                    case "/cancel":
                        // The asker learned the question was answered elsewhere (e.g. in the chat): drop just this one.
                        using (var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body))
                        {
                            string? cid = doc.RootElement.TryGetProperty("id", out var cidEl) ? cidEl.GetString() : null;
                            if (cid != null && _polls.TryRemove(cid, out var cq) && cq.Answer.TrySetResult(null)) AskAbandoned?.Invoke(cq);
                        }
                        await Reply(res, "{\"ok\":true}");
                        return;
                    case "/answer":
                        // Poll-mode check-in: {"id"} → {"pending":true} or {"pending":false,"choice":...}
                        using (var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(body) ? "{}" : body))
                        {
                            string? pid = doc.RootElement.TryGetProperty("id", out var idEl) ? idEl.GetString() : null;
                            if (pid == null || !_polls.TryGetValue(pid, out var pq)) { await Reply(res, "{\"pending\":false,\"choice\":null}"); return; }
                            pq.LastSeen = DateTime.UtcNow;
                            if (!pq.Answer.Task.IsCompleted) { await Reply(res, "{\"pending\":true}"); return; }
                            _polls.TryRemove(pid, out _);
                            await Reply(res, JsonSerializer.Serialize(new { pending = false, choice = pq.Answer.Task.Result }));
                            return;
                        }
                    case "/ask":
                        var q = JsonSerializer.Deserialize<AskRequest>(body, Json);
                        if (q == null || string.IsNullOrWhiteSpace(q.Title)) { res.StatusCode = 400; res.Close(); return; }
                        if (q.Buttons == null || q.Buttons.Length == 0) q.Buttons = new[] { "OK" };
                        if (q.Poll)
                        {
                            _polls[q.Id] = q;
                            AskPosted?.Invoke(q);
                            await Reply(res, JsonSerializer.Serialize(new { id = q.Id }));
                            return;
                        }
                        AskPosted?.Invoke(q);
                        var done = await Task.WhenAny(q.Answer.Task, Task.Delay(TimeSpan.FromSeconds(Math.Clamp(q.Timeout, 5, 600))));
                        string? choice = done == q.Answer.Task ? q.Answer.Task.Result : null;
                        await Reply(res, JsonSerializer.Serialize(new { choice }));
                        return;
                    default:
                        res.StatusCode = 404; res.Close(); return;
                }
            }
            catch
            {
                try { res.StatusCode = 400; res.Close(); } catch { }
            }
        }

        static async Task Reply(HttpListenerResponse res, string json)
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            res.ContentType = "application/json";
            res.ContentLength64 = bytes.Length;
            await res.OutputStream.WriteAsync(bytes);
            res.Close();
        }

        public static void Answer(AskRequest q, string? choice) => q.Answer.TrySetResult(choice);

        /// <summary>Poll-mode askers that stopped checking in (killed hook) → drop their questions.</summary>
        void ReapPolls()
        {
            var now = DateTime.UtcNow;
            foreach (var (id, q) in _polls)
            {
                bool answered = q.Answer.Task.IsCompleted;
                if (!answered && now - q.LastSeen > TimeSpan.FromSeconds(3.5))
                {
                    _polls.TryRemove(id, out _);
                    q.Answer.TrySetResult(null);
                    AskAbandoned?.Invoke(q);
                }
                else if (answered && now - q.LastSeen > TimeSpan.FromSeconds(30)) _polls.TryRemove(id, out _);
            }
        }

        public void Dispose()
        {
            _reaper?.Dispose();
            _cts.Cancel();
            try { _http.Stop(); _http.Close(); } catch { }
        }
    }
}
