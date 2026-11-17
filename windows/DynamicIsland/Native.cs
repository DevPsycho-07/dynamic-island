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

        public static void SetNoActivate(IntPtr hwnd, bool on)
        {
            int ex = GetWindowLong(hwnd, GWL_EXSTYLE);
            SetWindowLong(hwnd, GWL_EXSTYLE, on ? ex | WS_EX_NOACTIVATE : ex & ~WS_EX_NOACTIVATE);
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
