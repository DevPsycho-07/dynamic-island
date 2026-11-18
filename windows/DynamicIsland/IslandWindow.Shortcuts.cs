using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Media;

namespace DynamicIsland
{
    /// <summary>
    /// Shortcuts: screen capture (area / window / full screen / record), everyday launchers and developer tools.
    /// All of them live on the Shortcuts tab; right-click one to pin it to the quick row on the Home tab, which
    /// is what you see as soon as you open the island. Every action closes the island first so it never ends
    /// up in a capture or in the way.
    /// </summary>
    public partial class IslandWindow
    {
        sealed record Shortcut(string Id, string Glyph, string Label, string Tip, Action Run);

        Shortcut[] _capture = Array.Empty<Shortcut>(), _general = Array.Empty<Shortcut>(), _devTools = Array.Empty<Shortcut>();
        readonly Dictionary<string, Shortcut> _shortcuts = new();

        void InitShortcuts()
        {
            _capture = new Shortcut[]
            {
                new("area", "", "Area", "Snip an area (Snipping Tool overlay, copied to the clipboard)", () => Snip("Rectangle", 0)),
                new("window", "", "Window", "Snip a window (Snipping Tool overlay in window mode)", () => Snip("Window", 1)),
                new("screen", "", "Full screen", "Snip the whole screen (Snipping Tool, copied to the clipboard)", () => Snip("Full screen", 2)),
                new("record", "", "Record", "Record the screen (Snipping Tool overlay in recording mode)", Record),
            };
            _general = new Shortcut[]
            {
                new("emoji", "", "Emoji", "Emoji & symbols (Win+.)", () => Keys(Native.VK_LWIN, Native.VK_OEM_PERIOD)),
                new("clipboard", "", "Clipboard", "Windows clipboard history (Win+V)", () => Keys(Native.VK_LWIN, (byte)'V')),
                new("desktop", "", "Desktop", "Show the desktop (Win+D)", () => Keys(Native.VK_LWIN, (byte)'D')),
                new("lock", "", "Lock", "Lock the PC (Win+L)", () => { Collapse(); Native.LockWorkStation(); }),
                new("calc", "", "Calculator", "Calculator", () => Launch("calc.exe")),
                new("notepad", "", "Notepad", "Notepad", () => Launch("notepad.exe")),
                new("explorer", "", "Explorer", "File Explorer", () => Launch("explorer.exe")),
                new("downloads", "", "Downloads", "Open the Downloads folder", () => Launch("explorer.exe", "shell:Downloads")),
                new("settings", "", "Settings", "Windows Settings", () => Launch("ms-settings:")),
            };
            _devTools = new Shortcut[]
            {
                new("terminal", "", "Terminal", "Windows Terminal", () => Terminal(admin: false)),
                new("admin", "", "Admin", "Windows Terminal as administrator", () => Terminal(admin: true)),
                new("vscode", "", "VS Code", "Visual Studio Code", VsCode),
                new("taskmgr", "", "Task Mgr", "Task Manager", () => Launch("taskmgr.exe")),
                new("env", "", "Env vars", "Edit environment variables", () => Launch("rundll32.exe", "sysdm.cpl,EditEnvironmentVariables")),
                new("hosts", "", "Hosts", "Edit the hosts file (as administrator)", () =>
                    Launch("notepad.exe", Path.Combine(Environment.SystemDirectory, @"drivers\etc\hosts"), admin: true)),
                new("services", "", "Services", "Services", () => Launch("services.msc")),
                new("regedit", "", "Registry", "Registry Editor", () => Launch("regedit.exe")),
                new("ip", "", "Copy IP", "Copy this PC's local IP address", CopyLocalIp),
            };
            _shortcuts.Clear();
            foreach (var s in _capture.Concat(_general).Concat(_devTools)) _shortcuts[s.Id] = s;
            RefreshShortcuts();
        }

        void RefreshShortcuts()
        {
            Tiles(CaptureTiles, _capture);
            Tiles(GeneralTiles, _general);
            Tiles(DevTiles, _devTools);
            BuildQuickRow();
        }

        bool IsPinned(string id) => _s.QuickShortcuts.Contains(id);

        void TogglePin(string id)
        {
            if (!_s.QuickShortcuts.Remove(id)) _s.QuickShortcuts.Add(id);
            _s.Save();
            RefreshShortcuts();
        }

        void Tiles(Panel panel, Shortcut[] items)
        {
            panel.Children.Clear();
            var iconFont = (FontFamily)FindResource("IconFont");
            var label = Frozen(Color.FromArgb(0xCC, 255, 255, 255));
            var pinnedBg = Frozen(Color.FromArgb(0x30, 0x4F, 0xC3, 0xF7));
            foreach (var s in items)
            {
                bool pinned = IsPinned(s.Id);
                var content = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                content.Children.Add(new TextBlock { Text = s.Glyph, FontFamily = iconFont, FontSize = 16, HorizontalAlignment = HorizontalAlignment.Center });
                content.Children.Add(new TextBlock
                {
                    Text = s.Label, FontSize = 10.5, Foreground = pinned ? WaterB : label, Margin = new Thickness(0, 4, 0, 0),
                    HorizontalAlignment = HorizontalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis,
                });
                var b = new Button
                {
                    Style = (Style)FindResource("TileButton"), Content = content,
                    ToolTip = s.Tip + (pinned ? "\nRight-click: remove from the Home row" : "\nRight-click: pin to the Home row"),
                };
                if (pinned) b.Background = pinnedBg;
                b.Click += (_, _) => s.Run();
                b.MouseRightButtonUp += (_, e) => { e.Handled = true; TogglePin(s.Id); }; // not the island's right-click → settings
                panel.Children.Add(b);
            }
        }

        /// <summary>The Home tab's row: pinned shortcuts in pin order, then "+" to open the Shortcuts tab.</summary>
        void BuildQuickRow()
        {
            QuickRow.Children.Clear();
            foreach (var id in _s.QuickShortcuts.ToList())
            {
                if (!_shortcuts.TryGetValue(id, out var s)) continue;
                var b = new Button { Style = (Style)FindResource("QuickButton"), Content = s.Glyph, ToolTip = s.Label + " — " + s.Tip + "\nRight-click: remove" };
                b.Click += (_, _) => s.Run();
                b.MouseRightButtonUp += (_, e) => { e.Handled = true; TogglePin(s.Id); };
                QuickRow.Children.Add(b);
            }
            var more = new Button { Style = (Style)FindResource("QuickButton"), Content = "", Foreground = (Brush)FindResource("Dim"), ToolTip = "All shortcuts — right-click one to pin it here" };
            more.Click += (_, _) => SelectTab(Tab.Tools);
            QuickRow.Children.Add(more);
        }

        // ---------- capture: always Snipping Tool's own overlay (the snip goes to the clipboard; nothing is saved) ----------

        /// <summary>Snip overlay (Win+Shift+S) with [mode] picked in its dropdown: "Rectangle", "Window", "Full screen".</summary>
        void Snip(string mode, int index) => OverlayCapture(() => Native.SendKeys(Native.VK_LWIN, Native.VK_SHIFT, (byte)'S'), mode, index);

        /// <summary>The same overlay in recording mode (Win+Shift+R).</summary>
        void Record() => OverlayCapture(() => Native.SendKeys(Native.VK_LWIN, Native.VK_SHIFT, (byte)'R'), null, 0);

        /// <summary>
        /// Hides the island (so it's neither in the frozen screen nor under your clicks), opens the overlay, picks the
        /// mode, and brings the island back once the overlay closes. Windows has no way to open the overlay in a
        /// given mode for an app like this one, so the mode is picked from the overlay's dropdown exactly as you
        /// would with the keyboard — and only while the overlay is really the window in front.
        /// </summary>
        async void OverlayCapture(Action open, string? mode, int index)
        {
            Collapse();
            Visibility = Visibility.Hidden;
            try
            {
                await Task.Delay(250);
                open();
                if (!await WaitFor(SnipOverlayInFront, 4000)) return;
                // Always pick the mode, Rectangle included: the overlay reopens in whatever mode was used last.
                if (mode != null) await Task.Run(() => PickSnipMode(mode, index));
                await WaitFor(() => !SnipOverlayInFront(), 180_000);
            }
            finally
            {
                if (!_hiddenForFullscreen) Visibility = Visibility.Visible;
            }
        }

        static async Task<bool> WaitFor(Func<bool> condition, int ms)
        {
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < ms)
            {
                if (condition()) return true;
                await Task.Delay(120);
            }
            return condition();
        }

        static bool SnipOverlayInFront()
        {
            var fg = Native.GetForegroundApp();
            if (fg == null) return false;
            if (fg.Process.Equals("ScreenClippingHost", StringComparison.OrdinalIgnoreCase)) return true;
            return fg.Process.Equals("SnippingTool", StringComparison.OrdinalIgnoreCase) && fg.Title.Contains("Overlay", StringComparison.OrdinalIgnoreCase);
        }

        /// <summary>Opens the overlay's mode dropdown (UI Automation) and moves to [mode] with the arrow keys + Enter.</summary>
        static void PickSnipMode(string mode, int fallbackIndex)
        {
            try
            {
                var root = AutomationElement.FromHandle(Native.ForegroundWindow());
                var combo = root.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, "SnippingModeComboBox"));
                if (combo == null) return;
                var selection = (SelectionPattern)combo.GetCurrentPattern(SelectionPattern.Pattern);
                // Already in that mode: leave the dropdown alone.
                var now = selection.Current.GetSelection();
                if (now.Length > 0 && now[0].Current.Name.Equals(mode, StringComparison.OrdinalIgnoreCase)) return;
                ((ExpandCollapsePattern)combo.GetCurrentPattern(ExpandCollapsePattern.Pattern)).Expand();
                Thread.Sleep(300);
                var names = combo.FindAll(TreeScope.Descendants, new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.ListItem))
                                 .Cast<AutomationElement>().Select(e => e.Current.Name).ToList();
                var sel = selection.Current.GetSelection();
                int cur = Math.Max(0, sel.Length > 0 ? names.IndexOf(sel[0].Current.Name) : 0);
                if (cur == (names.FindIndex(n => n.Equals(mode, StringComparison.OrdinalIgnoreCase)) is int t && t >= 0 ? t : fallbackIndex))
                {
                    ((ExpandCollapsePattern)combo.GetCurrentPattern(ExpandCollapsePattern.Pattern)).Collapse();
                    return;
                }
                int target = names.FindIndex(n => n.Equals(mode, StringComparison.OrdinalIgnoreCase));
                if (target < 0) target = fallbackIndex; // other languages: same order (Rectangle, Window, Full screen, Freeform)
                for (int i = 0; i < Math.Abs(target - cur); i++)
                {
                    if (!SnipOverlayInFront()) return;
                    Native.SendKeys(target > cur ? Native.VK_DOWN : Native.VK_UP);
                    Thread.Sleep(90);
                }
                if (SnipOverlayInFront()) Native.SendKeys(Native.VK_RETURN);
            }
            catch { /* overlay changed or closed: leave it as it is */ }
        }

        // ---------- launchers ----------

        /// <summary>Key combos go to the app you were in: the island never takes focus.</summary>
        void Keys(params byte[] keys)
        {
            Collapse();
            Native.SendKeys(keys);
        }

        void Launch(string file, string args = "", bool admin = false)
        {
            Collapse();
            Open(file, args, admin);
        }

        /// <summary>ShellExecute; false when Windows can't open it (or the user said no to the admin prompt).</summary>
        static bool Open(string file, string args = "", bool admin = false)
        {
            try
            {
                Process.Start(new ProcessStartInfo(file) { Arguments = args, UseShellExecute = true, Verb = admin ? "runas" : "" })?.Dispose();
                return true;
            }
            catch { return false; }
        }

        void Terminal(bool admin)
        {
            Collapse();
            if (!Open("wt.exe", "", admin)) Open("powershell.exe", "", admin);
        }

        void VsCode()
        {
            Collapse();
            var exe = new[]
            {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), @"Programs\Microsoft VS Code\Code.exe"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), @"Microsoft VS Code\Code.exe"),
            }.FirstOrDefault(File.Exists);
            if (exe != null) { Open(exe); return; }
            try { Process.Start(new ProcessStartInfo("cmd.exe", "/c code") { CreateNoWindow = true, UseShellExecute = false })?.Dispose(); }
            catch { ShowMessage("", Orange, "VS Code not found", "Install it or add 'code' to PATH", "", 2.6); }
        }

        void CopyLocalIp()
        {
            Collapse();
            string? ip = null;
            try
            {
                // A UDP "connect" sends nothing; it only asks Windows which local address routes outward.
                using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                s.Connect(IPAddress.Parse("192.0.2.1"), 9);
                ip = (s.LocalEndPoint as IPEndPoint)?.Address.ToString();
            }
            catch { }
            if (string.IsNullOrEmpty(ip)) { ShowMessage("", Orange, "No network", "Couldn't find a local IP", "", 2.4); return; }
            try { Clipboard.SetText(ip); } catch { }
            ShowMessage("", Blue, "IP copied", ip, "", 2.4);
        }
    }
}
