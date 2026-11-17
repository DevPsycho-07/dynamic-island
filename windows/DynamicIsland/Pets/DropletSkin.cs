using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace DynamicIsland.Pets
{
    /// <summary>
    /// Evolving water droplet. A glass body with real water inside (clipped to the body, wavy surface).
    /// Stages: Droplet (teardrop) → Pup (round, feet, stubby arms) → Sprite (tall, wave-crest hair, arms)
    /// → Spirit (floating, glow aura, own rain cloud). Cosmetics: beanie, scarf, shades.
    /// </summary>
    public sealed class DropletSkin : PetSkin
    {
        public override PetKind Kind => PetKind.Droplet;
        public override string Name => "Droplet";
        public override string[] StageNames => new[] { "Droplet", "Pup", "Sprite", "Spirit" };
        public override string[] CosmeticNames => new[] { "None", "Beanie", "Scarf", "Shades" };

        // ---------- palette / helpers ----------
        static Color Cl(uint argb) => Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);
        static SolidColorBrush Sb(uint argb) { var s = new SolidColorBrush(Cl(argb)); s.Freeze(); return s; }
        static LinearGradientBrush Lin(uint top, uint bot, double x2 = 0, double y2 = 1)
        {
            var g = new LinearGradientBrush(Cl(top), Cl(bot), new Point(0, 0), new Point(x2, y2));
            g.Freeze(); return g;
        }

        static readonly Brush Ink = Sb(0xFF0B2A50);
        static readonly Brush White = Sb(0xFFFFFFFF);
        static readonly Brush Pink = Sb(0xFFFF8FB1);

        // tone 0 = normal, 1 = thirsty (paler), 2 = very thirsty (desaturated)
        static readonly Brush[] GlassFill = { Lin(0xFFE2F8FF, 0xFFA2DAF6), Lin(0xFFE2F8FF, 0xFFA8DDF6), Lin(0xFFEAF0F3, 0xFFB8C8D1) };
        static readonly Brush[] WaterF = { Lin(0xFF8FE3FF, 0xFF1E8FE6), Lin(0xFFC2EEFF, 0xFF6DBBEE), Lin(0xFFC9D6DC, 0xFF7D95A3) };
        static readonly Brush[] WaterB = { Lin(0x99C4F4FF, 0x995CC0F5), Lin(0x99DFF7FF, 0x999AD3F2), Lin(0x99E1E8EB, 0x99A5B6BF) };
        static readonly Brush[] Limb = { Lin(0xFF86D8FB, 0xFF35A0EA), Lin(0xFFB0E4FB, 0xFF70BAEA), Lin(0xFFBBC9D0, 0xFF7E95A2) };
        static readonly Brush[] Crest = { Lin(0xFFC4F4FF, 0xFF3AA8F0), Lin(0xFFDDF7FF, 0xFF7CC4F0), Lin(0xFFDDE6EA, 0xFF93A8B4) };
        static readonly Brush Rim = Lin(0xF0FFFFFF, 0xC8BFEFFF);
        static readonly Brush RimDull = Lin(0xC8FFFFFF, 0x9AD2E4EE);

        // ---------- per-stage layout ----------
        static readonly string[] BodyData =
        {
            "M50,12 C56,28 80,46 80,68 C80,82 67,92 50,92 C33,92 20,82 20,68 C20,46 44,28 50,12 Z",
            "M50,24 C55,36 79,50 79,68 C79,81 66,89 50,89 C34,89 21,81 21,68 C21,50 45,36 50,24 Z",
            "M50,18 C55,32 76,46 76,66 C76,80 64,89 50,89 C36,89 24,80 24,66 C24,46 45,32 50,18 Z",
            "M50,24 C56,37 78,49 78,64 C78,78 66,87 50,87 C34,87 22,78 22,64 C22,49 44,37 50,24 Z",
        };
        static readonly Geometry[] BodyGeo = { G(BodyData[0]), G(BodyData[1]), G(BodyData[2]), G(BodyData[3]) };
        static readonly double[] Top = { 12, 24, 18, 24 };
        static readonly double[] Bot = { 92, 89, 89, 87 };
        static readonly double[] EyeY = { 65, 62, 57, 55 };
        static readonly double[] EyeDx = { 11, 12, 11, 11 };
        static readonly double[] EyeRx = { 4.8, 5.4, 5.0, 5.0 };
        static readonly double[] EyeRy = { 5.8, 6.4, 6.2, 6.0 };
        static readonly double[] MouthY = { 75, 73, 68, 65 };
        static readonly double[] HatY = { 45, 46, 40, 42 };
        static readonly double[] PuddleRx = { 30, 32, 34, 0 };

        static readonly Geometry[] MouthGeo =
        {
            G("M-4.5,0 Q0,4.5 4.5,0"),                           // idle
            G("M-7,-1 Q0,1 7,-1 Q7,9 0,9 Q-7,9 -7,-1 Z"),        // happy
            G("M-4.5,1 Q0,0.4 4.5,1"),                           // thirsty
            G("M-5.5,3.5 Q0,-2.5 5.5,3.5"),                      // very thirsty
            G("M-3,0 Q0,3 3,0"),                                 // sleeping
        };
        static readonly Geometry StarGeo = G("M0,-1 L0.24,-0.24 1,0 0.24,0.24 0,1 -0.24,0.24 -1,0 -0.24,-0.24 Z");
        static readonly Geometry DropGeo = G("M0,-5 C1.6,-2.6 4,0 4,3 A4,4 0 0 1 -4,3 C-4,0 -1.6,-2.6 0,-5 Z");
        static readonly Geometry CrestGeo = G("M44,29 C42,17 48,6 60,5 C70,4 77,11 74,18 C72,23 64,24 62,19 C61,15.5 65,13.5 67.5,15 C66,10.5 59,10.5 57,15.5 C55.5,20 58,25 57,29 Z");
        const double Period = 40;

        static Geometry Wave(double amp, double x0, double x1, double depth, bool up)
        {
            var sg = new StreamGeometry();
            using (var c = sg.Open())
            {
                c.BeginFigure(new Point(x0, 0), true, true);
                double half = Period / 2, x = x0; bool dir = up;
                while (x < x1 - 0.01)
                {
                    c.QuadraticBezierTo(new Point(x + half / 2, dir ? -2 * amp : 2 * amp), new Point(x + half, 0), true, true);
                    x += half; dir = !dir;
                }
                c.LineTo(new Point(x, depth), true, false);
                c.LineTo(new Point(x0, depth), true, false);
            }
            sg.Freeze(); return sg;
        }
        static readonly Geometry WaveFront = Wave(2.1, -80, 180, 95, true);
        static readonly Geometry WaveBack = Wave(2.7, -60, 160, 95, false);
        static readonly Geometry WaveFrontC = Wave(3.4, -80, 180, 95, true);
        static readonly Geometry WaveBackC = Wave(3.9, -60, 160, 95, false);

        static (double l, double r) Span(Geometry g, double y)
        {
            double l = 50, r = 50; bool f = false;
            for (double x = 0; x <= 100; x += 0.5)
                if (g.FillContains(new Point(x, y))) { if (!f) { l = x; f = true; } r = x; }
            return (l, r);
        }

        sealed class Eye
        {
            public Canvas C = null!, Open = null!;
            public Path Closed = null!, Star = null!;
            public ScaleTransform S = null!;
            public TranslateTransform T = null!;
        }

        // ---------- live state ----------
        Canvas? _root;
        ScaleTransform _fit = new(1, 1, 50, 92);
        double _fitBase = 1;
        Path? _glass, _wBack, _wFront, _rim, _crest;
        readonly List<Shape> _limbs = new();
        readonly List<(RotateTransform rt, bool left)> _arms = new();
        TranslateTransform _lvl = new(), _wxF = new(), _wxB = new();
        Canvas? _lvlHost;
        readonly List<Eye> _eyes = new();
        readonly List<UIElement> _blush = new();
        Canvas? _mouthHost, _cracks, _brows;
        Path? _mouth;
        Ellipse? _tongue;
        Path? _sweat;
        Ellipse? _puddle;
        RotateTransform? _auraRot;
        TranslateTransform? _cloudT;
        readonly List<(TranslateTransform t, Path p, double delay)> _drops = new();
        double _eyeBaseX = 1, _eyeBaseY = 1;

        // ---------- scene ----------
        protected override void BuildScene(Canvas scene)
        {
            int s = Stage; double k = Compact ? 1.3 : 1;
            _puddle = null;
            if (s < 3)
            {
                double rx = PuddleRx[s], ry = 4.4 * k;
                var rg = new RadialGradientBrush();
                rg.GradientStops.Add(new GradientStop(Cl(0x8C8CDCFF), 0));
                rg.GradientStops.Add(new GradientStop(Cl(0x554FB8F5), 0.65));
                rg.GradientStops.Add(new GradientStop(Cl(0x002A8FE0), 1));
                rg.Freeze();
                _puddle = At(new Ellipse { Width = rx * 2, Height = ry * 2, Fill = rg, RenderTransformOrigin = new Point(0.5, 0.5), RenderTransform = new ScaleTransform(1, 1) }, 50 - rx, 94 - ry);
                scene.Children.Add(_puddle);
            }
            else
            {
                var rg = new RadialGradientBrush();
                rg.GradientStops.Add(new GradientStop(Cl(0x707FDBFF), 0));
                rg.GradientStops.Add(new GradientStop(Cl(0x307FDBFF), 0.6));
                rg.GradientStops.Add(new GradientStop(Cl(0x007FDBFF), 1));
                rg.Freeze();
                scene.Children.Add(At(new Ellipse { Width = 54, Height = 9, Fill = rg }, 23, 90));
            }
        }

        // ---------- body ----------
        protected override void BuildBody(Canvas body)
        {
            int s = Stage; bool c = Compact; double K = c ? 1.7 : 1;
            _limbs.Clear(); _arms.Clear(); _eyes.Clear(); _blush.Clear(); _drops.Clear();
            _auraRot = null; _cloudT = null;
            _lvl = new TranslateTransform(); _wxF = new TranslateTransform(); _wxB = new TranslateTransform();
            var geo = BodyGeo[s];

            double topMost = Top[s];
            if (Cosmetic == 1 && s != 2) topMost -= 6.5;
            if (s == 2) topMost = 4;
            if (s == 3 && !c) topMost = 3;
            _fitBase = c ? Math.Clamp(88.0 / (92 - topMost), 1, 1.18) : 1;
            _fit = new ScaleTransform(_fitBase, _fitBase, 50, 92);
            _root = new Canvas { Width = 100, Height = 100, RenderTransform = _fit };
            body.Children.Add(_root);
            var r = _root;

            // aura (Spirit)
            if (s == 3 && !c)
            {
                r.Children.Add(At(new Ellipse { Width = 84, Height = 84, Fill = Sb(0x2E7FDBFF) }, 8, 14));
                r.Children.Add(At(new Ellipse { Width = 68, Height = 68, Fill = Sb(0x387FDBFF) }, 16, 22));
                r.Children.Add(At(new Ellipse { Width = 52, Height = 52, Fill = Sb(0x40B6EBFF) }, 24, 30));
                _auraRot = new RotateTransform(0, 50, 56);
                var spin = new Canvas { Width = 100, Height = 100, RenderTransform = _auraRot };
                spin.Children.Add(At(new Ellipse { Width = 88, Height = 88, Stroke = Sb(0x9CBDEFFF), StrokeThickness = 1.5, StrokeDashArray = new DoubleCollection { 1.2, 3.2 }, StrokeDashCap = PenLineCap.Round }, 6, 12));
                foreach (var (x, y, sz) in new[] { (50.0, 12.0, 3.4), (94.0, 56.0, 2.6), (50.0, 100.0, 3.0), (6.0, 56.0, 3.0), (81.0, 25.0, 2.2) })
                    spin.Children.Add(At(new Path { Data = StarGeo, Fill = Sb(0xE6FFFFFF), RenderTransform = new ScaleTransform(sz, sz) }, x, y));
                r.Children.Add(spin);
            }

            if (s == 3 && c) r.Children.Add(At(new Ellipse { Width = 76, Height = 76, Fill = Sb(0x4A7FDBFF) }, 12, 14));

            // feet
            if (!c && (s == 1 || s == 2))
            {
                double fw = s == 1 ? 15 : 16, fh = 8.2, fy = s == 1 ? 88.2 : 88.6, fx = s == 1 ? 13 : 12;
                foreach (double cx in new[] { 50 - fx - 1, 50 + fx + 1 })
                    AddLimb(r, new Ellipse { Width = fw, Height = fh, StrokeThickness = 1.1 }, cx - fw / 2, fy - fh / 2 + 1.2);
            }

            // arms
            if (!c && s >= 1)
            {
                double aw = s == 1 ? 10 : 8.8, ah = s == 1 ? 12 : (s == 2 ? 17 : 15.5);
                double sy = s == 1 ? 64 : (s == 2 ? 62 : 58);
                double lx = s == 1 ? 22.5 : (s == 2 ? 25.5 : 25);
                foreach (bool left in new[] { true, false })
                {
                    double sx = left ? lx : 100 - lx;
                    var rt = new RotateTransform(0, aw / 2, 2.5);
                    var arm = new Ellipse { Width = aw, Height = ah, StrokeThickness = 1.1, RenderTransform = rt };
                    AddLimb(r, arm, sx - aw / 2, sy - 2.5);
                    _arms.Add((rt, left));
                }
            }

            // glass
            _glass = new Path { Data = geo };
            r.Children.Add(_glass);

            // water (clipped to the body)
            var water = new Canvas { Width = 100, Height = 100, Clip = geo };
            _lvlHost = new Canvas { Width = 100, Height = 100, RenderTransform = _lvl };
            _wBack = new Path { Data = c ? WaveBackC : WaveBack, RenderTransform = _wxB };
            _wFront = new Path { Data = c ? WaveFrontC : WaveFront, RenderTransform = _wxF };
            if (!c) { _wFront.Stroke = Sb(0xC8FFFFFF); _wFront.StrokeThickness = 1.5; _wFront.StrokeLineJoin = PenLineJoin.Round; }
            _lvlHost.Children.Add(_wBack);
            _lvlHost.Children.Add(_wFront);
            if (!c)
            {
                double bx = s == 2 ? 62 : 64;
                _lvlHost.Children.Add(At(new Ellipse { Width = 5.5, Height = 5.5, Fill = Sb(0x5CFFFFFF) }, 33, 17));
                _lvlHost.Children.Add(At(new Ellipse { Width = 3.4, Height = 3.4, Fill = Sb(0x66FFFFFF) }, bx, 29));
                _lvlHost.Children.Add(At(new Ellipse { Width = 2.4, Height = 2.4, Fill = Sb(0x55FFFFFF) }, 44, 38));
            }
            water.Children.Add(_lvlHost);
            r.Children.Add(water);

            // cracks (very thirsty)
            _cracks = null;
            if (!c)
            {
                _cracks = new Canvas { Width = 100, Height = 100, Clip = geo, Visibility = Visibility.Collapsed };
                double y0 = Top[s] + (Bot[s] - Top[s]) * 0.42;
                var (l0, r0) = Span(geo, y0);
                var (l1, r1) = Span(geo, y0 + 14);
                var cb = Sb(0xFF3F5A6B);
                _cracks.Children.Add(new Path { Data = G($"M{l0 - 1},{y0} L{l0 + 5},{y0 + 3.5} L{l0 + 3},{y0 + 7.5} L{l0 + 9},{y0 + 11.5}"), Stroke = cb, StrokeThickness = 1.6, StrokeLineJoin = PenLineJoin.Round });
                _cracks.Children.Add(new Path { Data = G($"M{r1 + 1},{y0 + 14} L{r1 - 5},{y0 + 17} L{r1 - 3},{y0 + 21} L{r1 - 8.5},{y0 + 25}"), Stroke = cb, StrokeThickness = 1.6, StrokeLineJoin = PenLineJoin.Round });
                _cracks.Children.Add(new Path { Data = G($"M{r0 - 1},{y0 - 8} L{r0 - 5},{y0 - 5}"), Stroke = cb, StrokeThickness = 1.5, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
                r.Children.Add(_cracks);
            }

            // glass outline + speculars
            _rim = new Path { Data = geo, StrokeThickness = c ? 3.2 : 2.1, StrokeLineJoin = PenLineJoin.Round };
            r.Children.Add(_rim);
            {
                double H = Bot[s] - Top[s];
                double ya = Top[s] + H * 0.44, yb = Top[s] + H * 0.70, ym = (ya + yb) / 2;
                var (la, _) = Span(geo, ya); var (lb, _) = Span(geo, yb); var (lm, _) = Span(geo, ym);
                r.Children.Add(new Path
                {
                    Data = G($"M{la + 6.5},{ya} Q{lm + 3.2},{ym} {lb + 5.2},{yb}"),
                    Stroke = Sb(c ? 0xC8FFFFFF : 0xB4FFFFFF),
                    StrokeThickness = c ? 4.2 : 3.2,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round
                });
                var (ld, _) = Span(geo, ya - 7);
                if (!c) r.Children.Add(At(new Ellipse { Width = 3.6, Height = 3.6, Fill = Sb(0xB4FFFFFF) }, ld + 8.5, ya - 10));
            }

            BuildFace(r);
            BuildCosmetic(r);

            // crest (Sprite)
            _crest = null;
            if (s == 2)
            {
                _crest = new Path { Data = CrestGeo, Stroke = Sb(0xE6FFFFFF), StrokeThickness = c ? 2.4 : 1.6, StrokeLineJoin = PenLineJoin.Round };
                r.Children.Add(_crest);
            }

            // rain cloud (Spirit)
            if (s == 3 && !c)
            {
                _cloudT = new TranslateTransform();
                var cloud = new Canvas { Width = 100, Height = 100, RenderTransform = _cloudT };
                var gg = new GeometryGroup { FillRule = FillRule.Nonzero };
                gg.Children.Add(new EllipseGeometry(new Point(41, 12), 7, 6));
                gg.Children.Add(new EllipseGeometry(new Point(50, 8.6), 9, 7.4));
                gg.Children.Add(new EllipseGeometry(new Point(60, 11), 7.6, 6.4));
                gg.Children.Add(new RectangleGeometry(new Rect(35, 11, 32, 7), 3.5, 3.5));
                gg.Freeze();
                foreach (var (x, y, dl) in new[] { (42.0, 23.0, 0.0), (60.0, 23.0, 450.0), (67.0, 21.0, 900.0) })
                {
                    var tt = new TranslateTransform();
                    var dp = new Path { Data = DropGeo, Fill = Sb(0xFF8FE0FF), Opacity = 0.9, RenderTransform = new TransformGroup { Children = { new ScaleTransform(0.55, 0.55), tt } } };
                    cloud.Children.Add(At(dp, x, y));
                    _drops.Add((tt, dp, dl));
                }
                cloud.Children.Add(new Path { Data = gg, Fill = Lin(0xFFFFFFFF, 0xFFB5D0EA) });
                cloud.Children.Add(At(new Ellipse { Width = 6, Height = 3.4, Fill = Sb(0xB0FFFFFF) }, 45, 4.4));
                r.Children.Add(cloud);
            }

            if (Idling) StartAnims();
        }

        void AddLimb(Canvas r, Shape e, double x, double y)
        {
            e.Stroke = Sb(0xA0FFFFFF);
            _limbs.Add(e);
            r.Children.Add(At(e, x, y));
        }

        void BuildFace(Canvas r)
        {
            int s = Stage; bool c = Compact;
            double K = c ? 1.7 : 1, ey = EyeY[s], dx = EyeDx[s];
            double rx = EyeRx[s] * (c ? 1.35 : 1.12), ry = EyeRy[s] * (c ? 1.35 : 1.12);
            if (c) { ey += 1; dx += 1.5; }

            // blush
            if (!c)
                foreach (double sgn in new[] { -1.0, 1.0 })
                {
                    var b = At(new Ellipse { Width = 10, Height = 5.4, Fill = Sb(0xB4FF8FB1), Visibility = Visibility.Collapsed }, 50 + sgn * (dx + 6.5) - 5, ey + ry * 0.9);
                    _blush.Add(b); r.Children.Add(b);
                }

            // eyes
            foreach (double sgn in new[] { -1.0, 1.0 })
            {
                var e = new Eye();
                e.C = new Canvas { Width = 0, Height = 0 };
                e.S = new ScaleTransform(1, 1); e.T = new TranslateTransform();
                e.C.RenderTransform = new TransformGroup { Children = { e.S, e.T } };
                Canvas.SetLeft(e.C, 50 + sgn * dx); Canvas.SetTop(e.C, ey);
                e.Open = new Canvas { Width = 0, Height = 0 };
                e.Open.Children.Add(At(new Ellipse { Width = rx * 2, Height = ry * 2, Fill = Ink }, -rx, -ry));
                if (!c) e.Open.Children.Add(At(new Ellipse { Width = rx * 1.05, Height = ry * 0.8, Fill = Sb(0xA03A86D6) }, -rx * 0.52, ry * 0.12));
                e.Open.Children.Add(At(new Ellipse { Width = rx * (c ? 0.9 : 0.85), Height = ry * (c ? 0.75 : 0.7), Fill = White }, -rx * 0.58, -ry * 0.66));
                if (!c) e.Open.Children.Add(At(new Ellipse { Width = rx * 0.38, Height = ry * 0.32, Fill = White }, rx * 0.2, ry * 0.3));
                e.Closed = new Path
                {
                    Data = G($"M{-rx * 0.95},{-ry * 0.1} Q0,{ry * 0.85} {rx * 0.95},{-ry * 0.1}"),
                    Stroke = Ink, StrokeThickness = 2.3 * K, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
                    Visibility = Visibility.Collapsed
                };
                e.Star = new Path { Data = StarGeo, Fill = White, Visibility = Visibility.Collapsed, RenderTransform = new ScaleTransform(3.3, 3.3) };
                Canvas.SetLeft(e.Star, rx * 0.5); Canvas.SetTop(e.Star, -ry * 0.55);
                e.C.Children.Add(e.Open); e.C.Children.Add(e.Closed);
                if (!c) e.C.Children.Add(e.Star);
                _eyes.Add(e); r.Children.Add(e.C);
            }

            // brows (very thirsty)
            _brows = null;
            if (!c)
            {
                _brows = new Canvas { Width = 100, Height = 100, Visibility = Visibility.Collapsed };
                double by = ey - ry - 3.4;
                _brows.Children.Add(new Path { Data = G($"M{50 - dx - 4.6},{by + 1.6} L{50 - dx + 4.4},{by - 2.2}"), Stroke = Ink, StrokeThickness = 1.9, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
                _brows.Children.Add(new Path { Data = G($"M{50 + dx + 4.6},{by + 1.6} L{50 + dx - 4.4},{by - 2.2}"), Stroke = Ink, StrokeThickness = 1.9, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
                r.Children.Add(_brows);
            }

            // mouth
            _mouthHost = new Canvas { Width = 0, Height = 0 };
            Canvas.SetLeft(_mouthHost, 50); Canvas.SetTop(_mouthHost, MouthY[s] + (c ? 1 : 0));
            if (c) _mouthHost.RenderTransform = new ScaleTransform(1.15, 1.15);
            _mouth = new Path { Stroke = Ink, StrokeThickness = 2.2 * K, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round };
            _tongue = At(new Ellipse { Width = 6.4, Height = 4.2, Fill = Sb(0xFFFF7D9A), Visibility = Visibility.Collapsed }, -3.2, 4.6);
            _mouthHost.Children.Add(_mouth);
            _mouthHost.Children.Add(_tongue);
            r.Children.Add(_mouthHost);

            // sweat drop
            _sweat = new Path
            {
                Data = DropGeo, Fill = Sb(0xFFD4F4FF), Stroke = Sb(0xFF4FB0F0), StrokeThickness = c ? 1.6 : 1,
                Visibility = Visibility.Collapsed, RenderTransform = new ScaleTransform(c ? 1.5 : 1.05, c ? 1.5 : 1.05)
            };
            At(_sweat, 50 + dx + rx + 4.5 + (c ? 1 : 0), ey - ry - 3 + (c ? 2 : 0));
            r.Children.Add(_sweat);
        }

        void BuildCosmetic(Canvas r)
        {
            int s = Stage; bool c = Compact; var geo = BodyGeo[s];
            if (Cosmetic == 1)
            {
                double hy = HatY[s];
                var clip = new Canvas { Width = 100, Height = 100, Clip = geo };
                clip.Children.Add(At(new Rectangle { Width = 100, Height = hy - Top[s] + 10, Fill = Lin(0xFFFF8A78, 0xFFE0454C) }, 0, Top[s] - 10));
                if (!c) clip.Children.Add(new Path { Data = G($"M{44},{hy - 8} Q{46},{hy - 18} {50},{Top[s] + 8}"), Stroke = Sb(0x66FFFFFF), StrokeThickness = 2.4, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
                r.Children.Add(clip);
                var (l, rr) = Span(geo, hy);
                r.Children.Add(At(new Rectangle { Width = rr - l + 6, Height = c ? 10 : 9, RadiusX = 4.5, RadiusY = 4.5, Fill = Lin(0xFFFFF1D6, 0xFFF3CB8E), Stroke = Sb(0x66B06A2A), StrokeThickness = c ? 0 : 0.8 }, l - 3, hy - 4.5));
                if (!c)
                    for (double x = l + 3; x < rr - 2; x += 5.2)
                        r.Children.Add(At(new Rectangle { Width = 1.3, Height = 6, Fill = Sb(0x55B06A2A) }, x, hy - 3));
                if (s != 2)
                    r.Children.Add(At(new Ellipse { Width = 11, Height = 11, Fill = Lin(0xFFFFFFFF, 0xFFFFE3B0), Stroke = Sb(0x55B06A2A), StrokeThickness = c ? 0 : 0.8 }, 44.5, Top[s] - 7));
            }
            else if (Cosmetic == 2)
            {
                double sy = MouthY[s] + (c ? 6.5 : 6);
                double bh = c ? 10 : 8.6;
                var clip = new Canvas { Width = 100, Height = 100, Clip = geo };
                clip.Children.Add(At(new Rectangle { Width = 100, Height = bh, Fill = Lin(0xFFFFB547, 0xFFEE8420) }, 0, sy));
                if (!c)
                    for (double x = -2; x < 100; x += 9)
                        clip.Children.Add(At(new Rectangle { Width = 3.2, Height = bh, Fill = Sb(0x55FFFFFF), RenderTransform = new SkewTransform(-18, 0) }, x, sy));
                r.Children.Add(clip);
                var (_, rr) = Span(geo, sy + 3);
                var tail = At(new Rectangle { Width = c ? 10 : 8.5, Height = c ? 16 : 15, RadiusX = 3.2, RadiusY = 3.2, Fill = Lin(0xFFFFB547, 0xFFE87A1C), Stroke = Sb(0x66B8570A), StrokeThickness = c ? 0 : 0.8, RenderTransform = new RotateTransform(-14, 4, 0) }, rr - 8, sy + 3.2);
                r.Children.Add(tail);
                if (!c) r.Children.Add(At(new Rectangle { Width = 5, Height = 1.6, Fill = Sb(0x77FFFFFF), RenderTransform = new RotateTransform(-14, 0, 0) }, rr - 5.4, sy + 9.5));
            }
            else if (Cosmetic == 3)
            {
                double ey = EyeY[s] + (c ? 1 : 0), dx = EyeDx[s] + (c ? 1.5 : 0);
                double w = EyeRx[s] * (c ? 1.3 : 1) * 2 + 7.5, h = EyeRy[s] * (c ? 1.3 : 1) * 2 + 2.5;
                var lens = Lin(0xFF2A3A5E, 0xFF0C1427);
                r.Children.Add(At(new Rectangle { Width = 2 * dx - w + 3, Height = c ? 3.4 : 2.4, Fill = Sb(0xFF0C1427) }, 50 - dx + w / 2 - 1.5, ey - h / 2 + 2.2));
                foreach (double sgn in new[] { -1.0, 1.0 })
                {
                    double cx = 50 + sgn * dx;
                    r.Children.Add(At(new Rectangle { Width = w, Height = h, RadiusX = 4.2, RadiusY = 4.2, Fill = lens, Stroke = Sb(0xFF0A1020), StrokeThickness = c ? 1.4 : 1.1 }, cx - w / 2, ey - h / 2));
                    r.Children.Add(new Path
                    {
                        Data = G($"M{cx - w * 0.28},{ey - h * 0.12} L{cx + w * 0.1},{ey - h * 0.38}"),
                        Stroke = Sb(0xA0FFFFFF), StrokeThickness = c ? 2.6 : 1.9, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round
                    });
                }
            }
        }

        // ---------- mood ----------
        public override void SetMood(PetMood m)
        {
            Mood = m; SetSleepFx(m == PetMood.Sleeping);
            if (_root == null || _glass == null || _mouth == null) return;
            bool sleeping = m == PetMood.Sleeping, happy = m == PetMood.Happy;
            bool thirsty = m == PetMood.Thirsty, very = m == PetMood.VeryThirsty;
            int tone = very ? 2 : (thirsty ? 1 : 0);

            _glass.Fill = GlassFill[tone];
            _wFront!.Fill = WaterF[tone];
            _wBack!.Fill = WaterB[tone];
            _rim!.Stroke = very ? RimDull : Rim;
            if (_crest != null) _crest.Fill = Crest[tone];
            foreach (var l in _limbs) l.Fill = Limb[tone];

            _eyeBaseX = happy ? 1.1 : 1;
            _eyeBaseY = happy ? 1.1 : (thirsty ? 0.82 : (very ? 0.9 : 1));
            foreach (var e in _eyes)
            {
                e.S.BeginAnimation(ScaleTransform.ScaleYProperty, null);
                e.S.ScaleX = _eyeBaseX; e.S.ScaleY = _eyeBaseY;
                e.Open.Visibility = sleeping ? Visibility.Collapsed : Visibility.Visible;
                e.Closed.Visibility = sleeping ? Visibility.Visible : Visibility.Collapsed;
                e.Star.Visibility = happy ? Visibility.Visible : Visibility.Collapsed;
                if (sleeping) { e.T.X = 0; e.T.Y = 0; }
            }

            int mi = happy ? 1 : (thirsty ? 2 : (very ? 3 : (sleeping ? 4 : 0)));
            _mouth.Data = MouthGeo[mi];
            _mouth.Fill = happy ? Ink : null;
            _tongue!.Visibility = happy && !Compact ? Visibility.Visible : Visibility.Collapsed;
            foreach (var b in _blush)
            {
                b.Opacity = happy ? 1 : (sleeping ? 0.6 : (m == PetMood.Idle ? 0.4 : 0));
                b.Visibility = b.Opacity > 0 ? Visibility.Visible : Visibility.Collapsed;
            }
            _sweat!.Visibility = thirsty || very ? Visibility.Visible : Visibility.Collapsed;
            if (_cracks != null) _cracks.Visibility = very ? Visibility.Visible : Visibility.Collapsed;
            if (_brows != null) _brows.Visibility = very ? Visibility.Visible : Visibility.Collapsed;

            double a = happy ? 138 : (very ? 2 : (thirsty ? 8 : (sleeping ? 12 : 18)));
            foreach (var (rt, left) in _arms) rt.Angle = left ? a : -a;

            double ms = very ? 0.92 : 1;
            _fit.ScaleX = _fit.ScaleY = _fitBase * ms;
        }

        // ---------- water ----------
        public override void SetWater(double fraction, bool animate)
        {
            Water = Math.Clamp(fraction, 0, 1);
            if (_root == null) return;
            int s = Stage;
            double top = Bot[s] - (0.13 + 0.82 * Water) * (Bot[s] - Top[s]);
            if (_puddle?.RenderTransform is ScaleTransform pt) pt.ScaleX = pt.ScaleY = 0.78 + 0.3 * Water;
            _lvl.BeginAnimation(TranslateTransform.YProperty, null);
            if (animate)
            {
                var an = new DoubleAnimation(top, TimeSpan.FromMilliseconds(750)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } };
                Timeline.SetDesiredFrameRate(an, 30);
                _lvl.BeginAnimation(TranslateTransform.YProperty, an, HandoffBehavior.SnapshotAndReplace);
                _lvl.Y = top;
            }
            else _lvl.Y = top;
        }

        public override void LookAt(double dx, double dy)
        {
            if (Mood == PetMood.Sleeping) return;
            double x = Math.Clamp(dx, -1, 1) * 2.5, y = Math.Clamp(dy, -1, 1) * 2.5;
            foreach (var e in _eyes) { e.T.X = x; e.T.Y = y; }
        }

        public override void Blink()
        {
            if (Mood == PetMood.Sleeping) return;
            foreach (var e in _eyes)
            {
                var a = new DoubleAnimationUsingKeyFrames();
                a.KeyFrames.Add(new LinearDoubleKeyFrame(0.08, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(70))));
                a.KeyFrames.Add(new LinearDoubleKeyFrame(_eyeBaseY, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(150))));
                e.S.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            }
        }

        // ---------- idle ----------
        public override void StartIdle()
        {
            base.StartIdle();
            if (Stage == 3 && Mood != PetMood.Sleeping)
            {
                var bob = new DoubleAnimation(0, -5, TimeSpan.FromMilliseconds(1800)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() };
                Timeline.SetDesiredFrameRate(bob, 30);
                BodyMove.BeginAnimation(TranslateTransform.YProperty, bob);
            }
            StartAnims();
        }

        public override void StopIdle()
        {
            base.StopIdle();
            StopAnims();
        }

        void StartAnims()
        {
            var f = new DoubleAnimation(0, -Period, TimeSpan.FromMilliseconds(2600)) { RepeatBehavior = RepeatBehavior.Forever };
            Timeline.SetDesiredFrameRate(f, 30);
            _wxF.BeginAnimation(TranslateTransform.XProperty, f);
            var b = new DoubleAnimation(0, Period, TimeSpan.FromMilliseconds(3700)) { RepeatBehavior = RepeatBehavior.Forever };
            Timeline.SetDesiredFrameRate(b, 30);
            _wxB.BeginAnimation(TranslateTransform.XProperty, b);

            if (_auraRot != null)
            {
                var ra = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(24)) { RepeatBehavior = RepeatBehavior.Forever };
                Timeline.SetDesiredFrameRate(ra, 30);
                _auraRot.BeginAnimation(RotateTransform.AngleProperty, ra);
            }
            if (_cloudT != null)
            {
                var cd = new DoubleAnimation(-4, 4, TimeSpan.FromMilliseconds(3400)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() };
                Timeline.SetDesiredFrameRate(cd, 30);
                _cloudT.BeginAnimation(TranslateTransform.XProperty, cd);
            }
            foreach (var (t, p, delay) in _drops)
            {
                var fall = new DoubleAnimation(0, 9, TimeSpan.FromMilliseconds(1300)) { RepeatBehavior = RepeatBehavior.Forever, BeginTime = TimeSpan.FromMilliseconds(delay) };
                Timeline.SetDesiredFrameRate(fall, 30);
                t.BeginAnimation(TranslateTransform.YProperty, fall);
                var fade = new DoubleAnimationUsingKeyFrames { RepeatBehavior = RepeatBehavior.Forever, BeginTime = TimeSpan.FromMilliseconds(delay) };
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.95, KeyTime.FromPercent(0)));
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(0.9, KeyTime.FromPercent(0.55)));
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromPercent(1)));
                fade.Duration = TimeSpan.FromMilliseconds(1300);
                Timeline.SetDesiredFrameRate(fade, 30);
                p.BeginAnimation(UIElement.OpacityProperty, fade);
            }
        }

        void StopAnims()
        {
            _wxF.BeginAnimation(TranslateTransform.XProperty, null);
            _wxB.BeginAnimation(TranslateTransform.XProperty, null);
            _auraRot?.BeginAnimation(RotateTransform.AngleProperty, null);
            _cloudT?.BeginAnimation(TranslateTransform.XProperty, null);
            foreach (var (t, p, _) in _drops)
            {
                t.BeginAnimation(TranslateTransform.YProperty, null);
                p.BeginAnimation(UIElement.OpacityProperty, null);
            }
        }
    }
}
