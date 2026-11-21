using System;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;

namespace DynamicIsland
{
    static class Native
    {
        public const int GWL_EXSTYLE = -20;
        public const int WS_EX_TOOLWINDOW = 0x80;
        public const int WS_EX_NOACTIVATE = 0x08000000;
        public const int WM_HOTKEY = 0x0312;
        public const int WM_CLIPBOARDUPDATE = 0x031D;
        public const uint MOD_ALT = 0x1, MOD_CONTROL = 0x2, MOD_NOREPEAT = 0x4000;

        [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr hWnd, int nIndex);
        [DllImport("user32.dll")] public static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);
        [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);
        [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr hWnd, int id);
        [DllImport("user32.dll")] public static extern bool AddClipboardFormatListener(IntPtr hwnd);
        [DllImport("user32.dll")] public static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
        [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
        [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
        [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
        [DllImport("user32.dll")] static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

        [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        /// <summary>
        /// Windows 11 native look for our borderless windows: system-drawn rounded corners, shadow and a subtle
        /// dark border — so the window is exactly the size of what you see (no transparent padding for a fake shadow).
        /// </summary>
        public static void ApplyWin11Frame(IntPtr hwnd, bool smallCorners = false)
        {
            try
            {
                int dark = 1; DwmSetWindowAttribute(hwnd, 20, ref dark, 4);                 // DWMWA_USE_IMMERSIVE_DARK_MODE
                int corner = smallCorners ? 3 : 2; DwmSetWindowAttribute(hwnd, 33, ref corner, 4); // DWMWA_WINDOW_CORNER_PREFERENCE: round / round-small
                int border = 0x00302A2A; DwmSetWindowAttribute(hwnd, 34, ref border, 4);    // DWMWA_BORDER_COLOR (0x00BBGGRR)
            }
            catch { /* older Windows: plain square window */ }
        }

        // 2 = busy (fullscreen app), 3 = D3D fullscreen, 4 = presentation mode
        [DllImport("shell32.dll")] static extern int SHQueryUserNotificationState(out int state);

        public static bool IsFullscreenAppRunning()
        {
            try { return SHQueryUserNotificationState(out int s) == 0 && (s == 2 || s == 3 || s == 4); }
            catch { return false; }
        }

        // ---------- Do not disturb ----------

        // Windows has no public API for this. The shell keeps the active "quiet hours" profile in a WNF state:
        // 0 = off, 1 = priority only (Windows 11's Do not disturb, focus sessions), 2 = alarms only (Windows 10 Focus assist).
        [DllImport("ntdll.dll")] static extern int NtQueryWnfStateData(ref ulong stateName, IntPtr typeId, IntPtr scope, out uint changeStamp, out int buffer, ref uint bufferSize);
        const ulong WNF_SHEL_QUIETHOURS_ACTIVE_PROFILE_CHANGED = 0x0D83063EA3BF1C75;

        /// <summary>Do not disturb (or a focus session / Focus assist) is on. Cheap enough to call any time.</summary>
        public static bool IsDoNotDisturbOn()
        {
            try
            {
                ulong name = WNF_SHEL_QUIETHOURS_ACTIVE_PROFILE_CHANGED;
                uint size = 4;
                return NtQueryWnfStateData(ref name, IntPtr.Zero, IntPtr.Zero, out _, out int profile, ref size) == 0 && size == 4 && profile != 0;
            }
            catch { return false; }
        }

        public static void SetNoActivate(IntPtr hwnd, bool on)
        {
            int ex = GetWindowLong(hwnd, GWL_EXSTYLE);
            SetWindowLong(hwnd, GWL_EXSTYLE, on ? ex | WS_EX_NOACTIVATE : ex & ~WS_EX_NOACTIVATE);
        }

        // ---------- keys / lock (Shortcuts tab) ----------

        public const byte VK_SHIFT = 0x10, VK_LWIN = 0x5B, VK_SNAPSHOT = 0x2C, VK_OEM_PERIOD = 0xBE,
                          VK_RETURN = 0x0D, VK_UP = 0x26, VK_DOWN = 0x28;

        /// <summary>Press a key combo (all down in order, then up in reverse), e.g. Win+Shift+S.</summary>
        public static void SendKeys(params byte[] keys)
        {
            static uint Ext(byte k) => k is VK_LWIN or VK_SNAPSHOT or VK_UP or VK_DOWN ? 1u : 0u; // KEYEVENTF_EXTENDEDKEY
            foreach (var k in keys) keybd_event(k, 0, Ext(k), UIntPtr.Zero);
            for (int i = keys.Length - 1; i >= 0; i--) keybd_event(keys[i], 0, Ext(keys[i]) | 2, UIntPtr.Zero); // KEYEVENTF_KEYUP
        }

        [DllImport("user32.dll")] public static extern bool LockWorkStation();

        public static IntPtr ForegroundWindow() => GetForegroundWindow();

        // ---------- which app is in front ----------

        [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr parent, EnumProc cb, IntPtr lParam);
        [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, uint flags, System.Text.StringBuilder sb, ref uint size);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern int GetApplicationUserModelId(IntPtr h, ref uint len, System.Text.StringBuilder sb);
        const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

        /// <summary>The focused window: its process name (no ".exe"), title, and AppUserModelId when it's a packaged app.</summary>
        public sealed record ForegroundApp(uint Pid, string Process, string Title, string? Aumid);

        public static ForegroundApp? GetForegroundApp()
        {
            try
            {
                var h = GetForegroundWindow();
                if (h == IntPtr.Zero) return null;
                GetWindowThreadProcessId(h, out uint pid);
                var sb = new System.Text.StringBuilder(512);
                GetWindowText(h, sb, sb.Capacity);
                string title = sb.ToString();
                var (proc, aumid) = ProcessInfo(pid);
                if (proc.Equals("ApplicationFrameHost", StringComparison.OrdinalIgnoreCase))
                {
                    // Store apps: the frame belongs to ApplicationFrameHost, the app itself is a child window's process.
                    uint child = 0;
                    EnumChildWindows(h, (w, _) =>
                    {
                        GetWindowThreadProcessId(w, out uint p);
                        if (p != pid) { child = p; return false; }
                        return true;
                    }, IntPtr.Zero);
                    if (child != 0) { pid = child; (proc, aumid) = ProcessInfo(pid); }
                }
                return new ForegroundApp(pid, proc, title, aumid);
            }
            catch { return null; }
        }

        static (string name, string? aumid) ProcessInfo(uint pid)
        {
            var h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (h == IntPtr.Zero) return ("", null);
            try
            {
                var sb = new System.Text.StringBuilder(1024);
                uint size = (uint)sb.Capacity;
                string name = QueryFullProcessImageName(h, 0, sb, ref size) ? System.IO.Path.GetFileNameWithoutExtension(sb.ToString()) : "";
                var id = new System.Text.StringBuilder(256);
                uint len = (uint)id.Capacity;
                string? aumid = GetApplicationUserModelId(h, ref len, id) == 0 ? id.ToString() : null; // only packaged apps have one
                return (name, aumid);
            }
            finally { CloseHandle(h); }
        }

        delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
        [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
        [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
        [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder sb, int max);

        /// <summary>
        /// Bring another app's window to the front (e.g. VS Code when Claude needs you).
        /// With a title hint (e.g. the project name) it picks the matching window when the app has several.
        /// </summary>
        public static bool FocusProcess(string? name, string? titleHint = null)
        {
            if (string.IsNullOrWhiteSpace(name)) return false;
            try
            {
                var procs = Process.GetProcessesByName(name.Replace(".exe", "", StringComparison.OrdinalIgnoreCase));
                if (procs.Length == 0) return false;
                IntPtr h = IntPtr.Zero;
                if (!string.IsNullOrWhiteSpace(titleHint))
                {
                    var pids = procs.Select(p => (uint)p.Id).ToHashSet();
                    EnumWindows((w, _) =>
                    {
                        if (!IsWindowVisible(w)) return true;
                        GetWindowThreadProcessId(w, out uint pid);
                        if (!pids.Contains(pid)) return true;
                        var sb = new System.Text.StringBuilder(512);
                        GetWindowText(w, sb, sb.Capacity);
                        if (sb.ToString().Contains(titleHint, StringComparison.OrdinalIgnoreCase)) { h = w; return false; }
                        return true;
                    }, IntPtr.Zero);
                }
                if (h == IntPtr.Zero) h = procs.FirstOrDefault(x => x.MainWindowHandle != IntPtr.Zero)?.MainWindowHandle ?? IntPtr.Zero;
                if (h == IntPtr.Zero) return false;
                if (IsIconic(h)) ShowWindow(h, 9); // SW_RESTORE
                keybd_event(0x12, 0, 0, UIntPtr.Zero);     // tap Alt so Windows lets us switch focus
                keybd_event(0x12, 0, 2, UIntPtr.Zero);
                return SetForegroundWindow(h);
            }
            catch { return false; }
        }
    }
}
