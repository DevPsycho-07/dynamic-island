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
    /// Sea otter. Floats upright in a round porthole "pool": water level = today's progress.
    /// Low water: sits on dry sand looking parched. High water: floats happily, belly in the water.
    /// </summary>
    public sealed class OtterSkin : PetSkin
    {
        public override PetKind Kind => PetKind.Otter;
        public override string Name => "Otter";
        static readonly string[] Stages = { "Pup", "Kit", "Adult", "Elder" };
        static readonly string[] Cosmetics = { "None", "Beanie", "Sunglasses", "Bow tie" };
        public override string[] StageNames => Stages;
        public override string[] CosmeticNames => Cosmetics;

        // ---------- palette ----------
        static Color Cc(string hex, byte a = 255) { var c = (Color)ColorConverter.ConvertFromString(hex); c.A = a; return c; }
        static SolidColorBrush S(string hex, byte a = 255) { var s = new SolidColorBrush(Cc(hex, a)); s.Freeze(); return s; }
        static LinearGradientBrush Grad(string a, string b, byte aa = 255, byte ab = 255)
        {
            var g = new LinearGradientBrush(Cc(a, aa), Cc(b, ab), new Point(0, 0), new Point(0, 1)); g.Freeze(); return g;
        }
        static LinearGradientBrush Grad3(string a, string b, string c, byte aa = 255, byte ab = 255, byte ac = 255)
        {
            var g = new LinearGradientBrush();
            g.StartPoint = new Point(0, 0); g.EndPoint = new Point(0, 1);
            g.GradientStops.Add(new GradientStop(Cc(a, aa), 0));
            g.GradientStops.Add(new GradientStop(Cc(b, ab), 0.4));
            g.GradientStops.Add(new GradientStop(Cc(c, ac), 1));
            g.Freeze(); return g;
        }

        static readonly Brush FurN = Grad("#9C6B4E", "#6D4632");
        static readonly Brush FurP = Grad("#B9A093", "#8D7468");
        static readonly Brush TailN = S("#7A4F37");
        static readonly Brush TailP = S("#9C8576");
        static readonly Brush BellyN = Grad("#F3DEC4", "#E2C197");
        static readonly Brush BellyP = Grad("#E8DDD0", "#CFBFAD");
        static readonly Brush Cream = Grad("#F8E8D2", "#EBD1AE");
        static readonly Brush Dark = S("#2A1A13");
        static readonly Brush EyeFill = Grad("#4D3428", "#140B07");
        static readonly Brush White = S("#FFFFFF");
        static readonly Brush Whisk = S("#FFF6E6");
        static readonly Brush Silver = S("#E4E9ED");

        // ---------- stage layout: headScale, headX, headY, bodyX, bodyY, bodyRx, bodyRy, rigScale ----------
        static readonly double[][] Lay =
        {
            new[] { 1.30, 50, 48, 50, 71, 20, 16, 1.00 },
            new[] { 1.00, 50, 40, 50, 67, 16, 19, 1.00 },
            new[] { 1.06, 50, 38, 50, 66, 24, 22, 1.00 },
            new[] { 1.10, 50, 38, 50, 66, 25, 22, 1.06 },
        };

        // ---------- geometry helpers ----------
        static Path Shp(Geometry g, Brush? fill, Brush? stroke = null, double sw = 0)
        {
            var p = new Path { Data = g, Fill = fill, Stroke = stroke, StrokeThickness = sw };
            if (stroke != null) { p.StrokeStartLineCap = PenLineCap.Round; p.StrokeEndLineCap = PenLineCap.Round; p.StrokeLineJoin = PenLineJoin.Round; }
            return p;
        }
        static Path El(double cx, double cy, double rx, double ry, Brush fill, Brush? stroke = null, double sw = 0)
        {
            var g = new EllipseGeometry(new Point(cx, cy), rx, ry); g.Freeze(); return Shp(g, fill, stroke, sw);
        }
        static Path Ln(string d, Brush stroke, double w) => Shp(G(d), null, stroke, w);
        static Path Fl(string d, Brush fill) => Shp(G(d), fill);
        static Geometry Q(double x0, double y0, double cx, double cy, double x1, double y1)
        {
            var sg = new StreamGeometry();
            using (var c = sg.Open()) { c.BeginFigure(new Point(x0, y0), false, false); c.QuadraticBezierTo(new Point(cx, cy), new Point(x1, y1), true, true); }
            sg.Freeze(); return sg;
        }
        static Path LQ(double x0, double y0, double cx, double cy, double x1, double y1, Brush s, double w) => Shp(Q(x0, y0, cx, cy, x1, y1), null, s, w);

        static Geometry Fluffy(double cx, double cy, double rx, double ry, int bumps, double amp)
        {
            var sg = new StreamGeometry();
            using (var c = sg.Open())
            {
                const int n = 120;
                for (int i = 0; i < n; i++)
                {
                    double th = Math.PI * 2 * i / n;
                    double r = (1 - amp) + amp * Math.Abs(Math.Sin(bumps * th / 2.0)) * 1.0;
                    var pt = new Point(cx + rx * r * Math.Cos(th), cy + ry * r * Math.Sin(th));
                    if (i == 0) c.BeginFigure(pt, true, true); else c.LineTo(pt, true, true);
                }
            }
            sg.Freeze(); return sg;
        }

        const double WavePeriod = 26;
        static Geometry WaveGeom(double amp, double phase, bool fill)
        {
            var sg = new StreamGeometry();
            using (var c = sg.Open())
            {
                double x0 = -50, x1 = 160;
                double Y(double x) => amp * Math.Sin(2 * Math.PI * x / WavePeriod + phase);
                c.BeginFigure(new Point(x0, Y(x0)), fill, fill);
                for (double x = x0 + 1; x <= x1; x += 1) c.LineTo(new Point(x, Y(x)), true, true);
                if (fill) { c.LineTo(new Point(x1, 72), false, false); c.LineTo(new Point(x0, 72), false, false); }
            }
            sg.Freeze(); return sg;
        }

        static Visibility V(bool b) => b ? Visibility.Visible : Visibility.Collapsed;

        // ---------- state ----------
        sealed class Fur { public Path P = null!; public Brush N = null!, Pa = null!; public bool Stroke; }
        sealed class EyeParts
        {
            public readonly Canvas Root = new(); public readonly Canvas Open = new();
            public Path Happy = null!, Sleep = null!;
            public readonly ScaleTransform Blink = new(1, 1); public readonly TranslateTransform Look = new();
        }

        readonly List<Fur> _fur = new();
        EyeParts _eL = new(), _eR = new();
        Canvas _rig = null!, _headG = null!, _blush = null!, _sweat = null!, _sadBrows = null!;
        Canvas _mIdle = null!, _mHappy = null!, _mThirsty = null!, _mSad = null!, _mSleep = null!;
        Canvas _cup = null!, _cupGlass = null!;
        Path _cupWater = null!, _cracks = null!;
        double _hs = 1;

        readonly RotateTransform _rock = new(0, 50, 78);
        readonly TranslateTransform _rise = new();
        readonly RotateTransform _headTilt = new(0, 0, 14);
        readonly TranslateTransform _headMove = new();
        readonly RotateTransform _cupRot = new(0, 0, 5.5);
        readonly TranslateTransform _cupMove = new();
        readonly TranslateTransform _pawL = new(), _pawR = new();
        RotateTransform _earL = new(0, -11, -9), _earR = new(0, 11, -9);
        readonly TranslateTransform _lvlBack = new(0, 100), _lvlFront = new(0, 101.5);
        readonly TranslateTransform _waveBack = new(0, 0), _waveFront = new(-12, 0);

        static void TimelineRate(Timeline t) => Timeline.SetDesiredFrameRate(t, 30);

        // =====================================================================================
        //  SCENE
        // =====================================================================================
        protected override void BuildScene(Canvas scene)
        {
            if (Compact) return;

            var clip = new EllipseGeometry(new Point(50, 52), 44, 44); clip.Freeze();
            var inner = new Canvas { Width = 100, Height = 100, Clip = clip };

            var bg = new RectangleGeometry(new Rect(0, 0, 100, 100)); bg.Freeze();
            inner.Children.Add(Shp(bg, Grad("#21496B", "#0C1E31")));

            // soft light shafts
            inner.Children.Add(Fl("M20,0 L34,0 L52,70 L30,70Z", S("#FFFFFF", 14)));
            inner.Children.Add(Fl("M58,0 L68,0 L84,70 L68,70Z", S("#FFFFFF", 10)));

            // sand + pebbles
            inner.Children.Add(Fl("M0,80 Q22,76 45,79 T100,78 L100,100 L0,100Z", Grad("#EDCB98", "#C39660")));
            inner.Children.Add(El(14, 90, 4.2, 2.6, S("#B58A58")));
            inner.Children.Add(El(84, 87, 3.6, 2.2, S("#B58A58")));
            inner.Children.Add(El(79, 92, 2.4, 1.5, S("#A87E4E")));
            inner.Children.Add(El(20, 84, 2, 1.3, S("#D9B382")));
            _cracks = Shp(G("M10,84 L18,87 L16,92 M18,87 L26,86 M86,82 L80,86 L83,92 M80,86 L72,85"), null, S("#8C6535", 220), 1.1);
            _cracks.StrokeLineJoin = PenLineJoin.Round;
            inner.Children.Add(_cracks);

            inner.Children.Add(MakeWave(2.3, 0, _waveBack, _lvlBack, Grad3("#81D4FA", "#43B4F2", "#1E88E5", 225, 232, 245), S("#E1F5FE", 190), 1.3));
            scene.Children.Add(inner);

            // porthole rim
            var ring = new EllipseGeometry(new Point(50, 52), 45, 45); ring.Freeze();
            scene.Children.Add(Shp(ring, null, Grad("#B8C7CF", "#4A6270"), 4.6));
            var ring2 = new EllipseGeometry(new Point(50, 52), 42.4, 42.4); ring2.Freeze();
            scene.Children.Add(Shp(ring2, null, S("#102532", 200), 1.2));
            for (int i = 0; i < 8; i++)
            {
                double a = Math.PI * 2 * i / 8 + Math.PI / 8;
                scene.Children.Add(El(50 + 45 * Math.Cos(a), 52 + 45 * Math.Sin(a), 1.35, 1.35, S("#EAF1F4")));
            }
            // glass glare
            scene.Children.Add(Ln("M20,34 A33,33 0 0 1 36,19", S("#FFFFFF", 105), 2.6));
            scene.Children.Add(El(41, 15.5, 1.3, 1.3, S("#FFFFFF", 105)));
        }

        static Canvas MakeWave(double amp, double phase, TranslateTransform wx, TranslateTransform lvl, Brush fill, Brush edge, double edgeW)
        {
            var c = new Canvas { Width = 100, Height = 100 };
            c.Children.Add(Shp(WaveGeom(amp, phase, true), fill));
            c.Children.Add(Shp(WaveGeom(amp, phase, false), null, edge, edgeW));
            var tg = new TransformGroup(); tg.Children.Add(wx); tg.Children.Add(lvl);
            c.RenderTransform = tg;
            return c;
        }

        void BuildFrontWater()
        {
            var clip = new EllipseGeometry(new Point(50, 52), 43.5, 43.5); clip.Freeze();
            var host = new Canvas { Width = 100, Height = 100, Clip = clip, IsHitTestVisible = false };
            host.Children.Add(MakeWave(1.8, 1.4, _waveFront, _lvlFront, Grad3("#81D4FA", "#4FC3F7", "#1E88E5", 82, 105, 140), S("#FFFFFF", 150), 1.2));
            Fx.Children.Add(host);
        }

        // =====================================================================================
        //  BODY
        // =====================================================================================
        protected override void BuildBody(Canvas body)
        {
            _fur.Clear();
            _eL = new EyeParts(); _eR = new EyeParts();
            _earL = new RotateTransform(0, -11, -9); _earR = new RotateTransform(0, 11, -9);
            var f = Lay[Stage];

            _rig = new Canvas { Width = 100, Height = 100 };
            if (!Compact)
            {
                var tg = new TransformGroup();
                tg.Children.Add(new ScaleTransform(f[7], f[7], 50, 90));
                tg.Children.Add(_rock);
                tg.Children.Add(_rise);
                _rig.RenderTransform = tg;
                BuildTorso(_rig, f);
            }
            BuildHead(_rig, f);
            if (!Compact) BuildCup(_rig, f);
            body.Children.Add(_rig);
            if (!Compact) BuildFrontWater();
        }

        Path AddFur(Canvas host, Path p, Brush n, Brush pa, bool stroke = false)
        {
            _fur.Add(new Fur { P = p, N = n, Pa = pa, Stroke = stroke });
            host.Children.Add(p);
            return p;
        }

        void BuildTorso(Canvas c, double[] f)
        {
            double bx = f[3], by = f[4], rx = f[5], ry = f[6];

            // tail (behind)
            if (Stage == 1) AddFur(c, Shp(G("M60,84 C73,88 81,80 79,69"), null, TailN, 6), TailN, TailP, true);
            else if (Stage >= 2)
            {
                double w = Stage == 3 ? 10 : 9;
                AddFur(c, Shp(G("M66,85 C84,91 94,79 89,62"), null, TailN, w), TailN, TailP, true);
                if (Stage == 3) c.Children.Add(El(89, 62.5, 3.2, 3.6, Silver));
            }

            // torso
            Geometry tg;
            if (Stage == 0) tg = Fluffy(bx, by, rx, ry, 14, 0.07);
            else { var e = new EllipseGeometry(new Point(bx, by), rx, ry); e.Freeze(); tg = e; }
            AddFur(c, Shp(tg, FurN), FurN, FurP);

            // belly
            AddFur(c, El(bx, by + ry * 0.14, rx * 0.74, ry * 0.76, BellyN), BellyN, BellyP);
            if (Stage >= 2)
            {
                // a few soft fur flicks on the belly
                c.Children.Add(Ln("M43,78 q1.5,-3 3,0", S("#C9A57A", 150), 1.1));
                c.Children.Add(Ln("M54,80 q1.5,-3 3,0", S("#C9A57A", 150), 1.1));
                c.Children.Add(Ln("M48,84 q1.5,-3 3,0", S("#C9A57A", 150), 1.1));
            }
            if (Stage == 3)
            {
                for (int i = 0; i < 9; i++)
                {
                    double a = (-170 + i * 17.5) * Math.PI / 180;
                    double px = bx + rx * Math.Cos(a), py = by + ry * Math.Sin(a);
                    double nx = Math.Cos(a), ny = Math.Sin(a);
                    c.Children.Add(LQ(px, py, px + nx * 2 + 1.5, py + ny * 2, px + nx * 3.6 + 2.2, py + ny * 3.6 - 1, Silver, 1.7));
                }
            }

            // hind feet
            double fy = by + ry - 1.2;
            AddFur(c, El(bx - rx * 0.52, fy, 7.6, 4.2, FurN), FurN, FurP);
            AddFur(c, El(bx + rx * 0.52, fy, 7.6, 4.2, FurN), FurN, FurP);
            c.Children.Add(Ln($"M{(bx - rx * 0.52 - 2).ToString("0.##", System.Globalization.CultureInfo.InvariantCulture)},{(fy + 0.8).ToString("0.##", System.Globalization.CultureInfo.InvariantCulture)} l0,2", S("#4A2E20", 150), 0.9));
        }

        void BuildCup(Canvas c, double[] f)
        {
            double cx = 50, cy;
            switch (Stage) { case 0: cy = f[4] + 1; break; case 1: cy = 66; break; default: cy = 65; break; }
            _cup = new Canvas { Width = 100, Height = 100 };
            _cupGlass = new Canvas(); _cupWater = new Path();
            var tg = new TransformGroup();
            tg.Children.Add(_cupRot); tg.Children.Add(_cupMove); tg.Children.Add(new TranslateTransform(cx, cy));
            _cup.RenderTransform = tg;

            if (Stage == 1)
            {
                // scallop shell bowl
                _cupGlass.Children.Add(Fl("M0,7 L-8.6,-0.6 Q-9.4,-8.4 0,-8.4 Q9.4,-8.4 8.6,-0.6 Z", Grad("#F8C8DA", "#EE87AC")));
                _cupGlass.Children.Add(Ln("M0,6 L-5.2,-6 M0,6 L0,-7.4 M0,6 L5.2,-6", S("#D9457F", 170), 1));
                _cupWater = Fl("M-7.8,-2.6 Q0,-5.6 7.8,-2.6 Q0,0.4 -7.8,-2.6Z", S("#4FC3F7"));
                _cupGlass.Children.Add(_cupWater);
            }
            else if (Stage >= 2)
            {
                var glass = Fl("M-6.6,-6 L6.6,-6 L5.1,6 Q0,7.4 -5.1,6 Z", Grad("#E1F5FE", "#B3E5FC", 150, 120));
                _cupGlass.Children.Add(glass);
                _cupWater = Fl("M-6,-1.6 Q0,0.2 6,-1.6 L5.1,6 Q0,7.4 -5.1,6 Z", Grad("#4FC3F7", "#1E88E5", 235, 245));
                _cupGlass.Children.Add(_cupWater);
                _cupGlass.Children.Add(Ln("M-6.6,-6 L6.6,-6", Stage == 3 ? S("#FFD54F") : S("#FFFFFF", 230), 1.4));
                _cupGlass.Children.Add(Ln("M-4.2,-3.6 L-3.6,3.6", S("#FFFFFF", 150), 1.1));
                if (Stage == 3) _cupGlass.Children.Add(Ln("M6.2,-3.2 Q11.6,-2.6 9.4,2.6 Q8.2,4.4 5.4,4", S("#FFD54F"), 1.5));
            }
            if (Stage == 1) _cupGlass.RenderTransform = new ScaleTransform(0.82, 0.82, 0, 3);
            _cup.Children.Add(_cupGlass);

            // paws (always present)
            double px = Stage == 0 ? 8.4 : 6.8, py = Stage == 0 ? 0 : 1.6;
            var pl = new Canvas { RenderTransform = _pawL };
            var pr = new Canvas { RenderTransform = _pawR };
            AddFur(pl, El(-px, py, 4.0, 3.5, FurN), FurN, FurP);
            AddFur(pr, El(px, py, 4.0, 3.5, FurN), FurN, FurP);
            pl.Children.Add(Ln($"M{(-px - 1).ToString(System.Globalization.CultureInfo.InvariantCulture)},{(py - 0.6).ToString(System.Globalization.CultureInfo.InvariantCulture)} l0,2.2", S("#4A2E20", 150), 0.8));
            pr.Children.Add(Ln($"M{(px + 1).ToString(System.Globalization.CultureInfo.InvariantCulture)},{(py - 0.6).ToString(System.Globalization.CultureInfo.InvariantCulture)} l0,2.2", S("#4A2E20", 150), 0.8));
            _cup.Children.Add(pl); _cup.Children.Add(pr);
            c.Children.Add(_cup);
        }

        // ---------------------------------------------------------------- head
        void BuildHead(Canvas rig, double[] f)
        {
            double hs = Compact ? 2.0 : f[0];
            double hx = Compact ? 50 : f[1], hy = Compact ? 52 : f[2];
            _hs = hs;
            double k = Compact ? 1.28 : 1.0;           // eye boldness
            double sw = Compact ? 1.9 : 1.2;           // mouth stroke

            _headG = new Canvas { Width = 0, Height = 0 };
            var tg = new TransformGroup();
            tg.Children.Add(_headTilt); tg.Children.Add(new ScaleTransform(hs, hs)); tg.Children.Add(new TranslateTransform(hx, hy)); tg.Children.Add(_headMove);
            _headG.RenderTransform = tg;
            var h = _headG;

            // ears
            foreach (int side in new[] { -1, 1 })
            {
                var ear = new Canvas { RenderTransform = side < 0 ? _earL : _earR };
                AddFur(ear, El(side * 15.8, -13.4, 5.0, 4.7, FurN), FurN, FurP);
                ear.Children.Add(El(side * 15.8, -13, 2.7, 2.5, S("#F0BFA8")));
                if (Stage == 3) ear.Children.Add(El(side * 15.8, -17.2, 2.4, 1.3, Silver));
                h.Children.Add(ear);
            }

            // head
            Geometry hg;
            if (Stage == 0) hg = Fluffy(0, 0, 21.6, 18.6, 16, 0.07);
            else { var e = new EllipseGeometry(new Point(0, 0), 21, 18); e.Freeze(); hg = e; }
            AddFur(h, Shp(hg, FurN), FurN, FurP);
            h.Children.Add(El(0, -11.4, 10.5, 4.2, S("#FFFFFF", 36)));
            if (Stage == 0 && Cosmetic != 1) h.Children.Add(Ln("M0,-17 Q-3.4,-23.5 2.4,-24", S("#9C6B4E"), 2.6));

            if (Stage == 3)
            {
                h.Children.Add(Fl("M-8.5,-12.6 Q0,-20 8.5,-12.6 Q0,-8.6 -8.5,-12.6Z", S("#E3E8EC", 195)));
                for (int i = 0; i < 6; i++)
                {
                    double a = (-150 + i * 24) * Math.PI / 180;
                    double px = 21 * Math.Cos(a), py = 18 * Math.Sin(a), nx = Math.Cos(a), ny = Math.Sin(a);
                    h.Children.Add(LQ(px - nx * 1.2, py - ny * 1.2, px + nx * 1.4 + 1.2, py + ny * 1.4, px + nx * 2.4 + 1.2, py + ny * 2.4 - 0.4, Silver, 1.25));
                }
            }

            // cream muzzle + cheek puffs
            h.Children.Add(El(0, 8, 13, 8.8, Cream));
            h.Children.Add(El(-7.2, 8.4, 7.6, 6.5, Cream));
            h.Children.Add(El(7.2, 8.4, 7.6, 6.5, Cream));
            h.Children.Add(El(0, 9.5, 11, 6, S("#FFFFFF", 40)));

            _blush = new Canvas();
            _blush.Children.Add(El(-12.8, 5.2, 3.6, 2.3, S("#FF8A80", 170)));
            _blush.Children.Add(El(12.8, 5.2, 3.6, 2.3, S("#FF8A80", 170)));
            h.Children.Add(_blush);

            // nose
            h.Children.Add(Fl("M-3.6,3.1 Q0,1.1 3.6,3.1 Q2.8,6.7 0,7.1 Q-2.8,6.7 -3.6,3.1Z", S("#34200F")));
            h.Children.Add(El(-1.2, 3.3, 1.2, 0.7, S("#FFFFFF", 130)));
            h.Children.Add(Ln("M0,7 L0,8.7", Dark, sw));

            // mouths
            _mIdle = new Canvas(); _mIdle.Children.Add(Ln("M-4.6,8.9 Q-2.3,11.9 0,8.7 Q2.3,11.9 4.6,8.9", Dark, sw));
            _mHappy = new Canvas();
            _mHappy.Children.Add(Fl("M-6.2,8.6 Q0,9.6 6.2,8.6 Q5.2,16.4 0,16.4 Q-5.2,16.4 -6.2,8.6Z", S("#4A1D1D")));
            _mHappy.Children.Add(Fl("M-3.4,15.7 Q0,11.7 3.4,15.7 Q0,16.9 -3.4,15.7Z", S("#F48FB1")));
            _mThirsty = new Canvas();
            _mThirsty.Children.Add(Ln("M-4.2,9.6 L4.2,9.6", Dark, sw));
            _mThirsty.Children.Add(Fl("M-1,9.8 L-1,13 Q1.3,15.6 3.6,13 L3.6,9.8Z", S("#F06292")));
            _mThirsty.Children.Add(Ln("M1.3,10.4 L1.3,12.6", S("#C2185B", 190), 0.7));
            _mSad = new Canvas(); _mSad.Children.Add(Ln("M-4.8,12.6 Q0,7.4 4.8,12.6", Dark, sw));
            _mSleep = new Canvas(); _mSleep.Children.Add(Ln("M-3.2,8.9 Q-1.6,11 0,8.7 Q1.6,11 3.2,8.9", Dark, sw));
            h.Children.Add(_mIdle); h.Children.Add(_mHappy); h.Children.Add(_mThirsty); h.Children.Add(_mSad); h.Children.Add(_mSleep);

            // eyes
            BuildEye(_eL, -8.6, k); BuildEye(_eR, 8.6, k);
            h.Children.Add(_eL.Root); h.Children.Add(_eR.Root);

            // sad brows (very thirsty)
            _sadBrows = new Canvas();
            _sadBrows.Children.Add(Ln("M-13,-5.4 L-5,-9.4", S("#F3DEC4"), 1.7));
            _sadBrows.Children.Add(Ln("M13,-5.4 L5,-9.4", S("#F3DEC4"), 1.7));
            if (!Compact) h.Children.Add(_sadBrows);

            // elder: bushy silver brows
            if (Stage == 3 && !Compact)
            {
                foreach (int s in new[] { -1, 1 })
                {
                    h.Children.Add(LQ(s * 12.6, -6.4, s * 9.6, -10.6, s * 5, -8, Silver, 2.1));
                    
                }
            }

            // sweat drop
            _sweat = new Canvas { RenderTransform = new TranslateTransform(17.8, -7.6) };
            _sweat.Children.Add(Shp(G("M0,-5 C-1.6,-2.2 -3.4,-0.4 -3.4,1.7 A3.4,3.4 0 0 0 3.4,1.7 C3.4,-0.4 1.6,-2.2 0,-5Z"), Grad("#B3E5FC", "#29B6F6"), S("#0277BD"), 0.7));
            _sweat.Children.Add(El(-1.1, 1.3, 0.9, 1.4, S("#FFFFFF", 200)));
            h.Children.Add(_sweat);

            // whiskers (thick so they survive scaling) - not in compact
            if (!Compact)
            {
                bool elder = Stage == 3;
                var wb = elder ? S("#FFFFFF", 235) : S("#FFF6E6", 230);
                double w = elder ? 1.35 : 1.15;
                foreach (int s in new[] { -1, 1 })
                {
                    h.Children.Add(LQ(s * 9.5, 7.8, s * 16, 4.4, s * (elder ? 24 : 20.5), 5.4, wb, w));
                    h.Children.Add(LQ(s * 10, 9.8, s * 17, 9.4, s * (elder ? 25 : 21.5), 10.4, wb, w));
                    h.Children.Add(LQ(s * 9.5, 11.6, s * 15.5, 14, s * (elder ? 23 : 19.5), 15.6, wb, w));
                    if (elder) h.Children.Add(LQ(s * 9, 13, s * 14, 17.2, s * 21, 20.2, wb, w));
                    foreach (var (dx, dy) in new[] { (5.8, 9.2), (7.2, 10.8), (5.6, 11.8) })
                        h.Children.Add(El(s * dx, dy, 0.55, 0.55, S("#8A6040")));
                }
            }

            // cosmetics
            if (Cosmetic == 1)
            {
                h.Children.Add(Fl("M-15.6,-7 C-17,-27 17,-27 15.6,-7 Q0,-10.2 -15.6,-7Z", Grad("#EF5350", "#B71C1C")));
                h.Children.Add(Ln("M-8,-19 Q-9.5,-14 -9,-9.2 M0,-22 L0,-9.6 M8,-19 Q9.5,-14 9,-9.2", S("#B71C1C", 140), 1.1));
                h.Children.Add(Fl("M-17.2,-9.2 Q0,-13.4 17.2,-9.2 L16.6,-3.4 Q0,-7.6 -16.6,-3.4Z", S("#FFF3E0")));
                h.Children.Add(Ln("M-14,-7.4 L-14,-5.2 M-8,-8.4 L-8,-6.2 M-2,-8.8 L-2,-6.6 M4,-8.8 L4,-6.6 M10,-8.4 L10,-6.2", S("#E8C9A0"), 1.1));
                h.Children.Add(El(0, -24.6, 4.4, 4.4, S("#FFF8EC")));
                h.Children.Add(El(-1, -25.6, 1.5, 1.2, S("#FFFFFF")));
            }
            else if (Cosmetic == 2)
            {
                var lens = Grad("#455A64", "#0E1418", 242, 242);
                h.Children.Add(Ln("M-14.4,-2.4 L-20,-5.4 M14.4,-2.4 L20,-5.4", S("#10161A"), 1.4));
                h.Children.Add(El(-8.6, -1.2, 6.5, 5.0, lens, S("#10161A"), 1.4));
                h.Children.Add(El(8.6, -1.2, 6.5, 5.0, lens, S("#10161A"), 1.4));
                h.Children.Add(Ln("M-2.2,-2.2 Q0,-3.8 2.2,-2.2", S("#10161A"), 1.5));
                h.Children.Add(Ln("M-11.6,-3.4 L-9.2,-4.4 M5.6,-3.4 L8,-4.4", S("#FFFFFF", 170), 1.2));
            }
            else if (Cosmetic == 3)
            {
                var bowC = Grad("#F48FB1", "#C2185B");
                var bow = new Canvas { RenderTransform = new TranslateTransform(0, 17.6) };
                bow.Children.Add(Shp(G("M0,0 L-9,-5.4 Q-10.4,0 -9,5.4Z"), bowC, S("#880E4F", 160), 0.6));
                bow.Children.Add(Shp(G("M0,0 L9,-5.4 Q10.4,0 9,5.4Z"), bowC, S("#880E4F", 160), 0.6));
                bow.Children.Add(El(0, 0, 2.7, 3.2, Grad("#F06292", "#AD1457"), S("#880E4F", 160), 0.6));
                h.Children.Add(bow);
            }

            rig.Children.Add(h);
        }

        void BuildEye(EyeParts e, double x, double k)
        {
            e.Open.Children.Add(El(0, 0, 3.5 * k, 4.2 * k, EyeFill));
            e.Open.Children.Add(El(1.25 * k, -1.6 * k, 1.35 * k, 1.35 * k, White));
            e.Open.Children.Add(El(-1.3 * k, 1.7 * k, 0.68 * k, 0.68 * k, S("#FFFFFF", 190)));
            e.Happy = Shp(Q(-3.9 * k, 2 * k, 0, -4.4 * k, 3.9 * k, 2 * k), null, Dark, 1.9 * (Compact ? 1.15 : 1));
            e.Sleep = Shp(Q(-3.9 * k, -0.8 * k, 0, 3.6 * k, 3.9 * k, -0.8 * k), null, Dark, 1.7 * (Compact ? 1.15 : 1));
            e.Root.Children.Add(e.Open); e.Root.Children.Add(e.Happy); e.Root.Children.Add(e.Sleep);
            var tg = new TransformGroup();
            tg.Children.Add(e.Blink); tg.Children.Add(e.Look); tg.Children.Add(new TranslateTransform(x, -1.2));
            e.Root.RenderTransform = tg;
        }

        // =====================================================================================
        //  MOOD / WATER / LOOK / BLINK
        // =====================================================================================
        public override void SetMood(PetMood m)
        {
            Mood = m; SetSleepFx(m == PetMood.Sleeping);
            bool sl = m == PetMood.Sleeping, hp = m == PetMood.Happy, th = m == PetMood.Thirsty, vt = m == PetMood.VeryThirsty;
            bool open = !(sl || hp);
            foreach (var e in new[] { _eL, _eR })
            {
                e.Open.Visibility = V(open); e.Happy.Visibility = V(hp); e.Sleep.Visibility = V(sl);
                if (sl) { e.Look.X = 0; e.Look.Y = 0; }
            }
            _sadBrows.Visibility = V(vt);
            _mIdle.Visibility = V(m == PetMood.Idle); _mHappy.Visibility = V(hp); _mThirsty.Visibility = V(th);
            _mSad.Visibility = V(vt); _mSleep.Visibility = V(sl);
            _blush.Opacity = hp ? 1 : sl ? 0.6 : m == PetMood.Idle ? 0.45 : 0;
            _sweat.Visibility = V(th || vt);

            double ear = vt ? -46 : th ? -14 : sl ? -7 : hp ? 5 : 0;
            _earL.Angle = ear; _earR.Angle = -ear;
            _headTilt.Angle = sl ? 7 : vt ? -4 : 0;
            _headMove.Y = vt ? 2.2 : sl ? 1.4 : 0;
            foreach (var f in _fur)
            {
                var b = vt ? f.Pa : f.N;
                if (f.Stroke) f.P.Stroke = b; else f.P.Fill = b;
            }
            if (!Compact)
            {
                _cupGlass.Visibility = V(!sl);
                _cupWater.Visibility = V(m == PetMood.Idle || hp);
                double p = sl ? (Stage == 0 ? 5.6 : 4.4) : 0;
                _pawL.X = p; _pawR.X = -p;
            }
            if (Idling) StartIdle();
        }

        public override void SetWater(double fraction, bool animate)
        {
            double f = Math.Clamp(fraction, 0, 1);
            Water = f;
            if (Compact) return;
            double surf = 100 - f * 36;
            Tween(_lvlBack, surf, animate);
            Tween(_lvlFront, surf + 1.5, animate);
            Tween(_rise, -f * 7, animate);
            _cracks.Opacity = Math.Clamp(1 - f * 5, 0, 1);
        }

        static void Tween(TranslateTransform t, double to, bool animate)
        {
            double from = t.Y;
            t.BeginAnimation(TranslateTransform.YProperty, null);
            t.Y = to;
            if (animate && Math.Abs(from - to) > 0.05)
            {
                var a = new DoubleAnimation(from, to, TimeSpan.FromMilliseconds(700)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut }, FillBehavior = FillBehavior.Stop };
                TimelineRate(a);
                t.BeginAnimation(TranslateTransform.YProperty, a);
            }
        }

        public override void LookAt(double dx, double dy)
        {
            if (Mood == PetMood.Sleeping) return;
            double r = 2.5 / _hs;
            double x = Math.Clamp(dx, -1, 1) * r, y = Math.Clamp(dy, -1, 1) * r;
            _eL.Look.X = x; _eL.Look.Y = y; _eR.Look.X = x; _eR.Look.Y = y;
        }

        public override void Blink()
        {
            if (Mood == PetMood.Sleeping || Mood == PetMood.Happy) return;
            foreach (var e in new[] { _eL, _eR })
            {
                var a = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
                a.KeyFrames.Add(new LinearDoubleKeyFrame(0.1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(70))));
                a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(160))));
                e.Blink.BeginAnimation(ScaleTransform.ScaleYProperty, a);
            }
        }

        // =====================================================================================
        //  ANIMATION
        // =====================================================================================
        public override void StartIdle()
        {
            base.StartIdle();
            if (Compact) return;
            bool sl = Mood == PetMood.Sleeping;
            var rock = new DoubleAnimation(-(sl ? 2.5 : 4), sl ? 2.5 : 4, TimeSpan.FromMilliseconds(sl ? 3400 : 2600))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
            TimelineRate(rock);
            _rock.BeginAnimation(RotateTransform.AngleProperty, rock);

            var wb = new DoubleAnimation(0, -WavePeriod, TimeSpan.FromMilliseconds(sl ? 6200 : 4200)) { RepeatBehavior = RepeatBehavior.Forever };
            var wf = new DoubleAnimation(-12, -12 - WavePeriod, TimeSpan.FromMilliseconds(sl ? 4800 : 3100)) { RepeatBehavior = RepeatBehavior.Forever };
            TimelineRate(wb); TimelineRate(wf);
            _waveBack.BeginAnimation(TranslateTransform.XProperty, wb);
            _waveFront.BeginAnimation(TranslateTransform.XProperty, wf);
        }

        public override void StopIdle()
        {
            base.StopIdle();
            if (Compact) return;
            _rock.BeginAnimation(RotateTransform.AngleProperty, null);
            _waveBack.BeginAnimation(TranslateTransform.XProperty, null);
            _waveFront.BeginAnimation(TranslateTransform.XProperty, null);
        }

        static DoubleAnimationUsingKeyFrames Kf(params (double v, int ms)[] k)
        {
            var a = new DoubleAnimationUsingKeyFrames { FillBehavior = FillBehavior.Stop };
            foreach (var (v, ms) in k)
                a.KeyFrames.Add(new EasingDoubleKeyFrame(v, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new SineEase { EasingMode = EasingMode.EaseInOut }));
            TimelineRate(a);
            return a;
        }

        public override void PlayDrink()
        {
            base.PlayDrink();
            double t0 = _headTilt.Angle;
            _headTilt.BeginAnimation(RotateTransform.AngleProperty, Kf((t0, 0), (t0 - 9, 320), (t0 - 9, 860), (t0, 1250)));
            if (!Compact && Stage > 0 && Mood != PetMood.Sleeping)
            {
                _cupMove.BeginAnimation(TranslateTransform.YProperty, Kf((0, 0), (-10, 320), (-10, 860), (0, 1250)));
                _cupRot.BeginAnimation(RotateTransform.AngleProperty, Kf((0, 0), (-22, 320), (-22, 860), (0, 1250)));
            }
            if (Mood != PetMood.Happy && Mood != PetMood.Sleeping)
                foreach (var e in new[] { _eL, _eR })
                    e.Blink.BeginAnimation(ScaleTransform.ScaleYProperty, Kf((1, 0), (0.12, 260), (0.12, 860), (1, 1150)));
        }
    }
}
