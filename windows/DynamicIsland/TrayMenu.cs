using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using DynamicIsland.Pets;

namespace DynamicIsland
{
    /// <summary>Custom dark tray menu (replaces the stock Windows context menu).</summary>
    public sealed class TrayMenu : Window
    {
        readonly StackPanel _items = new();

        public TrayMenu(Settings s, Hydration water, Action open, Action drink, Action settings, Action quit)
        {
            // Native Win11 frame (rounded corners + shadow by Windows) so the window is exactly the menu's size.
            WindowStyle = WindowStyle.SingleBorderWindow;
            Background = new SolidColorBrush(Color.FromRgb(0x16, 0x17, 0x1A));
            System.Windows.Shell.WindowChrome.SetWindowChrome(this, new System.Windows.Shell.WindowChrome
            {
                CaptionHeight = 0, GlassFrameThickness = new Thickness(0), ResizeBorderThickness = new Thickness(0),
                CornerRadius = new CornerRadius(0), UseAeroCaptionButtons = false,
            });
            SourceInitialized += (_, _) => Native.ApplyWin11Frame(new System.Windows.Interop.WindowInteropHelper(this).Handle, smallCorners: true);
            Topmost = true;
            ShowInTaskbar = false;
            ResizeMode = ResizeMode.NoResize;
            SizeToContent = SizeToContent.WidthAndHeight;
            FontFamily = (FontFamily)Application.Current.FindResource("UiFont");
            Foreground = Brushes.White;
            UseLayoutRounding = true;

            // Header: pet + today's water
            var pet = new PetView { Width = 40, Height = 40 };
            pet.Loaded += (_, _) => { pet.SetPet(s.Water.Pet, water.Stage, 0); pet.SetMood(water.Mood); pet.SetWater(water.Fraction, false); };
            var head = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 6, 10, 10) };
            head.Children.Add(pet);
            var ht = new StackPanel { Margin = new Thickness(10, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            ht.Children.Add(new TextBlock { Text = water.AmountText, FontSize = 14, FontWeight = FontWeights.SemiBold });
            ht.Children.Add(new TextBlock { Text = water.StatusLine(), FontSize = 11, Foreground = (Brush)Application.Current.FindResource("Dim") });
            head.Children.Add(ht);
            _items.Children.Add(head);

            Item("", "Open island", "Ctrl+Alt+I", open);
            Item("", $"Log a drink ({water.CupMl} ml)", "Ctrl+Alt+W", drink);
            Item("", "Settings", null, settings);
            Separator();
            Item("", "Quit", null, quit);

            Content = new Border { Padding = new Thickness(6), MinWidth = 250, Child = _items };

            Deactivated += (_, _) => Close();
            KeyDown += (_, e) => { if (e.Key == Key.Escape) Close(); };
        }

        void Item(string glyph, string text, string? hint, Action action)
        {
            var row = new Border { CornerRadius = new CornerRadius(10), Padding = new Thickness(10, 8, 12, 8), Background = Brushes.Transparent, Cursor = Cursors.Hand };
            var g = new Grid();
            var left = new StackPanel { Orientation = Orientation.Horizontal };
            left.Children.Add(new TextBlock { Text = glyph, FontFamily = (FontFamily)Application.Current.FindResource("IconFont"), FontSize = 14, Width = 26, VerticalAlignment = VerticalAlignment.Center });
            left.Children.Add(new TextBlock { Text = text, FontSize = 13, VerticalAlignment = VerticalAlignment.Center });
            g.Children.Add(left);
            if (hint != null) g.Children.Add(new TextBlock { Text = hint, FontSize = 11, Foreground = (Brush)Application.Current.FindResource("Faint"), HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(24, 0, 0, 0) });
            row.Child = g;
            row.MouseEnter += (_, _) => row.Background = new SolidColorBrush(Color.FromArgb(0x1F, 255, 255, 255));
            row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (_, _) => { Close(); action(); };
            _items.Children.Add(row);
        }

        void Separator() => _items.Children.Add(new Border { Height = 1, Margin = new Thickness(8, 4, 8, 4), Background = new SolidColorBrush(Color.FromArgb(0x22, 255, 255, 255)) });

        /// <summary>Open just above the mouse (the tray is at the bottom of the screen).</summary>
        public void ShowAtCursor()
        {
            var px = System.Windows.Forms.Cursor.Position;
            Show();
            var src = PresentationSource.FromVisual(this);
            double sx = src?.CompositionTarget?.TransformFromDevice.M11 ?? 1, sy = src?.CompositionTarget?.TransformFromDevice.M22 ?? 1;
            double x = px.X * sx, y = px.Y * sy;
            var wa = SystemParameters.WorkArea;
            Left = Math.Min(x - ActualWidth + 24, wa.Right - ActualWidth);
            Top = Math.Max(wa.Top, Math.Min(y - ActualHeight, wa.Bottom - ActualHeight));
            Activate();
        }
    }
}
