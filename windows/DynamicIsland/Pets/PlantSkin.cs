using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace DynamicIsland.Pets
{
    /// <summary>
    /// Potted plant with a kawaii face on the pot. The face carries the moods, the leaves droop / perk.
    /// Scene = pot + face + soil (moisture). Body = the plant only (rooted at the soil line, y~62).
    /// Pot and plant are always moved / squashed together (idle bob, hop, squash) so the plant never detaches.
    /// </summary>
    public sealed class PlantSkin : PetSkin
    {
        public override PetKind Kind => PetKind.Plant;
        public override string Name => "Plant";
        public override string[] StageNames => new[] { "Sprout", "Sapling", "Bush", "Bloom" };
        public override string[] CosmeticNames => new[] { "None", "Blue glaze pot", "Ribbon", "Golden pot" };

        // ---------- shared frozen brushes ----------
        static readonly Brush Ink = B(0x3A, 0x1F, 0x18);
        static readonly Brush EyeFill = VGrad(0x4D2E24, 0x1B0E0A);
        static readonly Brush White = B(255, 255, 255);
        static readonly Brush HiWhite = B(255, 255, 255, 230);
        static readonly Brush BlushBrush = B(0xFF, 0x6E, 0x93);
        static readonly Brush DropFill = VGrad(0xB4EAFF, 0x35A4EC);
        static readonly Brush VeinBrush = B(255, 255, 255, 105);
        static readonly Brush Shade = B(0, 0, 0, 40);
        static readonly Brush MouthIn = B(0x56, 0x22, 0x1C);
        static readonly Brush Tongue = B(0xFF, 0x8F, 0xA6);
        static readonly Brush SoilSpeck = B(0, 0, 0, 38);
        static readonly Brush Glint = B(255, 255, 255, 75);
        static readonly Brush CrackBrush = B(0x8A, 0x66, 0x3C);

        // ---------- small helpers ----------
        static Color Col(int rgb, byte a = 255) => Color.FromArgb(a, (byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb);
        static Color Mix(Color a, Color b, double t) => Color.FromArgb(255,
            (byte)Math.Round(a.R + (b.R - a.R) * t), (byte)Math.Round(a.G + (b.G - a.G) * t), (byte)Math.Round(a.B + (b.B - a.B) * t));
        static string N(double v) => v.ToString("0.###", CultureInfo.InvariantCulture);
        static LinearGradientBrush VGrad(int top, int bot) { var g = new LinearGradientBrush(Col(top), Col(bot), 90); g.Freeze(); return g; }
        static LinearGradientBrush AGrad(int c0, int c1, double angle) { var g = new LinearGradientBrush(Col(c0), Col(c1), angle); g.Freeze(); return g; }
        static string LeafPath(double L, double w) =>
            $"M0,0 C{N(-w)},{N(-L * .18)} {N(-w * 1.05)},{N(-L * .72)} 0,{N(-L)} C{N(w * 1.05)},{N(-L * .72)} {N(w)},{N(-L * .18)} 0,0 Z";

        static void Fade(UIElement? e, double to, bool anim, int ms = 450)
        {
            if (e == null) return;
            if (anim) e.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)));
            else { e.BeginAnimation(UIElement.OpacityProperty, null); e.Opacity = to; }
        }
        static void Hue(GradientStop s, Color to, bool anim, int ms = 450)
        {
            if (anim) s.BeginAnimation(GradientStop.ColorProperty, new ColorAnimation(to, TimeSpan.FromMilliseconds(ms)));
            else { s.BeginAnimation(GradientStop.ColorProperty, null); s.Color = to; }
        }
        static void Hue(SolidColorBrush b, Color to, bool anim, int ms = 450)
        {
            if (anim) b.BeginAnimation(SolidColorBrush.ColorProperty, new ColorAnimation(to, TimeSpan.FromMilliseconds(ms)));
            else { b.BeginAnimation(SolidColorBrush.ColorProperty, null); b.Color = to; }
        }
        static void Ang(RotateTransform r, double to, bool anim, int ms = 380)
        {
            if (anim) r.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(to, TimeSpan.FromMilliseconds(ms)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            else { r.BeginAnimation(RotateTransform.AngleProperty, null); r.Angle = to; }
        }
        static void Vis(IEnumerable<UIElement> l, bool on) { foreach (var e in l) e.Visibility = on ? Visibility.Visible : Visibility.Collapsed; }
        static void Vis(UIElement? e, bool on) { if (e != null) e.Visibility = on ? Visibility.Visible : Visibility.Collapsed; }

        // ---------- state ----------
        sealed class LeafInfo
        {
            public readonly RotateTransform Pose = new(), Sway = new();
            public double Ang; public int Dir = 1; public bool Flower;
            public GradientStop? S0, S1; public Color C0, C1;
            public ScaleTransform? FScale;
        }

        readonly List<LeafInfo> _leaves = new();
        readonly List<UIElement> _drops = new();
        readonly List<UIElement> _openEyes = new(), _happyEyes = new(), _sleepEyes = new(), _brows = new(), _tears = new(), _blush = new();
        UIElement? _mIdle, _mHappy, _mFlat, _mSad, _mSleep, _sweat, _cracks, _sheen;

        Canvas? _plant, _sprinkle;
        ScaleTransform? _grow, _sceneScale, _blinkT, _eyeMood;
        RotateTransform? _sway, _kick, _lean;
        TranslateTransform? _sceneMove, _look;
        SolidColorBrush? _stemBrush;
        LinearGradientBrush? _soilBrush;
        GradientStop? _soil0, _soil1;
        bool _snapMood;

        // =====================================================================================
        //  SCENE : pot, soil, face
        // =====================================================================================
        protected override void BuildScene(Canvas scene)
        {
            _snapMood = true;
            _sceneMove = new TranslateTransform();
            _sceneScale = new ScaleTransform(1, 1, 50, 92);
            var tg = new TransformGroup();
            tg.Children.Add(_sceneScale); tg.Children.Add(_sceneMove);
            scene.RenderTransform = tg;

            _soil0 = new GradientStop(Col(0xE9D3A6), 0);
            _soil1 = new GradientStop(Col(0xCDAE7E), 1);
            _soilBrush = new LinearGradientBrush { StartPoint = new Point(0.5, 0), EndPoint = new Point(0.5, 1) };
            _soilBrush.GradientStops.Add(_soil0); _soilBrush.GradientStops.Add(_soil1);

            _openEyes.Clear(); _happyEyes.Clear(); _sleepEyes.Clear(); _brows.Clear(); _tears.Clear(); _blush.Clear();
            _cracks = _sheen = null;

            if (Compact) BuildPotCompact(scene); else BuildPot(scene);
        }

        (int bt, int bb, int rt, int rb, int lt) Pal() => Cosmetic switch
        {
            1 => (0x72C0F8, 0x2B69BF, 0x8FD0FF, 0x4A8FE0, 0xC4E8FF),
            3 => (0xFFDA6A, 0xC48808, 0xFFE27A, 0xE8A91C, 0xFFF8D2),
            _ => (0xE0794F, 0xB5532E, 0xEE8A60, 0xD0653A, 0xF8A57F),
        };

        void BuildPot(Canvas c)
        {
            var p = Pal();
            // body
            c.Children.Add(new Path { Data = G("M23,70 L77,70 L72,94 Q71.4,98.5 66.5,98.5 L33.5,98.5 Q28.6,98.5 28,94 Z"), Fill = VGrad(p.bt, p.bb) });
            c.Children.Add(new Path { Data = G("M29.2,76 Q28.4,85 31.4,93"), Stroke = Cosmetic == 3 ? B(255, 255, 255, 120) : Glint, StrokeThickness = 3.2, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
            c.Children.Add(new Path { Data = G("M71.4,77 Q72.4,86 70,94"), Stroke = Shade, StrokeThickness = 3.4, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
            // shadow under the rim
            c.Children.Add(new Path { Data = G("M23.4,70.5 L76.6,70.5 L75.8,75.4 Q50,78.4 24.2,75.4 Z"), Fill = B(0, 0, 0, 44) });
            if (Cosmetic == 2)
            {
                c.Children.Add(new Path { Data = G("M24,71.2 L76,71.2 L75.3,75.2 L24.7,75.2 Z"), Fill = VGrad(0xFF86AA, 0xE0467A) });
                c.Children.Add(new Path { Data = G("M24.4,72.3 L75.6,72.3"), Stroke = B(255, 255, 255, 110), StrokeThickness = 0.9 });
            }
            // rim band + lip
            c.Children.Add(At(new Rectangle { Width = 64, Height = 9.6, RadiusX = 3.8, RadiusY = 3.8, Fill = VGrad(p.rt, p.rb) }, 18, 62));
            c.Children.Add(At(new Ellipse { Width = 64, Height = 12.4, Fill = VGrad(p.lt, p.rt) }, 18, 55.8));
            // soil
            c.Children.Add(At(new Ellipse { Width = 55, Height = 8.6, Fill = _soilBrush }, 22.5, 57.7));
            c.Children.Add(new Path { Data = G("M23.4,61 A26.6,4.1 0 0 1 76.6,61"), Stroke = B(0, 0, 0, 55), StrokeThickness = 1.3 });
            var specks = new Canvas();
            foreach (var (x, y, w) in new[] { (33.0, 62.6, 2.6), (44.0, 60.0, 2.0), (57.0, 64.0, 2.6), (65.0, 60.8, 2.0), (70.5, 63.4, 2.4), (39.5, 65.0, 2.0) })
                specks.Children.Add(At(new Ellipse { Width = w, Height = w * .5, Fill = SoilSpeck }, x - w / 2, y));
            c.Children.Add(specks);
            var cr = new Path
            {
                Data = G("M31.5,62.3 L36,61 L38.6,63.4 M40,64.8 L43.5,63.8 M56,59.5 L59,61.2 L63.5,60.6 M66,63.8 L70,62.4 M47.5,65.6 L50.5,64.8"),
                Stroke = CrackBrush, StrokeThickness = 0.95, StrokeLineJoin = PenLineJoin.Round, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Opacity = 0
            };
            _cracks = cr; c.Children.Add(cr);
            var sh = new Canvas { Opacity = 0 };
            sh.Children.Add(At(new Ellipse { Width = 17, Height = 1.9, Fill = Glint }, 31, 58.9));
            sh.Children.Add(At(new Ellipse { Width = 6, Height = 1.5, Fill = Glint }, 60, 60.2));
            _sheen = sh; c.Children.Add(sh);

            // cosmetics
            if (Cosmetic == 1)
            {
                foreach (var (x, len) in new[] { (25.0, 5.2), (35.2, 3.0), (64.8, 3.6), (73.5, 5.6) })
                    c.Children.Add(new Path { Data = G($"M{N(x - 1.7)},71 L{N(x - 1.7)},{N(71 + len)} A1.7,1.7 0 0 0 {N(x + 1.7)},{N(71 + len)} L{N(x + 1.7)},71 Z"), Fill = B(0xD6, 0xEF, 0xFF, 215) });
                c.Children.Add(At(new Ellipse { Width = 3.4, Height = 3.4, Fill = B(255, 255, 255, 140) }, 60, 56.6));
            }
            else if (Cosmetic == 3)
            {
                c.Children.Add(At(new Path { Data = G("M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"), Fill = White, RenderTransform = new ScaleTransform(3.6, 3.6) }, 28.5, 66));
                c.Children.Add(At(new Path { Data = G("M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"), Fill = B(255, 255, 255, 230), RenderTransform = new ScaleTransform(2.2, 2.2) }, 71.5, 77));
            }

            BuildFace(c, 11, 80.5, 89, 17, 87.4, 1.0, 1.0, 1.9, 1.9, 71.5, 75.5, 1.1);

            if (Cosmetic == 2) c.Children.Add(Bow(27, 72, 0.82));
        }

        void BuildPotCompact(Canvas c)
        {
            var p = Pal();
            c.Children.Add(new Path { Data = G("M17,64 L83,64 L77.5,93 Q76.8,98.5 70.5,98.5 L29.5,98.5 Q23.2,98.5 22.5,93 Z"), Fill = VGrad(p.bt, p.bb) });
            c.Children.Add(new Path { Data = G("M24,69 Q23.6,80 26.6,91"), Stroke = Cosmetic == 3 ? B(255, 255, 255, 130) : Glint, StrokeThickness = 5, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
            if (Cosmetic == 2)
                c.Children.Add(new Path { Data = G("M17.5,64 L82.5,64 L81.7,70.5 L18.3,70.5 Z"), Fill = VGrad(0xFF86AA, 0xE0467A) });
            c.Children.Add(At(new Rectangle { Width = 76, Height = 11, RadiusX = 5, RadiusY = 5, Fill = VGrad(p.rt, p.rb) }, 12, 54));
            c.Children.Add(At(new Ellipse { Width = 76, Height = 15, Fill = VGrad(p.lt, p.rt) }, 12, 46.5));
            c.Children.Add(At(new Ellipse { Width = 64, Height = 10.5, Fill = _soilBrush }, 18, 48.8));
            if (Cosmetic == 3)
                c.Children.Add(At(new Path { Data = G("M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"), Fill = White, RenderTransform = new ScaleTransform(6, 6) }, 76, 58));

            BuildFace(c, 13, 77, 87.5, 22, 86.5, 1.65, 1.35, 3.4, 4.2, 80, 70, 1.8);

            if (Cosmetic == 2) c.Children.Add(Bow(24, 66, 1.35));
        }

        Canvas Bow(double x, double y, double s)
        {
            var b = new Canvas { RenderTransform = new ScaleTransform(s, s) };
            var fill = VGrad(0xFF8EB0, 0xE0467A);
            b.Children.Add(new Path { Data = G("M-0.8,3 L-6.4,11.6 L-2.8,10.4 L-0.4,13 Z"), Fill = VGrad(0xF0507F, 0xC93468) });
            b.Children.Add(new Path { Data = G("M0.8,3 L6.4,11.6 L2.8,10.4 L0.4,13 Z"), Fill = VGrad(0xF0507F, 0xC93468) });
            b.Children.Add(new Path { Data = G("M0,0 C-5,-7.5 -13,-5.5 -12,0 C-13,5.5 -5,7.5 0,0 Z"), Fill = fill });
            b.Children.Add(new Path { Data = G("M0,0 C5,-7.5 13,-5.5 12,0 C13,5.5 5,7.5 0,0 Z"), Fill = fill });
            b.Children.Add(At(new Ellipse { Width = 5.6, Height = 6, Fill = VGrad(0xFF6C96, 0xD23A6C) }, -2.8, -3));
            b.Children.Add(At(new Ellipse { Width = 2, Height = 1.4, Fill = HiWhite }, -1.7, -2.3));
            var host = new Canvas();
            host.Children.Add(b);
            return At(host, x, y);
        }

        // ---------- face ----------
        Canvas MakeDrop(double s)
        {
            var d = new Canvas { RenderTransform = new ScaleTransform(s, s) };
            d.Children.Add(new Path { Data = G("M0,-4.4 C-0.6,-2.4 -3.1,-0.9 -3.1,1.5 A3.1,3.1 0 0 0 3.1,1.5 C3.1,-0.9 0.6,-2.4 0,-4.4 Z"), Fill = DropFill });
            d.Children.Add(At(new Ellipse { Width = 1.3, Height = 1.9, Fill = HiWhite }, -1.9, -0.2));
            return d;
        }

        void BuildFace(Canvas c, double ex, double ey, double my, double bx, double by, double ke, double km, double swe, double swm, double sxp, double syp, double ss)
        {
            _look = new TranslateTransform(); _blinkT = new ScaleTransform(1, 1); _eyeMood = new ScaleTransform(1, 1);
            double bk = Compact ? 1.5 : 1.0;

            foreach (int side in new[] { -1, 1 })
            {
                var bl = new Ellipse { Width = 9.4 * bk, Height = 5.4 * bk, Fill = BlushBrush, Opacity = 0.5 };
                c.Children.Add(At(bl, 50 + side * bx - 4.7 * bk, by - 2.7 * bk));
                _blush.Add(bl);
            }

            foreach (int side in new[] { -1, 1 })
            {
                var box = new Canvas();
                var open = new Canvas();
                open.Children.Add(At(new Ellipse { Width = 7.6, Height = 9.4, Fill = EyeFill }, -3.8, -4.7));
                open.Children.Add(At(new Ellipse { Width = 3.1, Height = 3.1, Fill = White }, -2.9, -3.8));
                open.Children.Add(At(new Ellipse { Width = 1.5, Height = 1.5, Fill = HiWhite }, 0.9, 1.4));
                var tg = new TransformGroup();
                tg.Children.Add(_eyeMood); tg.Children.Add(_blinkT); tg.Children.Add(new ScaleTransform(ke, ke)); tg.Children.Add(_look);
                open.RenderTransform = tg;
                box.Children.Add(open); _openEyes.Add(open);

                var happy = ArcPath(side, "M-4.2,1.8 Q0,-4.4 4.2,1.8", swe, ke); box.Children.Add(happy); _happyEyes.Add(happy);
                var sleep = ArcPath(side, "M-4.4,-0.8 Q0,3.8 4.4,-0.8", swe, ke); box.Children.Add(sleep); _sleepEyes.Add(sleep);
                var brow = ArcPath(side, side < 0 ? "M-4.6,-5.8 L3.4,-8.2" : "M-3.4,-8.2 L4.6,-5.8", swe, ke); box.Children.Add(brow); _brows.Add(brow);
                var tear = MakeDrop(0.95 * ke); At(tear, side * 5.6 * ke, 6.6 * ke); tear.Visibility = Visibility.Collapsed; box.Children.Add(tear); _tears.Add(tear);
                c.Children.Add(At(box, 50 + side * ex, ey));
            }

            var mouth = new Canvas { RenderTransform = new ScaleTransform(km, km) };
            _mIdle = StrokeP("M-3.8,0 Q0,3.8 3.8,0", swm);
            _mFlat = StrokeP("M-3.2,0.8 L3.2,0.8", swm);
            _mSad = StrokeP("M-4.2,2.8 Q0,-2.4 4.2,2.8", swm);
            var hm = new Canvas();
            hm.Children.Add(new Path { Data = G("M-5.2,-1.2 Q0,-2 5.2,-1.2 Q4.8,6.8 0,6.8 Q-4.8,6.8 -5.2,-1.2 Z"), Fill = MouthIn });
            hm.Children.Add(At(new Ellipse { Width = 5.4, Height = 2.8, Fill = Tongue }, -2.7, 3.1));
            _mHappy = hm;
            _mSleep = At(new Ellipse { Width = 3.6, Height = 4.2, Fill = MouthIn }, -1.8, -0.6);
            foreach (var m in new[] { _mIdle, _mFlat, _mSad, _mHappy, _mSleep }) { m.Visibility = Visibility.Collapsed; mouth.Children.Add(m); }
            c.Children.Add(At(mouth, 50, my));

            var sw = MakeDrop(ss); At(sw, sxp, syp); sw.Visibility = Visibility.Collapsed; _sweat = sw; c.Children.Add(sw);
        }

        static Path ArcPath(int side, string d, double sw, double k) => new Path
        {
            Data = G(d), Stroke = Ink, StrokeThickness = sw, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
            RenderTransform = new ScaleTransform(k, k), Visibility = Visibility.Collapsed
        };
        static Path StrokeP(string d, double sw) => new Path { Data = G(d), Stroke = Ink, StrokeThickness = sw, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };

        // =====================================================================================
        //  BODY : the plant
        // =====================================================================================
        protected override void BuildBody(Canvas body)
        {
            _leaves.Clear(); _drops.Clear();
            double S = Compact ? 54 : 62;
            _stemBrush = new SolidColorBrush(Col(0x3BAA62));
            _grow = new ScaleTransform(1, 1, 50, S);
            _sway = new RotateTransform(0, 50, S);
            _kick = new RotateTransform(0, 50, S);
            _lean = new RotateTransform(0, 50, S);
            _plant = new Canvas { Width = 100, Height = 100 };
            var tg = new TransformGroup();
            tg.Children.Add(_grow); tg.Children.Add(_sway); tg.Children.Add(_kick);
            _plant.RenderTransform = tg;
            var stem = new Canvas { Width = 100, Height = 100, RenderTransform = _lean };
            _plant.Children.Add(stem);

            if (Compact) BuildPlantCompact(stem); else BuildPlant(stem);
            body.Children.Add(_plant);

            double rx = Compact ? (Stage >= 2 ? 11 : 8) : (Stage >= 2 ? 10 : 6.5);
            double ry = Compact ? 3.4 : 2.5;
            body.Children.Add(At(new Ellipse { Width = rx * 2, Height = ry * 2, Fill = _soilBrush }, 50 - rx, S + 0.5 - ry));

            if (!Compact)
            {
                _sprinkle = new Canvas();
                Fx.Children.Insert(0, _sprinkle);
                for (int i = 0; i < 7; i++)
                {
                    var d = MakeDrop(1.05);
                    d.Opacity = 0; d.RenderTransform = new TransformGroup { Children = { new ScaleTransform(1.05, 1.05), new TranslateTransform() } };
                    _sprinkle.Children.Add(At(d, 28 + i * 7.4, 4));
                }
            }
            else _sprinkle = null;
        }

        Path StemPath(string d, double w) => new Path { Data = G(d), Stroke = _stemBrush, StrokeThickness = w, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };

        Canvas NewHost(LeafInfo li, double x, double y, double ang)
        {
            var host = new Canvas();
            li.Pose.Angle = ang;
            var tg = new TransformGroup();
            tg.Children.Add(li.Pose); tg.Children.Add(li.Sway);
            host.RenderTransform = tg;
            Canvas.SetLeft(host, x); Canvas.SetTop(host, y);
            return host;
        }

        void AddLeaf(Canvas parent, double x, double y, double ang, double L, double w, double dark, bool drop)
        {
            var li = new LeafInfo { Ang = ang, Dir = ang < 0 ? -1 : 1 };
            li.C0 = Mix(Col(0x2E9E5B), Col(0x1A6A40), dark);
            li.C1 = Mix(Col(0x7BD389), Col(0x45AD6B), dark);
            li.S0 = new GradientStop(li.C0, 0); li.S1 = new GradientStop(li.C1, 1);
            var br = new LinearGradientBrush { StartPoint = new Point(0.35, 1), EndPoint = new Point(0.65, 0) };
            br.GradientStops.Add(li.S0); br.GradientStops.Add(li.S1);
            var host = NewHost(li, x, y, ang);
            host.Children.Add(new Path { Data = G(LeafPath(L, w)), Fill = br });
            if (!Compact)
                host.Children.Add(new Path { Data = G($"M0,{N(-L * .1)} Q{N(w * .1)},{N(-L * .5)} 0,{N(-L * .84)}"), Stroke = VeinBrush, StrokeThickness = 0.9, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round });
            if (drop && !Compact)
            {
                var d = MakeDrop(0.8); d.Opacity = 0;
                host.Children.Add(At(d, -li.Dir * w * 0.2, -L * 0.56));
                _drops.Add(d);
            }
            parent.Children.Add(host);
            _leaves.Add(li);
        }

        void AddFlower(Canvas parent, double x, double y, double ang, double L, int hi, int lo, double r)
        {
            var li = new LeafInfo { Ang = ang, Dir = ang < 0 ? -1 : 1, Flower = true };
            var host = NewHost(li, x, y, ang);
            host.Children.Add(StemPath($"M0,0 Q{N(L * .07)},{N(-L * .5)} 0,{N(-L)}", Compact ? 4.6 : 2.3));
            var fg = new Canvas();
            li.FScale = new ScaleTransform(1, 1);
            fg.RenderTransform = li.FScale;
            var petal = AGrad(lo, hi, 270);
            double pw = 0.78 * r, ph = 1.06 * r, d = 0.6 * r;
            for (int k = 0; k < 5; k++)
                fg.Children.Add(At(new Ellipse { Width = pw, Height = ph, Fill = petal, RenderTransform = new RotateTransform(k * 72, pw / 2, d + ph / 2) }, -pw / 2, -d - ph / 2));
            double cd = 0.62 * r;
            fg.Children.Add(At(new Ellipse { Width = cd, Height = cd, Fill = VGrad(0xFFE88E, 0xF3A100) }, -cd / 2, -cd / 2));
            if (!Compact) fg.Children.Add(At(new Ellipse { Width = cd * .32, Height = cd * .26, Fill = HiWhite }, -cd * .3, -cd * .32));
            Canvas.SetLeft(fg, 0); Canvas.SetTop(fg, -L);
            host.Children.Add(fg);
            parent.Children.Add(host);
            _leaves.Add(li);
        }

        void AddBud(Canvas parent, double x, double y, double ang, double L)
        {
            var li = new LeafInfo { Ang = ang, Dir = ang < 0 ? -1 : 1, Flower = true };
            var host = NewHost(li, x, y, ang);
            host.Children.Add(StemPath($"M0,0 Q{N(L * .05)},{N(-L * .5)} 0,{N(-L + 1)}", 2.2));
            var g = new Canvas();
            li.FScale = new ScaleTransform(1, 1);
            g.RenderTransform = li.FScale;
            g.Children.Add(new Path { Data = G("M0,-10 C3.6,-5.6 3.8,-1.2 0,0.8 C-3.8,-1.2 -3.6,-5.6 0,-10 Z"), Fill = VGrad(0xFFC0D2, 0xFF7FA6) });
            g.Children.Add(new Path { Data = G("M-4.2,-3.4 Q0,3.4 4.2,-3.4 Q0,-1.4 -4.2,-3.4 Z"), Fill = VGrad(0x6DCB87, 0x2E9E5B) });
            Canvas.SetLeft(g, 0); Canvas.SetTop(g, -L);
            host.Children.Add(g);
            parent.Children.Add(host);
            _leaves.Add(li);
        }

        void BuildPlant(Canvas stem)
        {
            switch (Stage)
            {
                case 0: // Sprout: short stem + two round seed leaves
                    stem.Children.Add(StemPath("M50,62 L50,47", 3.8));
                    AddLeaf(stem, 50, 48.5, -62, 22, 12.5, 0.0, true);
                    AddLeaf(stem, 50, 48.5, 62, 22, 12.5, 0.0, true);
                    break;
                case 1: // Sapling: stem + 4 leaves
                    stem.Children.Add(StemPath("M50,62 C47.5,54 52.5,44 50,33.5", 3.6));
                    AddLeaf(stem, 49.4, 51, -66, 21, 11, 0.12, true);
                    AddLeaf(stem, 49.4, 51, 66, 21, 11, 0.12, false);
                    AddLeaf(stem, 50.6, 39, -38, 20, 10.5, 0.0, false);
                    AddLeaf(stem, 50.6, 39, 38, 20, 10.5, 0.0, true);
                    stem.Children.Add(At(new Ellipse { Width = 5.4, Height = 5.4, Fill = _stemBrush }, 47.3, 31.4));
                    break;
                case 2: // Bush: lush layered leaves
                    BushLeaves(stem, 1.0);
                    break;
                default: // Bloom: bush + 3 flowers + a bud
                    BushLeaves(stem, 0.74);
                    AddFlower(stem, 50, 62, -32, 38, 0xFFD3E0, 0xFF6F9B, 8.6);
                    AddFlower(stem, 50, 62, 30, 36, 0xFFF3B0, 0xFFC247, 8.2);
                    AddFlower(stem, 50, 62, 3, 47, 0xFFC4D6, 0xFF7FA6, 8.8);
                    AddBud(stem, 50, 62, 60, 27);
                    AddLeaf(stem, 46.5, 62, -42, 20, 9, 0.0, true);
                    AddLeaf(stem, 53.5, 62, 44, 20, 9, 0.0, true);
                    break;
            }
        }

        void BushLeaves(Canvas s, double k)
        {
            double w = 1.28;
            // back row (darker)
            AddLeaf(s, 46, 62, -54, 25 * k, 10 * k * w, .5, false);
            AddLeaf(s, 54, 62, 54, 25 * k, 10 * k * w, .5, false);
            AddLeaf(s, 48, 62, -24, 31 * k, 11 * k * w, .42, false);
            AddLeaf(s, 52, 62, 24, 31 * k, 11 * k * w, .42, true);
            if (k > 0.9) AddLeaf(s, 50, 62, 2, 34, 11 * w, .46, false);
            // front row
            if (k > 0.9)
            {
                AddLeaf(s, 44.5, 62, -76, 22, 9.5 * w, 0.02, true);
                AddLeaf(s, 55.5, 62, 76, 22, 9.5 * w, 0.02, false);
                AddLeaf(s, 46, 62, -46, 27, 10.5 * w, 0.04, false);
                AddLeaf(s, 54, 62, 46, 27, 10.5 * w, 0.04, true);
                AddLeaf(s, 49, 62, -14, 28, 10.5 * w, 0.0, true);
                AddLeaf(s, 51, 62, 16, 23, 9.5 * w, 0.08, false);
            }
        }

        void BuildPlantCompact(Canvas stem)
        {
            const double S = 54;
            switch (Stage)
            {
                case 0:
                    stem.Children.Add(StemPath($"M50,{N(S)} L50,43", 6));
                    AddLeaf(stem, 50, 45, -58, 25, 15, 0, false);
                    AddLeaf(stem, 50, 45, 58, 25, 15, 0, false);
                    break;
                case 1:
                    stem.Children.Add(StemPath($"M50,{N(S)} C47,46 53,38 50,31", 6));
                    AddLeaf(stem, 49, 46, -64, 25, 14, .1, false);
                    AddLeaf(stem, 49, 46, 64, 25, 14, .1, false);
                    AddLeaf(stem, 50.5, 35, -35, 23, 13, 0, false);
                    AddLeaf(stem, 50.5, 35, 35, 23, 13, 0, false);
                    break;
                case 2:
                    AddLeaf(stem, 45, S, -68, 25, 12, .35, false);
                    AddLeaf(stem, 55, S, 68, 25, 12, .35, false);
                    AddLeaf(stem, 47, S, -34, 32, 13, .1, false);
                    AddLeaf(stem, 53, S, 34, 32, 13, .1, false);
                    AddLeaf(stem, 50, S, 0, 38, 13, 0, false);
                    break;
                default:
                    AddLeaf(stem, 45, S, -70, 22, 11, .35, false);
                    AddLeaf(stem, 55, S, 70, 22, 11, .35, false);
                    AddLeaf(stem, 47, S, -38, 27, 12, .1, false);
                    AddLeaf(stem, 53, S, 38, 27, 12, .1, false);
                    AddFlower(stem, 50, S, 0, 31, 0xFFD3E0, 0xFF6F9B, 10);
                    break;
            }
        }

        // =====================================================================================
        //  MOOD / WATER / EYES
        // =====================================================================================
        static Color Tint0(PetMood m, Color c) => m switch
        {
            PetMood.Thirsty => Mix(c, Col(0xA9B545), 0.45),
            PetMood.VeryThirsty => Mix(c, Col(0x8A6A2E), 0.62),
            PetMood.Sleeping => Mix(c, Col(0x1B4A3A), 0.2),
            PetMood.Happy => Mix(c, Col(0xFFFFFF), 0.04),
            _ => c,
        };
        static Color Tint1(PetMood m, Color c) => m switch
        {
            PetMood.Thirsty => Mix(c, Col(0xE3DB6E), 0.5),
            PetMood.VeryThirsty => Mix(c, Col(0xCDA64E), 0.66),
            PetMood.Sleeping => Mix(c, Col(0x1B4A3A), 0.2),
            PetMood.Happy => Mix(c, Col(0xFFFFFF), 0.1),
            _ => c,
        };

        public override void SetMood(PetMood m)
        {
            Mood = m;
            if (Scene == null || _plant == null) return;
            SetSleepFx(m == PetMood.Sleeping);
            bool anim = !_snapMood; _snapMood = false;

            double delta = m switch { PetMood.Happy => -10, PetMood.Thirsty => 22, PetMood.VeryThirsty => 42, _ => 0 };
            double fold = m == PetMood.Sleeping ? 0.55 : 1;
            double lean = m switch { PetMood.Thirsty => 7, PetMood.VeryThirsty => 15, PetMood.Sleeping => 3, _ => 0 };
            double fs = m switch { PetMood.Happy => 1.12, PetMood.Thirsty => 0.9, PetMood.VeryThirsty => 0.72, PetMood.Sleeping => 0.82, _ => 1 };
            foreach (var l in _leaves)
            {
                double a = l.Ang * fold;
                if (m == PetMood.Happy) a += l.Dir * delta * Math.Min(1, Math.Abs(l.Ang) / 25);
                else a += l.Dir * delta * (l.Flower ? 0.72 : 1);
                Ang(l.Pose, Math.Clamp(a, -106, 106), anim);
                if (l.S0 != null) { Hue(l.S0, Tint0(m, l.C0), anim); Hue(l.S1!, Tint1(m, l.C1), anim); }
                if (l.FScale != null)
                {
                    if (anim)
                    {
                        var an = new DoubleAnimation(fs, TimeSpan.FromMilliseconds(380));
                        l.FScale.BeginAnimation(ScaleTransform.ScaleXProperty, an); l.FScale.BeginAnimation(ScaleTransform.ScaleYProperty, an);
                    }
                    else { l.FScale.BeginAnimation(ScaleTransform.ScaleXProperty, null); l.FScale.BeginAnimation(ScaleTransform.ScaleYProperty, null); l.FScale.ScaleX = l.FScale.ScaleY = fs; }
                }
            }
            Ang(_lean!, lean, anim);
            Hue(_stemBrush!, m switch { PetMood.Thirsty => Col(0x8DB04A), PetMood.VeryThirsty => Col(0xA58A3E), PetMood.Sleeping => Col(0x2F8F58), _ => Col(0x3BAA62) }, anim);

            // face
            bool happy = m == PetMood.Happy, sleep = m == PetMood.Sleeping, very = m == PetMood.VeryThirsty, thirsty = m == PetMood.Thirsty;
            Vis(_openEyes, !happy && !sleep); Vis(_happyEyes, happy); Vis(_sleepEyes, sleep); Vis(_brows, very); Vis(_tears, very);
            Vis(_mIdle, m == PetMood.Idle); Vis(_mHappy, happy); Vis(_mFlat, thirsty); Vis(_mSad, very); Vis(_mSleep, sleep);
            Vis(_sweat, thirsty);
            _eyeMood!.ScaleX = very ? 1.1 : thirsty ? 0.95 : 1;
            _eyeMood.ScaleY = very ? 1.1 : thirsty ? 0.74 : 1;
            double bo = m switch { PetMood.Happy => 0.95, PetMood.Idle => 0.55, PetMood.Sleeping => 0.5, PetMood.Thirsty => 0.22, _ => 0.1 };
            foreach (var b in _blush) Fade(b, bo, anim, 300);
            if (sleep) { _look!.X = 0; _look.Y = 0; }

            if (Idling) StartIdle();
        }

        public override void SetWater(double fraction, bool animate)
        {
            Water = Math.Clamp(fraction, 0, 1);
            if (_soil0 == null) return;
            double t = Water;
            Hue(_soil0, Mix(Col(0xE9D3A6), Col(0x6A4428), t), animate, 650);
            Hue(_soil1!, Mix(Col(0xCDAE7E), Col(0x3D2514), t), animate, 650);
            Fade(_cracks, Math.Clamp((0.5 - t) / 0.35, 0, 1), animate, 650);
            Fade(_sheen, Math.Clamp((t - 0.55) / 0.35, 0, 1) * 0.95, animate, 650);
            double dv = Math.Clamp((t - 0.7) / 0.1, 0, 1);
            foreach (var d in _drops) Fade(d, dv, animate, 500);
        }

        public override void LookAt(double dx, double dy)
        {
            if (_look == null || Mood == PetMood.Sleeping) return;
            double k = Compact ? 2.6 : 2.0;
            _look.X = Math.Clamp(dx, -1, 1) * k;
            _look.Y = Math.Clamp(dy, -1, 1) * k * 0.8;
        }

        public override void Blink()
        {
            if (_blinkT == null || Mood == PetMood.Sleeping || Mood == PetMood.Happy) return;
            var a = new DoubleAnimationUsingKeyFrames();
            a.KeyFrames.Add(new LinearDoubleKeyFrame(0.08, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(70))));
            a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(190))));
            _blinkT.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        // =====================================================================================
        //  ANIMATION (pot + plant move together; leaves do their own motion)
        // =====================================================================================
        public override void StartIdle()
        {
            base.StartIdle();
            if (_sceneMove == null || _sway == null) return;
            bool sl = Mood == PetMood.Sleeping;
            var bob = new DoubleAnimation(0, sl ? -1.2 : -3, TimeSpan.FromMilliseconds(sl ? 2200 : 1300))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() };
            Timeline.SetDesiredFrameRate(bob, 30);
            _sceneMove.BeginAnimation(TranslateTransform.YProperty, bob);

            double a = sl ? 0.8 : 2.2;
            var sw = new DoubleAnimation(-a, a, TimeSpan.FromMilliseconds(sl ? 3200 : 2100)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() };
            Timeline.SetDesiredFrameRate(sw, 30);
            _sway.BeginAnimation(RotateTransform.AngleProperty, sw);
            int i = 0;
            foreach (var l in _leaves)
            {
                double b = (sl ? 0.9 : 3.0) * (l.Flower ? 1.3 : 1);
                var ls = new DoubleAnimation(-b, b, TimeSpan.FromMilliseconds((sl ? 2600 : 1350) + (i % 5) * 170))
                { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase(), BeginTime = TimeSpan.FromMilliseconds(i * 60) };
                Timeline.SetDesiredFrameRate(ls, 30);
                l.Sway.BeginAnimation(RotateTransform.AngleProperty, ls);
                i++;
            }
        }

        public override void StopIdle()
        {
            base.StopIdle();
            if (_sceneMove == null || _sway == null) return;
            _sceneMove.BeginAnimation(TranslateTransform.YProperty, null); _sceneMove.Y = 0;
            _sway.BeginAnimation(RotateTransform.AngleProperty, null); _sway.Angle = 0;
            foreach (var l in _leaves) { l.Sway.BeginAnimation(RotateTransform.AngleProperty, null); l.Sway.Angle = 0; }
        }

        void SceneSquash(double sx, double sy, int ms)
        {
            if (_sceneScale == null) return;
            var x = new DoubleAnimationUsingKeyFrames();
            x.KeyFrames.Add(new EasingDoubleKeyFrame(sx, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.18))));
            x.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new ElasticEase { Oscillations = 2, Springiness = 5 }));
            var y = new DoubleAnimationUsingKeyFrames();
            y.KeyFrames.Add(new EasingDoubleKeyFrame(sy, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.18))));
            y.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new ElasticEase { Oscillations = 2, Springiness = 5 }));
            _sceneScale.BeginAnimation(ScaleTransform.ScaleXProperty, x);
            _sceneScale.BeginAnimation(ScaleTransform.ScaleYProperty, y);
        }

        void SceneHop(double h)
        {
            if (_sceneMove == null) return;
            var j = new DoubleAnimationUsingKeyFrames();
            j.KeyFrames.Add(new EasingDoubleKeyFrame(-h, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(220)), new QuadraticEase { EasingMode = EasingMode.EaseOut }));
            j.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(560)), new BounceEase { Bounces = 2, Bounciness = 3, EasingMode = EasingMode.EaseOut }));
            _sceneMove.BeginAnimation(TranslateTransform.YProperty, j);
        }

        void Shake(double amp)
        {
            if (_kick == null) return;
            var k = new DoubleAnimationUsingKeyFrames();
            double[] v = { -amp, amp * .8, -amp * .5, amp * .25, 0 };
            int[] t = { 90, 230, 360, 470, 600 };
            for (int i = 0; i < v.Length; i++) k.KeyFrames.Add(new LinearDoubleKeyFrame(v[i], KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(t[i]))));
            _kick.BeginAnimation(RotateTransform.AngleProperty, k);
        }

        void Pulse(double peak, int ms)
        {
            if (_grow == null) return;
            var a = new DoubleAnimationUsingKeyFrames();
            a.KeyFrames.Add(new EasingDoubleKeyFrame(peak, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.35)), new CubicEase { EasingMode = EasingMode.EaseOut }));
            a.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new ElasticEase { Oscillations = 1, Springiness = 4 }));
            _grow.BeginAnimation(ScaleTransform.ScaleXProperty, a);
            _grow.BeginAnimation(ScaleTransform.ScaleYProperty, a);
        }

        public override void PlayDrink()
        {
            base.PlayDrink();
            SceneSquash(1.12, 0.88, 420);
            Pulse(1.1, 700);
            if (_sprinkle == null) return;
            int i = 0;
            foreach (UIElement e in _sprinkle.Children)
            {
                var tt = (TranslateTransform)((TransformGroup)e.RenderTransform).Children[1];
                var fall = new DoubleAnimation(0, 40 + (i % 3) * 4, TimeSpan.FromMilliseconds(520)) { BeginTime = TimeSpan.FromMilliseconds(i * 65), EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseIn } };
                tt.BeginAnimation(TranslateTransform.YProperty, fall);
                var fade = new DoubleAnimationUsingKeyFrames { BeginTime = TimeSpan.FromMilliseconds(i * 65) };
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(60))));
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(400))));
                fade.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(520))));
                e.BeginAnimation(UIElement.OpacityProperty, fade);
                i++;
            }
        }

        public override void PlayCelebrate()
        {
            base.PlayCelebrate();
            SceneHop(16);
            Shake(7);
        }

        public override void PlayGoal()
        {
            base.PlayGoal();
            SceneHop(20);
            Shake(9);
        }

        public override void PlayEvolve()
        {
            base.PlayCelebrate();
            SceneHop(16);
            Pulse(1.22, 900);
            Shake(10);
        }

        public override void PlayPoke()
        {
            Squash(1.14, 0.84, 600);
            SceneSquash(1.14, 0.84, 600);
            Shake(11);
            Blink();
        }

        public override void Dance(double peak)
        {
            if (Mood == PetMood.Sleeping || _sceneScale == null || _kick == null) return;
            double p = Math.Clamp(peak, 0, 1);
            foreach (var s in new[] { BodyScale, _sceneScale })
            {
                s.BeginAnimation(ScaleTransform.ScaleXProperty, null);
                s.BeginAnimation(ScaleTransform.ScaleYProperty, null);
                s.ScaleY = 1 - p * 0.09;
                s.ScaleX = 1 + p * 0.06;
            }
            _kick.BeginAnimation(RotateTransform.AngleProperty, null);
            _kick.Angle = Math.Sin(Environment.TickCount64 / 260.0) * 7 * Math.Min(1, p * 2);
        }

        public override void StopDance()
        {
            BodyScale.ScaleX = BodyScale.ScaleY = 1;
            if (_sceneScale != null) { _sceneScale.ScaleX = _sceneScale.ScaleY = 1; }
            if (_kick != null) { _kick.BeginAnimation(RotateTransform.AngleProperty, null); _kick.Angle = 0; }
        }
    }
}
