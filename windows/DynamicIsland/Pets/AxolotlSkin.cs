using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace DynamicIsland.Pets
{
    /// <summary>Pink axolotl in a glass tank. Stages: Baby / Teen / Adult / Royal.</summary>
    public sealed class AxolotlSkin : PetSkin
    {
        public override PetKind Kind => PetKind.Axolotl;
        public override string Name => "Axolotl";
        public override string[] StageNames => new[] { "Baby", "Teen", "Adult", "Royal" };
        public override string[] CosmeticNames => new[] { "None", "Bow", "Crown", "Flower crown" };

        // ---------- frozen art assets ----------
        static Color C(string hex) => (Color)ColorConverter.ConvertFromString(hex);
        static Brush Hx(string hex, double alpha = 1)
        {
            var c = C(hex);
            var b = new SolidColorBrush(Color.FromArgb((byte)Math.Round(alpha * 255), c.R, c.G, c.B));
            b.Freeze();
            return b;
        }
        static Brush Grad(string top, string bottom, double alpha = 1)
        {
            var a = C(top); var b = C(bottom); byte al = (byte)Math.Round(alpha * 255);
            var g = new LinearGradientBrush(Color.FromArgb(al, a.R, a.G, a.B), Color.FromArgb(al, b.R, b.G, b.B), 90);
            g.Freeze();
            return g;
        }
        static Brush MakeEye()
        {
            var b = new RadialGradientBrush(C("#5E3C6C"), C("#1B0E21"))
            { GradientOrigin = new Point(0.4, 0.3), Center = new Point(0.5, 0.5), RadiusX = 0.62, RadiusY = 0.62 };
            b.Freeze();
            return b;
        }

        static readonly Brush White = Hx("#FFFFFF");
        static readonly Brush WhiteSoft = Hx("#FFFFFF", 0.8);
        static readonly Brush WhiteFaint = Hx("#FFFFFF", 0.22);
        static readonly Brush WhiteHi = Hx("#FFFFFF", 0.38);
        static readonly Brush Ink = Hx("#2E1A36");
        static readonly Brush BrowInk = Hx("#6E4A5F");
        static readonly Brush EyeFill = MakeEye();
        static readonly Brush BlushFill = Hx("#FF4F8C");
        static readonly Brush MouthFill = Hx("#B2324F");
        static readonly Brush TongueFill = Hx("#FF8AA6");
        static readonly Brush DropFill = Hx("#8FDBFF");
        static readonly Brush GoldFill = Grad("#FFE98A", "#FFB52E");
        static readonly Brush GoldLine = Hx("#E58F1F");
        static readonly Brush GemRed = Hx("#FF4F78");
        static readonly Brush GemBlue = Hx("#4FC3F7");
        static readonly Brush BowFill = Grad("#C7B2FF", "#8F6CF2");
        static readonly Brush BowDark = Hx("#6D4BD1");
        static readonly Brush Leaf = Hx("#6FD08A");
        static readonly Brush Halo = Hx("#FF9AD0", 0.30);
        static readonly Brush Aura1 = Hx("#FFE3F2", 0.13);
        static readonly Brush Aura2 = Hx("#FFF0F8", 0.12);
        static readonly Brush Star = Hx("#FFF4B0");

        static readonly Geometry[] MouthGeo =
        {
            G("M-4.4,0 Q-2.2,3.6 0,1.4 Q2.2,3.6 4.4,0"),                          // idle (cat smile)
            G("M-5.8,-0.6 Q0,0.5 5.8,-0.6 Q5,6.8 0,6.8 Q-5,6.8 -5.8,-0.6 Z"),    // happy (open)
            G("M-3.8,1.8 Q0,-0.7 3.8,1.8"),                                        // thirsty
            G("M-5.2,3.2 Q0,-3.2 5.2,3.2"),                                        // very thirsty
            G("M-2.6,0 Q0,2.3 2.6,0"),                                             // sleeping
        };
        static readonly Geometry DropGeo = G("M0,-5 C-1,-1.5 -3.4,0.8 -3.4,3 A3.4,3.4 0 0 0 3.4,3 C3.4,0.8 1,-1.5 0,-5 Z");
        static readonly Geometry StarGeo = G("M0,-1 L0.22,-0.22 1,0 0.22,0.22 0,1 -0.22,0.22 -1,0 -0.22,-0.22 Z");
        static readonly Geometry TongueGeo = G("M-2.7,4.6 Q0,3.2 2.7,4.6 Q2.4,6.7 0,6.7 Q-2.4,6.7 -2.7,4.6 Z");
        static readonly Geometry TailBaby = G("M62,85 C68,83 73,85 73,88.5 C72,92 66,92.5 62,90.5 Z");
        static readonly Geometry TailTeen = G("M74,77 C82,78 87,73 88,64 C93,72 92,86 76,90.5 Z");
        static readonly Geometry TailAdult = G("M79,74.5 C87,75 91,68 91,56 C96,66 96,85 80,91.5 Z");
        static readonly Geometry CrownGeo = G("M-13,4.5 L-14.5,-12 L-7,-6 L0,-16.5 L7,-6 L14.5,-12 L13,4.5 Q0,-0.5 -13,4.5 Z");
        static readonly Geometry BowL = G("M0,0 C-4,-7.5 -13,-6.5 -13,0 C-13,6.5 -4,7.5 0,0 Z");
        static readonly Geometry BowR = G("M0,0 C4,-7.5 13,-6.5 13,0 C13,6.5 4,7.5 0,0 Z");
        static readonly Geometry BowTailL = G("M-1,2 L-6.5,10 L-2.2,8.4 Z");
        static readonly Geometry BowTailR = G("M1,2 L6.5,10 L2.2,8.4 Z");

        static Ellipse El(double cx, double cy, double rx, double ry, Brush? fill, Brush? stroke = null, double sw = 0)
        {
            var e = new Ellipse { Width = rx * 2, Height = ry * 2, Fill = fill, Stroke = stroke, StrokeThickness = sw };
            return At(e, cx - rx, cy - ry);
        }
        static Path Pa(Geometry g, Brush? fill, Brush? stroke = null, double sw = 0) => new Path
        {
            Data = g, Fill = fill, Stroke = stroke, StrokeThickness = sw,
            StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round
        };
        static Line Ln(double x1, double y1, double x2, double y2, double th, Brush stroke) => new Line
        {
            X1 = x1, Y1 = y1, X2 = x2, Y2 = y2, Stroke = stroke, StrokeThickness = th,
            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round
        };
        static Geometry QCurve(double x1, double y1, double cx, double cy, double x2, double y2)
        {
            var f = new PathFigure { StartPoint = new Point(x1, y1), IsClosed = false };
            f.Segments.Add(new QuadraticBezierSegment(new Point(cx, cy), new Point(x2, y2), true));
            var g = new PathGeometry(); g.Figures.Add(f); g.Freeze();
            return g;
        }
        static Geometry WaveGeo(bool closed)
        {
            const double half = 12.5, amp = 3.2; const int n = 12;
            var f = new PathFigure { StartPoint = new Point(0, 0), IsClosed = closed };
            for (int i = 0; i < n; i++)
                f.Segments.Add(new QuadraticBezierSegment(new Point(half * i + half / 2, i % 2 == 0 ? -amp : amp), new Point(half * (i + 1), 0), true));
            if (closed)
            {
                f.Segments.Add(new LineSegment(new Point(half * n, 120), false));
                f.Segments.Add(new LineSegment(new Point(0, 120), false));
            }
            var g = new PathGeometry(); g.Figures.Add(f); g.Freeze();
            return g;
        }

        static (string top, string bot, string line, string gill, string tip) Pal(PetMood m, bool royal) => m switch
        {
            PetMood.Happy => royal ? ("#FFCBDF", "#FF9FC9", "#EE78A6", "#FF58A8", "#FFC3E1") : ("#FFC0D8", "#FF9AC2", "#EC76A4", "#EA4A8E", "#FF8DBA"),
            PetMood.Thirsty => ("#F1CBD9", "#DFA3BC", "#C48AA3", "#B9618C", "#D68CAD"),
            PetMood.VeryThirsty => ("#CEC1C9", "#AB9AA6", "#968391", "#85647A", "#A3869A"),
            PetMood.Sleeping => royal ? ("#F2BFD6", "#EA97BD", "#D571A0", "#EE4B93", "#F5A6CB") : ("#F6B3CE", "#EE8DB6", "#DB6C98", "#D43F7C", "#F27BAB"),
            _ => royal ? ("#FFC6DB", "#FF9CC6", "#EE78A6", "#FF4FA0", "#FFC0DF") : ("#FFB8D3", "#FF93BC", "#EA6F9C", "#E0407F", "#FF80B0"),
        };

        // ---------- live state ----------
        sealed class GillRef { public RotateTransform Droop = null!, Wave = null!; public int Side; }
        readonly List<GillRef> _gills = new();
        readonly List<ScaleTransform> _eyeScale = new();
        readonly List<TranslateTransform> _eyeMove = new();

        LinearGradientBrush _skin = null!;
        GradientStop _sTop = null!, _sBot = null!;
        SolidColorBrush _outline = null!, _gill = null!, _tip = null!;
        ScaleTransform _stageTf = null!, _sleepScale = null!;
        TranslateTransform _sleepMove = null!;
        RotateTransform? _tailRot;
        Canvas? _eyesOpen, _eyesClosed, _brows, _drop, _burst, _water;
        TranslateTransform? _burstMove, _waveMove;
        Path _mouth = null!, _tongue = null!;
        readonly List<UIElement> _cheeks = new();
        double _hx, _hy;
        bool _ready;

        // =====================================================================
        // Scene: glass tank + water
        // =====================================================================
        protected override void BuildScene(Canvas scene)
        {
            _water = null; _waveMove = null;
            if (Compact) return;

            var tank = Pa(new RectangleGeometry(new Rect(4, 8, 92, 89), 13, 13), Grad("#FFFFFF", "#9FE3FF", 0.06), Hx("#FFFFFF", 0.42), 1.6);
            scene.Children.Add(tank);

            var clip = new RectangleGeometry(new Rect(5.5, 9.5, 89, 86), 12, 12); clip.Freeze();
            var host = new Canvas { Width = 100, Height = 100, Clip = clip };
            _water = new Canvas { Width = 100, Height = 100 };
            _waveMove = new TranslateTransform();
            var waveC = new Canvas { RenderTransform = _waveMove };
            var fillBrush = new LinearGradientBrush(new GradientStopCollection
            {
                new GradientStop(Color.FromArgb(0x92, 0x6F, 0xD6, 0xFA), 0),
                new GradientStop(Color.FromArgb(0x8A, 0x3A, 0xA0, 0xE0), 0.55),
                new GradientStop(Color.FromArgb(0x96, 0x24, 0x78, 0xC4), 1),
            }, 90);
            fillBrush.Freeze();
            waveC.Children.Add(Pa(WaveGeo(true), fillBrush));
            waveC.Children.Add(Pa(WaveGeo(false), null, Hx("#E6F8FF", 0.7), 1.4));
            _water.Children.Add(waveC);
            // tiny bubbles inside the water (they ride with the surface)
            foreach (var (x, y, r) in new[] { (15.0, 14.0, 2.0), (17.5, 27.0, 1.2), (84.0, 22.0, 1.6), (88.0, 40.0, 1.0) })
                _water.Children.Add(El(x, y, r, r, Hx("#FFFFFF", 0.14), Hx("#FFFFFF", 0.6), 0.8));
            host.Children.Add(_water);

            // sand + pebbles
            host.Children.Add(At(new Rectangle { Width = 89, Height = 5, Fill = Grad("#E8CC9A", "#CDAA74", 0.9) }, 5.5, 90.8));
            foreach (var (x, y, rx, ry, col) in new[]
                     { (14.0, 91.4, 3.4, 2.1, "#B8A8B8"), (20.0, 92.6, 2.2, 1.5, "#E9B79A"), (83.0, 91.6, 3.0, 2.0, "#A9B8C8"), (89.0, 92.7, 2.0, 1.4, "#D9C2E0"), (74.0, 93.2, 1.6, 1.0, "#E9B79A") })
                host.Children.Add(El(x, y, rx, ry, Hx(col, 0.95)));
            scene.Children.Add(host);

            // glass reflection
            scene.Children.Add(Pa(QCurve(11, 24, 10.2, 31, 11, 40), null, Hx("#FFFFFF", 0.38), 1.8));
            scene.Children.Add(El(11, 46, 1, 1, Hx("#FFFFFF", 0.38)));
        }

        public override void SetWater(double fraction, bool animate)
        {
            Water = Math.Clamp(fraction, 0, 1);
            if (_water == null) return;
            double y = 95.5 - (8 + Water * 66);
            if (animate)
            {
                var a = new DoubleAnimation(y, TimeSpan.FromMilliseconds(750)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
                _water.BeginAnimation(Canvas.TopProperty, a);
            }
            else
            {
                _water.BeginAnimation(Canvas.TopProperty, null);
                Canvas.SetTop(_water, y);
            }
        }

        // =====================================================================
        // Body
        // =====================================================================
        protected override void BuildBody(Canvas body)
        {
            _ready = false;
            _gills.Clear(); _eyeScale.Clear(); _eyeMove.Clear(); _cheeks.Clear();
            _tailRot = null; _burst = null; _burstMove = null;

            _skin = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(0, 1) };
            _sTop = new GradientStop(Colors.White, 0); _sBot = new GradientStop(Colors.White, 1);
            _skin.GradientStops.Add(_sTop); _skin.GradientStops.Add(_sBot);
            _outline = new SolidColorBrush(Colors.White);
            _gill = new SolidColorBrush(Colors.White);
            _tip = new SolidColorBrush(Colors.White);

            int st = Stage;
            bool royal = st == 3;
            double hx, hy, hrx, hry, gl, gth, sc = 1;
            if (Compact) { hx = 50; hy = 59; hrx = 29; hry = 24; gl = 17 + st * 1.2; gth = 6.4; }
            else if (st == 0) { hx = 50; hy = 71; hrx = 23; hry = 19; gl = 8.5; gth = 3.5; }
            else if (st == 1) { hx = 44; hy = 66; hrx = 20; hry = 16.5; gl = 12; gth = 3.2; }
            else { hx = 42; hy = 62; hrx = 19.5; hry = 16.5; gl = st == 2 ? 15 : 16.5; gth = 3.3; if (royal) sc = 1.05; }
            _hx = hx; _hy = hy;
            double ow = Compact ? 2.2 : 1.2;

            _stageTf = new ScaleTransform(sc, sc, 50, 92);
            _sleepScale = new ScaleTransform(1, 1, 50, 92);
            _sleepMove = new TranslateTransform();
            var tg = new TransformGroup();
            tg.Children.Add(_stageTf); tg.Children.Add(_sleepScale); tg.Children.Add(_sleepMove);
            var inner = new Canvas { Width = 100, Height = 100, RenderTransform = tg };
            body.Children.Add(inner);

            // royal aura (layered translucent discs instead of blur)
            if (royal)
            {
            }

            // ---- tail, body, legs
            if (!Compact)
            {
                var tail = new Canvas { Width = 100, Height = 100 };
                tail.Children.Add(Pa(st == 0 ? TailBaby : st == 1 ? TailTeen : TailAdult, _skin, _outline, ow));
                var piv = st == 0 ? new Point(64, 88) : st == 1 ? new Point(77, 84) : new Point(80, 83);
                _tailRot = new RotateTransform(0, piv.X, piv.Y);
                tail.RenderTransform = _tailRot;
                inner.Children.Add(tail);

                double bx, by, brx, bry;
                if (st == 0) { bx = 50; by = 86; brx = 14; bry = 8; }
                else if (st == 1) { bx = 58; by = 83; brx = 25; bry = 9.5; }
                else { bx = 60; by = 81; brx = 27; bry = 11; }
                inner.Children.Add(El(bx, by, brx, bry, _skin, _outline, ow));
                inner.Children.Add(El(bx, by + bry * 0.4, brx * 0.7, bry * 0.4, WhiteFaint));

                (double x, double y, double rx, double ry)[] legs = st == 0
                    ? new[] { (39.0, 91.5, 5.6, 3.2), (61.0, 91.5, 5.6, 3.2) }
                    : st == 1
                        ? new[] { (36.0, 91.0, 4.8, 3.0), (51.0, 91.4, 4.8, 3.0), (70.0, 90.6, 4.8, 3.0) }
                        : new[] { (35.0, 91.0, 5.8, 3.6), (50.0, 91.6, 5.8, 3.6), (70.0, 91.0, 6.0, 3.6), (83.0, 89.6, 5.4, 3.4) };
                foreach (var l in legs) inner.Children.Add(El(l.x, l.y, l.rx, l.ry, _skin, _outline, ow));
            }

            // ---- gills (behind head)
            double[] phi = { 52, 22, -8 }, ang = { 64, 30, -2 }, lf = { 1.0, 0.9, 0.78 };
            for (int s = -1; s <= 1; s += 2)
                for (int i = 0; i < 3; i++)
                {
                    double p = phi[i] * Math.PI / 180;
                    AddGill(inner, hx + s * hrx * 0.88 * Math.Cos(p), hy - hry * 0.88 * Math.Sin(p), s, ang[i], gl * lf[i], gth, royal);
                }

            // ---- head
            inner.Children.Add(El(hx, hy, hrx, hry, _skin, _outline, ow));
            var shine = El(hx - hrx * 0.38, hy - hry * 0.58, hrx * 0.3, hry * 0.13, WhiteHi);
            shine.RenderTransform = new RotateTransform(-16, hrx * 0.3, hry * 0.13);
            inner.Children.Add(shine);

            // ---- face
            double er = hrx * (st == 0 || Compact ? 0.29 : 0.25);
            double eoff = hrx * 0.5, ey = hy + hry * 0.05;
            double mk = hrx / 20.0 * (Compact ? 1.25 : 1.0);

            foreach (int s in new[] { -1, 1 })   // cheeks first (under eyes/mouth)
            {
                var ch = El(hx + s * hrx * 0.74, hy + hry * 0.46, er * 0.85, er * 0.5, BlushFill);
                ch.Opacity = 0;
                _cheeks.Add(ch); inner.Children.Add(ch);
            }

            _eyesOpen = new Canvas { Width = 100, Height = 100 };
            _eyesClosed = new Canvas { Width = 100, Height = 100 };
            _brows = new Canvas { Width = 100, Height = 100, Visibility = Visibility.Collapsed };
            foreach (int s in new[] { -1, 1 })
            {
                double ex = hx + s * eoff;
                var sct = new ScaleTransform(1, 1, ex, ey);
                var mv = new TranslateTransform();
                var tgrp = new TransformGroup(); tgrp.Children.Add(sct); tgrp.Children.Add(mv);
                var ec = new Canvas { Width = 100, Height = 100, RenderTransform = tgrp };
                ec.Children.Add(El(ex, ey, er, er * 1.12, EyeFill));
                ec.Children.Add(El(ex - er * 0.32, ey - er * 0.38, er * 0.36, er * 0.36, White));
                ec.Children.Add(El(ex + er * 0.4, ey + er * 0.42, er * 0.17, er * 0.17, WhiteSoft));
                _eyesOpen.Children.Add(ec);
                _eyeScale.Add(sct); _eyeMove.Add(mv);

                _eyesClosed.Children.Add(Pa(QCurve(ex - er * 0.95, ey, ex, ey + er * 1.0, ex + er * 0.95, ey), null, Ink, Compact ? 2.8 : 1.8));
                if (!Compact)
                    _brows.Children.Add(Ln(ex + s * er * 1.1, ey - er * 1.55, ex - s * er * 1.0, ey - er * 2.1, 1.6, BrowInk));
            }
            _eyesClosed.Visibility = Visibility.Collapsed;
            inner.Children.Add(_eyesOpen); inner.Children.Add(_eyesClosed); inner.Children.Add(_brows);

            double my = hy + hry * 0.52;
            _mouth = new Path
            {
                Stroke = Ink, StrokeThickness = 1.7, StrokeLineJoin = PenLineJoin.Round,
                StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
                RenderTransform = new ScaleTransform(mk, mk)
            };
            _tongue = new Path { Data = TongueGeo, Fill = TongueFill, RenderTransform = new ScaleTransform(mk, mk), Visibility = Visibility.Collapsed };
            inner.Children.Add(At(_mouth, hx, my));
            inner.Children.Add(At(_tongue, hx, my));

            // sweat drop (thirsty)
            double dk = Compact ? 1.9 : 1.15 * hrx / 20;
            _drop = new Canvas { Visibility = Visibility.Collapsed };
            var dp = Pa(DropGeo, DropFill); dp.RenderTransform = new ScaleTransform(dk, dk);
            _drop.Children.Add(At(dp, hx + hrx * 0.84, hy - hry * 0.62));
            inner.Children.Add(_drop);

            // royal sparkle spots
            if (royal)
            {
                double s1 = Compact ? 4.2 : 2.6;
                inner.Children.Add(At(Pa(StarGeo, Star), hx - hrx * 0.55, hy - hry * 0.2).Also(p => p.RenderTransform = new ScaleTransform(s1, s1)));
                inner.Children.Add(At(Pa(StarGeo, Star), hx + hrx * 0.62, hy - hry * 0.38).Also(p => p.RenderTransform = new ScaleTransform(s1 * 0.8, s1 * 0.8)));
                if (!Compact)
                {
                    inner.Children.Add(At(Pa(StarGeo, Star), 64, 76).Also(p => p.RenderTransform = new ScaleTransform(2.4, 2.4)));
                    inner.Children.Add(At(Pa(StarGeo, Star), 76, 83).Also(p => p.RenderTransform = new ScaleTransform(1.7, 1.7)));
                    inner.Children.Add(At(Pa(StarGeo, Star), 52, 85).Also(p => p.RenderTransform = new ScaleTransform(1.5, 1.5)));
                }
            }

            AddCosmetic(inner, hx, hy, hrx, hry);

            // drink bubbles (live in Fx so the body squash does not distort them)
            if (!Compact)
            {
                _burstMove = new TranslateTransform();
                _burst = new Canvas { Opacity = 0, RenderTransform = _burstMove };
                foreach (var (dx, dy, r) in new[] { (-6.0, 0.0, 2.4), (3.0, -5.0, 1.8), (9.0, 2.0, 2.0), (-1.0, -11.0, 1.4) })
                    _burst.Children.Add(El(hx + dx, hy + hry * 0.2 + dy, r, r, Hx("#FFFFFF", 0.15), Hx("#FFFFFF", 0.85), 0.9));
                Fx.Children.Add(_burst);
            }

            _ready = true;
        }

        void AddGill(Canvas host, double ax, double ay, int side, double angDeg, double len, double th, bool royal)
        {
            double a = angDeg * Math.PI / 180;
            double dx = side * Math.Cos(a), dy = -Math.Sin(a);
            double ex = ax + dx * len, ey = ay + dy * len;
            var c = new Canvas { Width = 100, Height = 100 };
            if (royal) c.Children.Add(Ln(ax, ay, ex, ey, th + (Compact ? 5 : 4.2), Halo));
            c.Children.Add(Ln(ax, ay, ex, ey, th, _gill));
            if (!Compact && len >= 10)
            {
                foreach (double t in new[] { 0.58 })
                    foreach (int fs in new[] { -1, 1 })
                    {
                        double px = ax + dx * len * t, py = ay + dy * len * t;
                        double th2 = fs * 0.95; // ~55 degrees
                        double fx = dx * Math.Cos(th2) - dy * Math.Sin(th2), fy = dx * Math.Sin(th2) + dy * Math.Cos(th2);
                        double fl = len * 0.4;
                        c.Children.Add(Ln(px, py, px + fx * fl, py + fy * fl, th * 0.78, _gill));
                        c.Children.Add(El(px + fx * fl, py + fy * fl, th * 0.62, th * 0.62, _tip));
                    }
            }
            c.Children.Add(El(ex, ey, th * 0.95, th * 0.95, _tip));
            var droop = new RotateTransform(0, ax, ay);
            var wave = new RotateTransform(0, ax, ay);
            var g = new TransformGroup(); g.Children.Add(droop); g.Children.Add(wave);
            c.RenderTransform = g;
            host.Children.Add(c);
            _gills.Add(new GillRef { Droop = droop, Wave = wave, Side = side });
        }

        // ---------- cosmetics ----------
        void AddCosmetic(Canvas inner, double hx, double hy, double hrx, double hry)
        {
            if (Cosmetic == 0) return;
            double cs = hrx / 18.5;
            if (Cosmetic == 2)
            {
                var c = new Canvas();
                c.Children.Add(Pa(CrownGeo, GoldFill, GoldLine, 1.0));
                foreach (var (x, y) in new[] { (-14.5, -12.0), (0.0, -16.5), (14.5, -12.0) }) c.Children.Add(El(x, y, 1.9, 1.9, GoldFill, GoldLine, 0.7));
                c.Children.Add(El(0, -4.6, 2.2, 2.2, GemRed));
                c.Children.Add(El(-7.5, -1.8, 1.6, 1.6, GemBlue));
                c.Children.Add(El(7.5, -1.8, 1.6, 1.6, GemBlue));
                Place(c, hx, hy - hry + 1.2, cs, 0);
                inner.Children.Add(c);
            }
            else if (Cosmetic == 1)
            {
                double t = 52 * Math.PI / 180;
                var c = new Canvas();
                c.Children.Add(Pa(BowTailL, BowFill, BowDark, 0.6));
                c.Children.Add(Pa(BowTailR, BowFill, BowDark, 0.6));
                c.Children.Add(Pa(BowL, BowFill, BowDark, 0.9));
                c.Children.Add(Pa(BowR, BowFill, BowDark, 0.9));
                c.Children.Add(El(0, 0, 2.7, 2.7, BowDark));
                c.Children.Add(El(-0.6, -0.7, 0.9, 0.9, WhiteSoft));
                Place(c, hx + hrx * 0.74 * Math.Cos(t) / 0.78 * 0.9, hy - hry * 0.95 * Math.Sin(t) * 0.97, cs * 0.95, 18);
                inner.Children.Add(c);
            }
            else
            {
                string[] pet = { "#FFFFFF", "#DDBEFF", "#FFC9AA", "#FFE98A", "#FFFFFF" };
                double[] ts = { 158, 126, 94, 62, 30 };
                var c = new Canvas();
                for (int i = 0; i < 4; i++)
                {
                    double m = (ts[i] + ts[i + 1]) / 2 * Math.PI / 180;
                    var lf = El(0, 0, 3.8 * cs, 1.9 * cs, Leaf);
                    lf.RenderTransform = new RotateTransform((ts[i] + ts[i + 1]) / 2 - 90 + 90, 3.8 * cs, 1.9 * cs);
                    At(lf, hx + hrx * 0.98 * Math.Cos(m) - 3.8 * cs, hy - hry * 1.0 * Math.Sin(m) - 1.9 * cs);
                    c.Children.Add(lf);
                }
                double fr = Math.Max(3.4, hrx * 0.17);
                for (int i = 0; i < 5; i++)
                {
                    double t = ts[i] * Math.PI / 180;
                    double fx = hx + hrx * 0.98 * Math.Cos(t), fy = hy - hry * 1.0 * Math.Sin(t);
                    var pb = Hx(pet[i]);
                    for (int k = 0; k < 5; k++)
                    {
                        double pa = k * 2 * Math.PI / 5 - Math.PI / 2;
                        c.Children.Add(El(fx + Math.Cos(pa) * fr * 0.62, fy + Math.Sin(pa) * fr * 0.62, fr * 0.52, fr * 0.52, pb, Hx("#E58AB0", 0.5), 0.5));
                    }
                    c.Children.Add(El(fx, fy, fr * 0.42, fr * 0.42, Hx(i == 3 ? "#FF9F43" : "#FFC83D")));
                }
                inner.Children.Add(c);
            }
        }

        static void Place(Canvas c, double x, double y, double scale, double rot)
        {
            var g = new TransformGroup();
            g.Children.Add(new ScaleTransform(scale, scale));
            g.Children.Add(new RotateTransform(rot));
            g.Children.Add(new TranslateTransform(x, y));
            c.RenderTransform = g;
        }

        // =====================================================================
        // Mood / eyes
        // =====================================================================
        public override void SetMood(PetMood m)
        {
            Mood = m;
            if (!_ready) return;
            SetSleepFx(m == PetMood.Sleeping);

            var p = Pal(m, Stage == 3);
            _sTop.Color = C(p.top); _sBot.Color = C(p.bot);
            _outline.Color = C(p.line); _gill.Color = C(p.gill); _tip.Color = C(p.tip);

            bool sleeping = m == PetMood.Sleeping;
            _eyesOpen!.Visibility = sleeping ? Visibility.Collapsed : Visibility.Visible;
            _eyesClosed!.Visibility = sleeping ? Visibility.Visible : Visibility.Collapsed;
            _brows!.Visibility = m == PetMood.VeryThirsty && !Compact ? Visibility.Visible : Visibility.Collapsed;
            _drop!.Visibility = m == PetMood.Thirsty || m == PetMood.VeryThirsty ? Visibility.Visible : Visibility.Collapsed;

            double eyeY = m switch { PetMood.Thirsty => 0.86, PetMood.VeryThirsty => 0.68, _ => 1.0 };
            foreach (var s in _eyeScale) { s.BeginAnimation(ScaleTransform.ScaleYProperty, null); s.ScaleY = eyeY; }
            if (sleeping) foreach (var t in _eyeMove) { t.X = 0; t.Y = 0; }

            _mouth.Data = MouthGeo[(int)m];
            _mouth.Fill = m == PetMood.Happy ? MouthFill : null;
            _tongue.Visibility = m == PetMood.Happy ? Visibility.Visible : Visibility.Collapsed;

            double blush = m switch { PetMood.Happy => 0.8, PetMood.Idle => 0.3, PetMood.Sleeping => 0.35, _ => 0 };
            foreach (var c in _cheeks) c.Opacity = blush;

            double droop = m switch { PetMood.Happy => -6, PetMood.Thirsty => 20, PetMood.VeryThirsty => 42, PetMood.Sleeping => 14, _ => 0 };
            foreach (var g in _gills) g.Droop.Angle = g.Side * droop;

            _sleepScale.ScaleY = sleeping ? 0.93 : 1;
            _sleepScale.ScaleX = sleeping ? 1.03 : 1;
            _sleepMove.Y = sleeping ? 2.5 : (m == PetMood.VeryThirsty ? 1.5 : 0);

            if (Idling) StartIdle();
        }

        public override void LookAt(double dx, double dy)
        {
            if (!_ready || Mood == PetMood.Sleeping) return;
            double x = Math.Clamp(dx, -1, 1) * 2.5, y = Math.Clamp(dy, -1, 1) * 2.0;
            foreach (var t in _eyeMove) { t.X = x; t.Y = y; }
        }

        public override void Blink()
        {
            if (!_ready || Mood == PetMood.Sleeping) return;
            double rest = _eyeScale.Count > 0 ? _eyeScale[0].ScaleY : 1;
            foreach (var s in _eyeScale)
            {
                var k = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
                k.KeyFrames.Add(new LinearDoubleKeyFrame(0.08, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(70))));
                k.KeyFrames.Add(new LinearDoubleKeyFrame(rest, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(170))));
                s.BeginAnimation(ScaleTransform.ScaleYProperty, k);
            }
        }

        // =====================================================================
        // Idle: gentle gill wave, tail sway, water ripple
        // =====================================================================
        public override void StartIdle()
        {
            base.StartIdle();
            if (!_ready) return;
            double amp = Mood == PetMood.Sleeping ? 2.5 : (Mood == PetMood.VeryThirsty ? 3 : 7);
            for (int i = 0; i < _gills.Count; i++)
            {
                var g = _gills[i];
                var a = new DoubleAnimation(-amp * g.Side, amp * g.Side, TimeSpan.FromMilliseconds(1100 + (i % 3) * 170))
                {
                    AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut },
                    BeginTime = TimeSpan.FromMilliseconds((i % 3) * 140)
                };
                Timeline.SetDesiredFrameRate(a, 30);
                g.Wave.BeginAnimation(RotateTransform.AngleProperty, a);
            }
            if (_tailRot != null)
            {
                var t = new DoubleAnimation(-4.5, 4.5, TimeSpan.FromMilliseconds(1700))
                { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
                Timeline.SetDesiredFrameRate(t, 30);
                _tailRot.BeginAnimation(RotateTransform.AngleProperty, t);
            }
            if (_waveMove != null)
            {
                var w = new DoubleAnimation(0, -25, TimeSpan.FromMilliseconds(3200)) { RepeatBehavior = RepeatBehavior.Forever };
                Timeline.SetDesiredFrameRate(w, 30);
                _waveMove.BeginAnimation(TranslateTransform.XProperty, w);
            }
        }

        public override void StopIdle()
        {
            base.StopIdle();
            foreach (var g in _gills) { g.Wave.BeginAnimation(RotateTransform.AngleProperty, null); g.Wave.Angle = 0; }
            if (_tailRot != null) { _tailRot.BeginAnimation(RotateTransform.AngleProperty, null); _tailRot.Angle = 0; }
            if (_waveMove != null) { _waveMove.BeginAnimation(TranslateTransform.XProperty, null); _waveMove.X = 0; }
        }

        public override void PlayDrink()
        {
            base.PlayDrink();
            if (_burst == null || _burstMove == null) return;
            var o = new DoubleAnimationUsingKeyFrames();
            o.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(100))));
            o.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(650))));
            o.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(950))));
            _burst.BeginAnimation(UIElement.OpacityProperty, o);
            var y = new DoubleAnimation(4, -16, TimeSpan.FromMilliseconds(950)) { EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut } };
            _burstMove.BeginAnimation(TranslateTransform.YProperty, y);
        }
    }

    static class AxolotlExt
    {
        public static T Also<T>(this T t, Action<T> a) { a(t); return t; }
    }
}
