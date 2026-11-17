using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace DynamicIsland
{
    /// <summary>
    /// Smooth, proportional wheel/touchpad scrolling for a ScrollViewer.
    /// WPF's default jumps a fixed ~48 px per wheel event, which feels wildly fast on precision
    /// touchpads (they send many small events). This scrolls by the real delta and eases toward it.
    /// </summary>
    public static class SmoothScroll
    {
        public static readonly DependencyProperty EnabledProperty = DependencyProperty.RegisterAttached(
            "Enabled", typeof(bool), typeof(SmoothScroll), new PropertyMetadata(false, OnEnabledChanged));

        public static void SetEnabled(DependencyObject d, bool v) => d.SetValue(EnabledProperty, v);
        public static bool GetEnabled(DependencyObject d) => (bool)d.GetValue(EnabledProperty);

        const double Speed = 0.45;   // pixels per wheel-delta unit (one mouse notch = 120 → ~54 px)
        const double Ease = 0.22;    // fraction of the remaining distance covered per frame

        static void OnEnabledChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
        {
            if (d is not ScrollViewer sv || e.NewValue is not true) return;
            double target = 0;
            bool running = false;

            void frame(object? s, EventArgs a)
            {
                double cur = sv.VerticalOffset;
                double next = cur + (target - cur) * Ease;
                if (Math.Abs(target - next) < 0.5) { next = target; CompositionTarget.Rendering -= frame; running = false; }
                sv.ScrollToVerticalOffset(next);
            }

            sv.PreviewMouseWheel += (_, ev) =>
            {
                if (sv.ScrollableHeight <= 0) return;   // nothing to scroll: let the parent handle the wheel
                ev.Handled = true;
                double from = running ? target : sv.VerticalOffset;
                target = Math.Clamp(from - ev.Delta * Speed, 0, sv.ScrollableHeight);
                if (!running) { running = true; CompositionTarget.Rendering += frame; }
            };
            // Keep the target in sync if something else scrolls (scrollbar drag, ScrollToTop).
            sv.ScrollChanged += (_, _) => { if (!running) target = sv.VerticalOffset; };
        }
    }
}
