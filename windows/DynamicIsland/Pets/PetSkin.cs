using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace DynamicIsland.Pets
{
    public enum PetMood { Idle, Happy, Thirsty, VeryThirsty, Sleeping }
    public enum PetKind { Axolotl, Droplet, Plant, Otter }

    /// <summary>
    /// One character. Draws into a 100×100 logical canvas (PetView scales it to any size).
    /// Layers, bottom to top: Scene (tank / pot / pond — shows today's water), Body (the character,
    /// moved by the base animations), Fx (sparkles, hearts, splash, Zzz — owned by this base class).
    ///
    /// Rules for skins:
    ///  • Stage 0..3 must look clearly different (growth is the reward). Cosmetic 0 = none, 1..3 = unlockables.
    ///  • Compact = drawn at ~22 px in the island pill: simplify (head/face only, thick shapes, no thin lines).
    ///  • Keep the character's "feet"/base around y≈92 so BodyScale/BodyTilt pivot correctly.
    ///  • No continuous animation unless StartIdle() was called. Freeze brushes/geometries you never change.
    /// </summary>
    public abstract class PetSkin
    {
        protected Canvas Scene = null!;
        protected Canvas Body = null!;
        protected Canvas Fx = null!;
        protected readonly ScaleTransform BodyScale = new(1, 1, 50, 92);
        protected readonly RotateTransform BodyTilt = new(0, 50, 92);
        protected readonly TranslateTransform BodyMove = new();

        Canvas _sparkles = null!, _hearts = null!, _zzz = null!, _splash = null!;
        protected bool Idling;

        public int Stage { get; private set; }
        public int Cosmetic { get; private set; }
        public bool Compact { get; private set; }
        public PetMood Mood { get; protected set; } = PetMood.Idle;
        public double Water { get; protected set; } = 0.3;

        public abstract PetKind Kind { get; }
        public abstract string Name { get; }
        /// <summary>Exactly 4 names, e.g. Baby, Teen, Adult, Elder.</summary>
        public abstract string[] StageNames { get; }
        /// <summary>Exactly 4 names; [0] = "None".</summary>
        public abstract string[] CosmeticNames { get; }

        public void Attach(Canvas root, int stage, int cosmetic, bool compact)
        {
            root.Children.Clear();
            Stage = Math.Clamp(stage, 0, 3);
            Cosmetic = Math.Clamp(cosmetic, 0, 3);
            Compact = compact;

            Scene = new Canvas { Width = 100, Height = 100 };
            Body = new Canvas { Width = 100, Height = 100 };
            var g = new TransformGroup();
            g.Children.Add(BodyScale); g.Children.Add(BodyTilt); g.Children.Add(BodyMove);
            Body.RenderTransform = g;
            Fx = new Canvas { Width = 100, Height = 100, IsHitTestVisible = false };

            BuildScene(Scene);
            BuildBody(Body);
            BuildFx();

            root.Children.Add(Scene);
            root.Children.Add(Body);
            root.Children.Add(Fx);
            SetMood(Mood);
            SetWater(Water, false);
        }

        internal void SetMoodSilently(PetMood m) => Mood = m;
        internal void SetWaterSilently(double w) => Water = w;

        protected abstract void BuildScene(Canvas scene);
        protected abstract void BuildBody(Canvas body);

        /// <summary>Change expression/pose/colours. Implementations must set Mood and call SetSleepFx(m == Sleeping).</summary>
        public abstract void SetMood(PetMood m);
        /// <summary>Today's progress 0..1 shown in the scene (tank level, soil moisture, body fill, pond).</summary>
        public abstract void SetWater(double fraction, bool animate);
        /// <summary>Point eyes toward a direction, each axis -1..1. Ignore while sleeping.</summary>
        public abstract void LookAt(double dx, double dy);
        public abstract void Blink();

        // ---------- default animations (skins may override and call base) ----------

        public virtual void StartIdle()
        {
            Idling = true;
            var bob = new DoubleAnimation(0, Mood == PetMood.Sleeping ? -1.2 : -3, TimeSpan.FromMilliseconds(Mood == PetMood.Sleeping ? 2200 : 1300))
            { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase() };
            Timeline.SetDesiredFrameRate(bob, 30);
            BodyMove.BeginAnimation(TranslateTransform.YProperty, bob);
        }

        public virtual void StopIdle()
        {
            Idling = false;
            BodyMove.BeginAnimation(TranslateTransform.YProperty, null);
            BodyTilt.BeginAnimation(RotateTransform.AngleProperty, null);
        }

        /// <summary>Logged a drink: gulp + splash.</summary>
        public virtual void PlayDrink()
        {
            Squash(1.12, 0.88, 420);
            Flash(_splash, 700);
        }

        public virtual void PlayCelebrate()
        {
            Jump(16);
            Flash(_sparkles, 1500);
        }

        public virtual void PlayGoal()
        {
            Jump(20);
            Flash(_hearts, 1800);
            Flash(_sparkles, 1800);
        }

        /// <summary>Stage-up flourish (~1.5 s). Caller re-attaches with the new stage halfway through.</summary>
        public virtual void PlayEvolve()
        {
            var spin = new DoubleAnimation(0, 360, TimeSpan.FromMilliseconds(900)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut } };
            BodyTilt.BeginAnimation(RotateTransform.AngleProperty, spin);
            Squash(1.25, 1.25, 900);
            Flash(_sparkles, 1600);
        }

        public virtual void PlayPoke()
        {
            Squash(1.16, 0.8, 600);
            Blink();
        }

        /// <summary>Called ~30×/s with music loudness 0..1 while a song plays.</summary>
        public virtual void Dance(double peak)
        {
            if (Mood == PetMood.Sleeping) return;
            double p = Math.Clamp(peak, 0, 1);
            BodyScale.BeginAnimation(ScaleTransform.ScaleXProperty, null);
            BodyScale.BeginAnimation(ScaleTransform.ScaleYProperty, null);
            BodyScale.ScaleY = 1 - p * 0.09;
            BodyScale.ScaleX = 1 + p * 0.06;
            BodyTilt.BeginAnimation(RotateTransform.AngleProperty, null);
            BodyTilt.Angle = Math.Sin(Environment.TickCount64 / 260.0) * 6 * Math.Min(1, p * 2);
        }

        public virtual void StopDance()
        {
            BodyScale.ScaleX = BodyScale.ScaleY = 1;
            BodyTilt.Angle = 0;
        }

        // ---------- helpers for skins ----------

        protected void SetSleepFx(bool sleeping) => _zzz.Visibility = sleeping ? Visibility.Visible : Visibility.Collapsed;

        protected void Squash(double sx, double sy, int ms)
        {
            var x = new DoubleAnimationUsingKeyFrames();
            x.KeyFrames.Add(new EasingDoubleKeyFrame(sx, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.18))));
            x.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new ElasticEase { Oscillations = 2, Springiness = 5 }));
            var y = new DoubleAnimationUsingKeyFrames();
            y.KeyFrames.Add(new EasingDoubleKeyFrame(sy, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.18))));
            y.KeyFrames.Add(new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms)), new ElasticEase { Oscillations = 2, Springiness = 5 }));
            BodyScale.BeginAnimation(ScaleTransform.ScaleXProperty, x);
            BodyScale.BeginAnimation(ScaleTransform.ScaleYProperty, y);
        }

        protected void Jump(double height)
        {
            var j = new DoubleAnimationUsingKeyFrames();
            j.KeyFrames.Add(new EasingDoubleKeyFrame(-height, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(220)), new QuadraticEase { EasingMode = EasingMode.EaseOut }));
            j.KeyFrames.Add(new EasingDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(560)), new BounceEase { Bounces = 2, Bounciness = 3, EasingMode = EasingMode.EaseOut }));
            j.Completed += (_, _) => { if (Idling) StartIdle(); };
            BodyMove.BeginAnimation(TranslateTransform.YProperty, j);
        }

        protected static void Flash(UIElement e, int ms)
        {
            var a = new DoubleAnimationUsingKeyFrames();
            a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(120))));
            a.KeyFrames.Add(new LinearDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms * 0.7))));
            a.KeyFrames.Add(new LinearDoubleKeyFrame(0, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(ms))));
            e.BeginAnimation(UIElement.OpacityProperty, a);
        }

        protected static SolidColorBrush B(byte r, byte g, byte b, byte a = 255) { var s = new SolidColorBrush(Color.FromArgb(a, r, g, b)); s.Freeze(); return s; }
        protected static Geometry G(string data) { var g = Geometry.Parse(data); g.Freeze(); return g; }
        protected static T At<T>(T e, double x, double y) where T : UIElement { Canvas.SetLeft(e, x); Canvas.SetTop(e, y); return e; }

        void BuildFx()
        {
            _sparkles = new Canvas { Opacity = 0 };
            foreach (var (x, y, s) in new[] { (8.0, 24.0, 9.0), (88.0, 16.0, 11.0), (92.0, 64.0, 7.0), (6.0, 70.0, 6.0), (50.0, 4.0, 7.0) })
                _sparkles.Children.Add(At(new Path { Data = G("M0,-1 L0.25,-0.25 1,0 0.25,0.25 0,1 -0.25,0.25 -1,0 -0.25,-0.25 Z"), Fill = B(0xFF, 0xF1, 0x76), RenderTransform = new ScaleTransform(s, s) }, x, y));

            _hearts = new Canvas { Opacity = 0 };
            foreach (var (x, y, s) in new[] { (4.0, 14.0, 0.8), (82.0, 6.0, 1.0), (88.0, 46.0, 0.65) })
                _hearts.Children.Add(At(new Path { Data = G("M0,3 C0,-1 6,-1 6,3 C6,-1 12,-1 12,3 C12,7 6,10 6,12 C6,10 0,7 0,3 Z"), Fill = B(0xFF, 0x6B, 0x9A), RenderTransform = new ScaleTransform(s, s) }, x, y));

            _splash = new Canvas { Opacity = 0 };
            foreach (var (x, y, s) in new[] { (22.0, 30.0, 0.6), (74.0, 26.0, 0.7), (14.0, 50.0, 0.45), (84.0, 48.0, 0.5) })
                _splash.Children.Add(At(new Path { Data = G("M5,0 C4,3 0,6 0,9 A5,5 0 0 0 10,9 C10,6 6,3 5,0 Z"), Fill = B(0x81, 0xD4, 0xFA), RenderTransform = new ScaleTransform(s, s) }, x, y));

            _zzz = new Canvas { Visibility = Visibility.Collapsed };
            foreach (var (x, y, size) in new[] { (72.0, 18.0, 12.0), (82.0, 4.0, 16.0) })
                _zzz.Children.Add(At(new TextBlock { Text = "z", FontSize = size, FontWeight = FontWeights.Bold, Foreground = B(0xB3, 0xE5, 0xFC) }, x, y));

            if (Compact) { _hearts.Visibility = _splash.Visibility = Visibility.Collapsed; }
            Fx.Children.Add(_splash); Fx.Children.Add(_sparkles); Fx.Children.Add(_hearts); Fx.Children.Add(_zzz);
        }
    }

    /// <summary>Hosts any PetSkin and scales it. Handles blinking and pausing when hidden.</summary>
    public sealed class PetView : Viewbox
    {
        readonly Canvas _root = new() { Width = 100, Height = 100 };
        readonly DispatcherTimer _blink = new() { Interval = TimeSpan.FromSeconds(3) };
        static readonly Random Rng = new();
        public PetSkin Skin { get; private set; } = PetSkins.Create(PetKind.Axolotl);

        public static readonly DependencyProperty CompactProperty = DependencyProperty.Register(nameof(Compact), typeof(bool), typeof(PetView), new PropertyMetadata(false));
        public bool Compact { get => (bool)GetValue(CompactProperty); set => SetValue(CompactProperty, value); }

        public PetView()
        {
            Stretch = Stretch.Uniform;
            Child = _root;
            Loaded += (_, _) => { if (!_attached) { Skin.Attach(_root, 0, 0, Compact); _attached = true; } };
            _blink.Tick += (_, _) => { if (Skin.Mood != PetMood.Sleeping) Skin.Blink(); _blink.Interval = TimeSpan.FromSeconds(2.5 + Rng.NextDouble() * 4); };
            IsVisibleChanged += (_, _) =>
            {
                if (IsVisible) _blink.Start();
                else { _blink.Stop(); Skin.StopIdle(); Skin.StopDance(); }
            };
        }

        /// <summary>Switch character / stage / cosmetic. Keeps current mood and water.</summary>
        public void SetPet(PetKind kind, int stage, int cosmetic)
        {
            var mood = Skin.Mood;
            var water = Skin.Water;
            bool newSkin = Skin.Kind != kind || !_attached;
            if (Skin.Kind != kind) Skin = PetSkins.Create(kind);
            if (newSkin || Skin.Stage != stage || Skin.Cosmetic != cosmetic || Skin.Compact != Compact)
            {
                Skin.SetMoodSilently(mood);
                Skin.SetWaterSilently(water);
                Skin.Attach(_root, stage, cosmetic, Compact);
                _attached = true;
            }
        }

        bool _attached;

        public void SetMood(PetMood m) { if (Skin.Mood != m) Skin.SetMood(m); }
        public void SetWater(double f, bool animate = true) => Skin.SetWater(f, animate);
        public void LookAt(double dx, double dy) => Skin.LookAt(dx, dy);
        public void StartIdle() => Skin.StartIdle();
        public void StopIdle() => Skin.StopIdle();
        public void Drink() => Skin.PlayDrink();
        public void Celebrate() => Skin.PlayCelebrate();
        public void Goal() => Skin.PlayGoal();
        public void Poke() => Skin.PlayPoke();
        public void Dance(double peak) => Skin.Dance(peak);
        public void StopDance() => Skin.StopDance();

        /// <summary>Stage-up: flourish, then swap to the new stage.</summary>
        public void Evolve(int newStage, int cosmetic)
        {
            Skin.PlayEvolve();
            var t = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
            t.Tick += (_, _) => { t.Stop(); SetPet(Skin.Kind, newStage, cosmetic); };
            t.Start();
        }
    }

    public static class PetSkins
    {
        public static PetSkin Create(PetKind k) => k switch
        {
            PetKind.Droplet => new DropletSkin(),
            PetKind.Plant => new PlantSkin(),
            PetKind.Otter => new OtterSkin(),
            _ => new AxolotlSkin(),
        };

        public static readonly PetKind[] All = { PetKind.Axolotl, PetKind.Droplet, PetKind.Plant, PetKind.Otter };
    }
}
