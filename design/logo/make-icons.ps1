# Turns the Stitch logo render (logo-render.jpg) into app icons for Windows and Android.
#   - cuts the tile out of its grey background with smooth transparent corners
#   - Windows: Assets\app.ico (16-256 px) + Assets\logo-256.png
#   - Android: adaptive-icon foreground PNG (432 px, xxxhdpi)
# Usage: powershell -ExecutionPolicy Bypass -File make-icons.ps1
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $here '..\..')
$src = [System.Drawing.Bitmap]::FromFile((Join-Path $here 'logo-render.jpg'))

# --- find the tile: first/last pixels that differ from the flat background ---
$bg = $src.GetPixel(8, 8)
function PixDiff($c) { [math]::Abs($c.R - $bg.R) + [math]::Abs($c.G - $bg.G) + [math]::Abs($c.B - $bg.B) }
$mid = [int]($src.Width / 2)
$left = 0;  while ((PixDiff $src.GetPixel($left, $mid)) -lt 60) { $left++ }
$right = $src.Width - 1; while ((PixDiff $src.GetPixel($right, $mid)) -lt 60) { $right-- }
$top = 0;   while ((PixDiff $src.GetPixel($mid, $top)) -lt 60) { $top++ }
$bottom = $src.Height - 1; while ((PixDiff $src.GetPixel($mid, $bottom)) -lt 60) { $bottom-- }
$w = $right - $left + 1; $h = $bottom - $top + 1
"tile: x=$left..$right y=$top..$bottom ($w x $h)"
$crop = $src.Clone((New-Object System.Drawing.Rectangle $left, $top, $w, $h), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)

function RoundRect([float]$x, [float]$y, [float]$s, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2 * $r
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $s - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $s - $d, $y + $s - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $s - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

# --- master: 1024 px, tile edge-to-edge with anti-aliased transparent corners ---
$N = 1024
$master = New-Object System.Drawing.Bitmap $N, $N, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($master)
$g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.PixelOffsetMode = 'HighQuality'
$brush = New-Object System.Drawing.TextureBrush $crop
$brush.WrapMode = 'Clamp'
$brush.ScaleTransform($N / $w, $N / $h)
$inset = 7                        # trims the grey anti-aliased fringe of the render
$g.FillPath($brush, (RoundRect $inset $inset ($N - 2 * $inset) (0.215 * $N)))
$g.Dispose()
$master.Save((Join-Path $here 'icon-1024.png'), [System.Drawing.Imaging.ImageFormat]::Png)

function Scaled([System.Drawing.Bitmap]$img, [int]$size) {
    $b = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $gg = [System.Drawing.Graphics]::FromImage($b)
    $gg.InterpolationMode = 'HighQualityBicubic'; $gg.PixelOffsetMode = 'HighQuality'; $gg.SmoothingMode = 'AntiAlias'
    $attr = New-Object System.Drawing.Imaging.ImageAttributes
    $attr.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
    $gg.DrawImage($img, (New-Object System.Drawing.Rectangle 0, 0, $size, $size), 0, 0, $img.Width, $img.Height, 'Pixel', $attr)
    $gg.Dispose()
    return $b
}
function PngBytes($bmp) { $ms = New-Object System.IO.MemoryStream; $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png); return , $ms.ToArray() }

# --- Windows .ico (PNG-compressed entries) ---
$assets = Join-Path $root 'windows\DynamicIsland\Assets'
New-Item -ItemType Directory -Force $assets | Out-Null
$sizes = 16, 20, 24, 32, 40, 48, 64, 96, 128, 256
$images = foreach ($s in $sizes) { , (PngBytes (Scaled $master $s)) }
$ico = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $ico
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $len = $images[$i].Length
    $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s }))); $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
    $bw.Write([byte]0); $bw.Write([byte]0); $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$len); $bw.Write([UInt32]$offset); $offset += $len
}
foreach ($img in $images) { $bw.Write($img) }
$bw.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $assets 'app.ico'), $ico.ToArray())
(Scaled $master 256).Save((Join-Path $assets 'logo-256.png'), [System.Drawing.Imaging.ImageFormat]::Png)
"windows: $assets\app.ico ($($sizes -join ', ') px)"

# --- Android adaptive foreground: 108dp canvas @4x = 432 px; tile = 76dp so the launcher mask trims its edge ---
$res = Join-Path $root 'android\app\src\main\res\drawable-xxxhdpi'
New-Item -ItemType Directory -Force $res | Out-Null
$fg = New-Object System.Drawing.Bitmap 432, 432, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g2 = [System.Drawing.Graphics]::FromImage($fg)
$g2.InterpolationMode = 'HighQualityBicubic'; $g2.PixelOffsetMode = 'HighQuality'
$tile = Scaled $master 304
$g2.DrawImage($tile, 64, 64, 304, 304)
$g2.Dispose()
$fg.Save((Join-Path $res 'ic_launcher_fg.png'), [System.Drawing.Imaging.ImageFormat]::Png)
"android: $res\ic_launcher_fg.png"

# preview sheet of small sizes on dark + light (to judge readability)
$sheet = New-Object System.Drawing.Bitmap 560, 160
$g3 = [System.Drawing.Graphics]::FromImage($sheet)
$g3.Clear([System.Drawing.Color]::FromArgb(32, 32, 32))
$g3.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(243, 243, 243))), 0, 80, 560, 80)
$x = 10
foreach ($s in 16, 20, 24, 32, 48, 64) { $b = Scaled $master $s; $g3.DrawImage($b, $x, 40 - $s / 2); $g3.DrawImage($b, $x, 120 - $s / 2); $x += $s + 24 }
$g3.DrawImage((Scaled $master 128), 420, 16, 128, 128)
$g3.Dispose()
$sheet.Save((Join-Path $here 'sizes-preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
"preview: $here\sizes-preview.png"
