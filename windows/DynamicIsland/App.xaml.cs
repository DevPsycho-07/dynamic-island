using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Threading;
using Microsoft.Win32;
using WinForms = System.Windows.Forms;

namespace DynamicIsland
{
    public partial class App : System.Windows.Application
    {
        Mutex? _mutex;
        WinForms.NotifyIcon? _tray;
        IslandWindow? _island;
        SettingsWindow? _settingsWin;
        TrayMenu? _menu;
        public Settings Settings { get; private set; } = new();

        const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        const string RunName = "DynamicIsland";

        protected override void OnStartup(System.Windows.StartupEventArgs e)
        {
            _mutex = new Mutex(true, "RSV.DynamicIsland.SingleInstance", out bool first);
            if (!first) { Shutdown(); return; }

            base.OnStartup(e);
            // Never vanish on a UI glitch: log it and keep the island alive.
            DispatcherUnhandledException += (_, ex) =>
            {
                try
                {
                    var dir = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DynamicIsland");
                    System.IO.Directory.CreateDirectory(dir);
                    System.IO.File.AppendAllText(System.IO.Path.Combine(dir, "error.log"), $"{DateTime.Now:u}  {ex.Exception}{Environment.NewLine}{Environment.NewLine}");
                }
                catch { }
                ex.Handled = true;
            };
            Settings = Settings.Load();
            SettingsWindow.UpgradeHooksIfInstalled();   // keep installed Claude Code hooks in step with this version

            _island = new IslandWindow(Settings);
            _island.SettingsRequested += OpenSettings;
            _island.Show();

            _tray = new WinForms.NotifyIcon
            {
                Icon = LoadAppIcon(),
                Text = "Dynamic Island",
                Visible = true,
            };
            _tray.MouseUp += (_, a) =>
            {
                if (a.Button == WinForms.MouseButtons.Left) _island?.ToggleExpanded();
                else if (a.Button == WinForms.MouseButtons.Right) ShowMenu();
            };
        }

        void ShowMenu()
        {
            if (_island == null) return;
            _menu?.Close();
            _menu = new TrayMenu(Settings, _island.Water,
                open: () => _island.ToggleExpanded(),
                drink: () => _island.LogWater(),
                settings: OpenSettings,
                quit: Quit);
            _menu.ShowAtCursor();
        }

        public void OpenSettings()
        {
            if (_island == null) return;
            if (_settingsWin is { IsLoaded: true }) { _settingsWin.Activate(); return; }
            _settingsWin = new SettingsWindow(Settings, _island);
            _settingsWin.Show();
            _settingsWin.Activate();
        }

        public static bool StartWithWindows
        {
            get
            {
                using var k = Registry.CurrentUser.OpenSubKey(RunKey);
                return k?.GetValue(RunName) is string;
            }
            set
            {
                using var k = Registry.CurrentUser.OpenSubKey(RunKey, true) ?? Registry.CurrentUser.CreateSubKey(RunKey);
                if (value) k.SetValue(RunName, $"\"{Environment.ProcessPath}\"");
                else k.DeleteValue(RunName, false);
            }
        }

        void Quit()
        {
            if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
            _island?.Shutdown();
            Shutdown();
        }

        protected override void OnExit(System.Windows.ExitEventArgs e)
        {
            if (_tray != null) { _tray.Visible = false; _tray.Dispose(); }
            Settings.Save();
            base.OnExit(e);
        }

        /// <summary>The app logo (Assets\app.ico, designed in Stitch) at the tray's size for the current DPI.</summary>
        static Icon LoadAppIcon()
        {
            try
            {
                var s = GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"))?.Stream;
                if (s != null) return new Icon(s, WinForms.SystemInformation.SmallIconSize);
            }
            catch { }
            return SystemIcons.Application;
        }
    }
}
