using System;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace DynamicIsland.Services
{
    /// <summary>
    /// The real icon of the app behind a notification (WhatsApp, Teams, Chrome…), looked up from its
    /// AppUserModelID through the shell's Apps folder — works for Store and classic desktop apps alike.
    /// Must be called on the UI (STA) thread. Cached per app.
    /// </summary>
    public static class AppIcons
    {
        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig] int GetImage(SIZE size, int flags, out IntPtr phbm);
        }

        [StructLayout(LayoutKind.Sequential)] struct SIZE { public int cx, cy; }

        [StructLayout(LayoutKind.Sequential)]
        struct BITMAP { public int bmType, bmWidth, bmHeight, bmWidthBytes; public ushort bmPlanes, bmBitsPixel; public IntPtr bmBits; }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr pbc, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory ppv);
        [DllImport("gdi32.dll")] static extern int GetObject(IntPtr h, int c, ref BITMAP bm);
        [DllImport("gdi32.dll")] static extern bool DeleteObject(IntPtr h);

        static readonly ConcurrentDictionary<string, BitmapSource?> Cache = new(StringComparer.OrdinalIgnoreCase);

        /// <summary>System senders whose notification ID isn't an Apps-folder entry → the app that owns them.</summary>
        static readonly (string Prefix, string Aumid)[] Aliases =
        {
            ("Windows.Defender", "Microsoft.SecHealthUI_8wekyb3d8bbwe!SecHealthUI"),
            ("Windows.SystemToast.SecurityAndMaintenance", "Microsoft.SecHealthUI_8wekyb3d8bbwe!SecHealthUI"),
            ("Microsoft.Windows.Explorer", "Microsoft.Windows.Explorer"),
            ("Windows.SystemToast.WindowsUpdate", "windows.immersivecontrolpanel_cw5n1h2txyewy!microsoft.windows.immersivecontrolpanel"),
            ("Windows.SystemToast", "windows.immersivecontrolpanel_cw5n1h2txyewy!microsoft.windows.immersivecontrolpanel"),
        };

        public static BitmapSource? For(string? appId, int px = 64)
        {
            if (string.IsNullOrWhiteSpace(appId)) return null;
            if (Cache.TryGetValue(appId, out var hit)) return hit;
            var icon = Load("shell:AppsFolder\\" + appId, px);
            if (icon == null)
                foreach (var (prefix, aumid) in Aliases)
                    if (appId.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) && (icon = Load("shell:AppsFolder\\" + aumid, px)) != null) break;
            Cache[appId] = icon;
            return icon;
        }

        static BitmapSource? Load(string parsingName, int px)
        {
            IntPtr hbm = IntPtr.Zero;
            try
            {
                var iid = typeof(IShellItemImageFactory).GUID;
                SHCreateItemFromParsingName(parsingName, IntPtr.Zero, ref iid, out var factory);
                // SIIGBF_BIGGERSIZEOK | SIIGBF_ICONONLY: the app's icon, never a thumbnail
                if (factory.GetImage(new SIZE { cx = px, cy = px }, 0x1 | 0x4, out hbm) != 0 || hbm == IntPtr.Zero) return null;
                return FromHBitmap(hbm);
            }
            catch { return null; }
            finally { if (hbm != IntPtr.Zero) DeleteObject(hbm); }
        }

        /// <summary>
        /// Real logo for an Island-API icon name (claude, terminal, vscode); null = use the drawn symbol.
        /// Claude's logo comes from the installed Claude Code extension, so nothing is bundled and it follows updates.
        /// </summary>
        public static BitmapSource? ForIcon(string? icon) => (icon ?? "").ToLowerInvariant() switch
        {
            "claude" => ClaudeLogo(),
            "terminal" => For("Microsoft.WindowsTerminal_8wekyb3d8bbwe!App") ?? For("{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe"),
            "vscode" or "code" => For("Microsoft.VisualStudioCode"),
            _ => null,
        };

        static BitmapSource? _claude;
        static bool _claudeLooked;

        static BitmapSource? ClaudeLogo()
        {
            if (_claudeLooked) return _claude;
            _claudeLooked = true;
            try
            {
                string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
                foreach (var root in new[] { ".vscode", ".vscode-insiders", ".cursor" })
                {
                    var dir = System.IO.Path.Combine(home, root, "extensions");
                    if (!System.IO.Directory.Exists(dir)) continue;
                    // newest installed version first
                    var ext = System.Linq.Enumerable.FirstOrDefault(System.Linq.Enumerable.OrderByDescending(
                        System.IO.Directory.GetDirectories(dir, "anthropic.claude-code-*"), d => System.IO.Directory.GetLastWriteTimeUtc(d)));
                    if (ext == null) continue;
                    string png = System.IO.Path.Combine(ext, "resources", "claude-logo.png");
                    try
                    {
                        using var doc = System.Text.Json.JsonDocument.Parse(System.IO.File.ReadAllText(System.IO.Path.Combine(ext, "package.json")));
                        if (doc.RootElement.TryGetProperty("icon", out var ic) && ic.GetString() is string rel) png = System.IO.Path.Combine(ext, rel);
                    }
                    catch { }
                    if (!System.IO.File.Exists(png)) continue;
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.DecodePixelWidth = 96;
                    bmp.UriSource = new Uri(png);
                    bmp.EndInit();
                    bmp.Freeze();
                    return _claude = bmp;
                }
            }
            catch { }
            return null;
        }

        /// <summary>Copies a 32-bpp DIB section keeping its alpha (CreateBitmapSourceFromHBitmap drops it).</summary>
        static BitmapSource? FromHBitmap(IntPtr hbm)
        {
            var bm = new BITMAP();
            if (GetObject(hbm, Marshal.SizeOf<BITMAP>(), ref bm) == 0 || bm.bmBits == IntPtr.Zero || bm.bmBitsPixel != 32) return null;
            int w = bm.bmWidth, h = Math.Abs(bm.bmHeight), stride = bm.bmWidthBytes;
            var raw = new byte[stride * h];
            Marshal.Copy(bm.bmBits, raw, 0, raw.Length);
            byte[] px = raw;
            if (bm.bmHeight > 0)   // bottom-up DIB: flip
            {
                px = new byte[raw.Length];
                for (int y = 0; y < h; y++) Buffer.BlockCopy(raw, (h - 1 - y) * stride, px, y * stride, stride);
            }
            var src = BitmapSource.Create(w, h, 96, 96, PixelFormats.Pbgra32, null, px, stride);
            src.Freeze();
            return src;
        }
    }
}
