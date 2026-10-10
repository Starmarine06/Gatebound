# Generates the spell-effect textures, item models and item definitions used by the mod's effect props.
# Run from the repo root:  powershell -ExecutionPolicy Bypass -File tools\gen_fx_assets.ps1
# Any texture in common\src\main\resources\assets\gatebound\textures\item\fx can be replaced with hand-made art of the same name.
Add-Type -AssemblyName System.Drawing
$root = Join-Path $PSScriptRoot '..\common\src\main\resources\assets\gatebound'
$texDir = Join-Path $root 'textures\item\fx'
$modelDir = Join-Path $root 'models\item\fx'
$itemDir = Join-Path $root 'items\fx'
foreach ($d in @($texDir, $modelDir, $itemDir)) { New-Item -ItemType Directory -Force $d | Out-Null }
$enc = New-Object System.Text.UTF8Encoding($false)

# ------------------------------------------------------------------ colours
function C($a, $r, $g, $b) { return [System.Drawing.Color]::FromArgb($a, $r, $g, $b) }
$elements = [ordered]@{
  fire      = @{ glow = @(255, 110, 25);  core = @(255, 230, 150) }
  ice       = @{ glow = @(90, 190, 255);  core = @(235, 252, 255) }
  lightning = @{ glow = @(255, 225, 60);  core = @(255, 255, 215) }
  wind      = @{ glow = @(150, 235, 200); core = @(245, 255, 252) }
  earth     = @{ glow = @(140, 100, 55);  core = @(190, 235, 120) }
  light     = @{ glow = @(255, 205, 90);  core = @(255, 252, 225) }
  dark      = @{ glow = @(140, 50, 225);  core = @(235, 200, 255) }
  warrior   = @{ glow = @(210, 58, 34);   core = @(255, 225, 190) }
}

function NewCanvas($size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Clear([System.Drawing.Color]::Transparent)
  return @{ bmp = $bmp; g = $g }
}

# A line that glows: a few passes of falling width and rising brightness, finished with a bright core.
function GlowLines($g, $pts, $glow, $core, $w) {
  $passes = @(@(6.0, 28), @(3.4, 70), @(1.8, 150))
  foreach ($p in $passes) {
    $pen = New-Object System.Drawing.Pen (C $p[1] $glow[0] $glow[1] $glow[2]), ([single]($p[0] * $w))
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round; $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawLines($pen, $pts); $pen.Dispose()
  }
  $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single](0.9 * $w))
  $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
  $g.DrawLines($pen, $pts); $pen.Dispose()
}

function GlowCircle($g, $cx, $cy, $r, $glow, $core, $w) {
  $n = [int][math]::Max(48, $r * 0.9)
  $pts = New-Object 'System.Drawing.PointF[]' ($n + 1)
  for ($i = 0; $i -le $n; $i++) {
    $a = 2 * [math]::PI * $i / $n
    $pts[$i] = New-Object System.Drawing.PointF ([single]($cx + [math]::Cos($a) * $r)), ([single]($cy + [math]::Sin($a) * $r))
  }
  GlowLines $g $pts $glow $core $w
}

function Pt($x, $y) { return New-Object System.Drawing.PointF ([single]$x), ([single]$y) }

function PolyStar($g, $cx, $cy, $r, $points, $skip, $rot, $glow, $core, $w) {
  # A {points/skip} star polygon: every vertex joined to the one 'skip' places along.
  $v = @()
  for ($i = 0; $i -lt $points; $i++) {
    $a = $rot + 2 * [math]::PI * $i / $points
    $v += , (Pt ($cx + [math]::Cos($a) * $r) ($cy + [math]::Sin($a) * $r))
  }
  $visited = 0; $i = 0; $pts = @()
  $count = if ($points % $skip -eq 0) { $points / $skip } else { $points }
  for ($k = 0; $k -le $count; $k++) { $pts += , $v[$i]; $i = ($i + $skip) % $points }
  GlowLines $g ([System.Drawing.PointF[]]$pts) $glow $core $w
  if ($points % $skip -eq 0 -and $skip -gt 1) {
    for ($s = 1; $s -lt $skip; $s++) {
      $i = $s; $pts = @()
      for ($k = 0; $k -le $count; $k++) { $pts += , $v[$i]; $i = ($i + $skip) % $points }
      GlowLines $g ([System.Drawing.PointF[]]$pts) $glow $core $w
    }
  }
}

# Invented rune glyphs: a few strokes between points of a 3x5 grid, drawn along the ring.
function Glyph($g, $cx, $cy, $angle, $radius, $height, $width, $rng, $glow, $core, $w) {
  $ta = $angle + [math]::PI / 2
  $rx = [math]::Cos($angle); $ry = [math]::Sin($angle)
  $tx = [math]::Cos($ta);    $ty = [math]::Sin($ta)
  $grid = @()
  for ($u = 0; $u -lt 3; $u++) { for ($v = 0; $v -lt 5; $v++) { $grid += , @($u, $v) } }
  $strokes = 3 + $rng.Next(0, 3)
  $cur = $grid[$rng.Next(0, $grid.Count)]
  if ($rng.Next(0, 2) -eq 0) { # a stem most glyphs share
    $a = GlyphPt $cx $cy $rx $ry $tx $ty $radius $height $width 1 0
    $b = GlyphPt $cx $cy $rx $ry $tx $ty $radius $height $width 1 4
    GlowLines $g ([System.Drawing.PointF[]]@($a, $b)) $glow $core ($w * 0.7)
  }
  for ($s = 0; $s -lt $strokes; $s++) {
    $nxt = $grid[$rng.Next(0, $grid.Count)]
    if ($nxt[0] -eq $cur[0] -and $nxt[1] -eq $cur[1]) { continue }
    $a = GlyphPt $cx $cy $rx $ry $tx $ty $radius $height $width $cur[0] $cur[1]
    $b = GlyphPt $cx $cy $rx $ry $tx $ty $radius $height $width $nxt[0] $nxt[1]
    GlowLines $g ([System.Drawing.PointF[]]@($a, $b)) $glow $core ($w * 0.7)
    $cur = $nxt
  }
}
function GlyphPt($cx, $cy, $rx, $ry, $tx, $ty, $radius, $height, $width, $u, $v) {
  $along = ($u - 1) * ($width / 2)          # sideways along the ring
  $out = ($v - 2) * ($height / 4)           # across the band
  return Pt ($cx + $rx * ($radius + $out) + $tx * $along) ($cy + $ry * ($radius + $out) + $ty * $along)
}

# ------------------------------------------------------------------ magic circles (512x512)
function DrawCircle($name, $glow, $core, $seed) {
  $S = 512; $c = $S / 2
  $cv = NewCanvas $S; $g = $cv.g
  # soft glow inside
  $path = New-Object System.Drawing.Drawing2D.GraphicsPath
  $path.AddEllipse(8, 8, $S - 16, $S - 16)
  $br = New-Object System.Drawing.Drawing2D.PathGradientBrush $path
  $br.CenterColor = C 55 $glow[0] $glow[1] $glow[2]
  $br.SurroundColors = @((C 0 $glow[0] $glow[1] $glow[2]))
  $g.FillPath($br, $path); $br.Dispose(); $path.Dispose()

  GlowCircle $g $c $c 248 $glow $core 1.3
  GlowCircle $g $c $c 240 $glow $core 0.6
  # tick band
  for ($i = 0; $i -lt 120; $i++) {
    $a = 2 * [math]::PI * $i / 120
    $len = if ($i % 5 -eq 0) { 14 } else { 6 }
    GlowLines $g ([System.Drawing.PointF[]]@((Pt ($c + [math]::Cos($a) * 238) ($c + [math]::Sin($a) * 238)), (Pt ($c + [math]::Cos($a) * (238 - $len)) ($c + [math]::Sin($a) * (238 - $len))))) $glow $core 0.5
  }
  GlowCircle $g $c $c 214 $glow $core 0.9
  GlowCircle $g $c $c 168 $glow $core 0.9
  # inscription band between 214 and 168
  $rng = New-Object System.Random $seed
  $glyphs = 30
  for ($i = 0; $i -lt $glyphs; $i++) {
    $a = 2 * [math]::PI * $i / $glyphs
    Glyph $g $c $c $a 191 34 24 $rng $glow $core 1.0
  }
  # inner geometry (differs per element)
  GlowCircle $g $c $c 160 $glow $core 0.7
  switch ($name) {
    'fire'      { PolyStar $g $c $c 158 5 2 (-[math]::PI / 2) $glow $core 1.2; GlowCircle $g $c $c 62 $glow $core 1.0 }
    'ice'       { PolyStar $g $c $c 158 6 2 (-[math]::PI / 2) $glow $core 1.1
                  for ($i = 0; $i -lt 6; $i++) { $a = $i * [math]::PI / 3; GlowLines $g ([System.Drawing.PointF[]]@((Pt $c $c), (Pt ($c + [math]::Cos($a) * 150) ($c + [math]::Sin($a) * 150)))) $glow $core 0.6 }
                  GlowCircle $g $c $c 70 $glow $core 0.9 }
    'lightning' { PolyStar $g $c $c 158 8 3 (-[math]::PI / 2) $glow $core 1.0
                  for ($i = 0; $i -lt 8; $i++) { $a = $i * [math]::PI / 4 + [math]::PI / 8; GlowLines $g ([System.Drawing.PointF[]]@((Pt ($c + [math]::Cos($a) * 40) ($c + [math]::Sin($a) * 40)), (Pt ($c + [math]::Cos($a + 0.12) * 80) ($c + [math]::Sin($a + 0.12) * 80)), (Pt ($c + [math]::Cos($a - 0.08) * 110) ($c + [math]::Sin($a - 0.08) * 110)), (Pt ($c + [math]::Cos($a) * 140) ($c + [math]::Sin($a) * 140)))) $glow $core 0.8 } }
    'wind'      { for ($arm = 0; $arm -lt 3; $arm++) {
                    $pts = @()
                    for ($t = 0; $t -le 60; $t++) { $r = 20 + $t * 2.3; $a = $arm * 2 * [math]::PI / 3 + $t * 0.075; $pts += , (Pt ($c + [math]::Cos($a) * $r) ($c + [math]::Sin($a) * $r)) }
                    GlowLines $g ([System.Drawing.PointF[]]$pts) $glow $core 1.1 }
                  GlowCircle $g $c $c 22 $glow $core 0.9 }
    'earth'     { for ($k = 0; $k -lt 2; $k++) { $rot = $k * [math]::PI / 4; PolyStar $g $c $c 150 4 1 $rot $glow $core 1.1 }
                  GlowCircle $g $c $c 90 $glow $core 0.9; GlowCircle $g $c $c 40 $glow $core 0.9 }
    'light'     { for ($i = 0; $i -lt 16; $i++) { $a = $i * [math]::PI / 8; $len = if ($i % 2 -eq 0) { 150 } else { 112 }
                    GlowLines $g ([System.Drawing.PointF[]]@((Pt ($c + [math]::Cos($a) * 50) ($c + [math]::Sin($a) * 50)), (Pt ($c + [math]::Cos($a) * $len) ($c + [math]::Sin($a) * $len)))) $glow $core 1.0 }
                  GlowCircle $g $c $c 50 $glow $core 1.1 }
    'warrior'   { PolyStar $g $c $c 158 4 1 ([math]::PI / 4) $glow $core 1.3
                  PolyStar $g $c $c 158 4 1 0 $glow $core 0.8
                  for ($i = 0; $i -lt 4; $i++) { $a = $i * [math]::PI / 2 + [math]::PI / 4; GlowLines $g ([System.Drawing.PointF[]]@((Pt ($c + [math]::Cos($a) * 30) ($c + [math]::Sin($a) * 30)), (Pt ($c + [math]::Cos($a) * 112) ($c + [math]::Sin($a) * 112)))) $glow $core 1.4 }
                  GlowCircle $g $c $c 60 $glow $core 1.1 }
    'dark'      { PolyStar $g $c $c 156 5 2 ([math]::PI / 2) $glow $core 1.2
                  GlowCircle $g $c $c 70 $glow $core 1.0
                  GlowCircle $g $c $c 24 $glow $core 1.2 }
  }
  $g.Dispose()
  $cv.bmp.Save((Join-Path $texDir "circle_$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}

# ------------------------------------------------------------------ shockwave rings (256x256)
function DrawRing($name, $glow, $core) {
  $S = 256; $c = $S / 2
  $cv = NewCanvas $S; $g = $cv.g
  foreach ($w in @(@(15, 26), @(10, 60), @(6, 120))) {
    $pen = New-Object System.Drawing.Pen (C $w[1] $glow[0] $glow[1] $glow[2]), ([single]$w[0])
    $g.DrawEllipse($pen, ($c - 118), ($c - 118), 236, 236); $pen.Dispose()
  }
  $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]3)
  $g.DrawEllipse($pen, ($c - 118), ($c - 118), 236, 236); $pen.Dispose()
  $g.Dispose()
  $cv.bmp.Save((Join-Path $texDir "ring_$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}

# ------------------------------------------------------------------ projectile and prop textures
function DrawFireball {
  $S = 128; $c = 64
  $cv = NewCanvas $S; $g = $cv.g
  $layers = @(@(62, 40, 255, 80, 10), @(52, 90, 255, 110, 15), @(42, 150, 255, 160, 25), @(30, 210, 255, 205, 60), @(18, 255, 255, 245, 160))
  foreach ($l in $layers) {
    $br = New-Object System.Drawing.SolidBrush (C $l[1] $l[2] $l[3] $l[4])
    $g.FillEllipse($br, ($c - $l[0]), ($c - $l[0]), ($l[0] * 2), ($l[0] * 2)); $br.Dispose()
  }
  # flame tongues licking out
  $rng = New-Object System.Random 7
  for ($i = 0; $i -lt 9; $i++) {
    $a = $rng.NextDouble() * 2 * [math]::PI; $len = 46 + $rng.Next(0, 14)
    $pts = @((Pt ($c + [math]::Cos($a - 0.35) * 24) ($c + [math]::Sin($a - 0.35) * 24)), (Pt ($c + [math]::Cos($a) * $len) ($c + [math]::Sin($a) * $len)), (Pt ($c + [math]::Cos($a + 0.35) * 24) ($c + [math]::Sin($a + 0.35) * 24)))
    $br = New-Object System.Drawing.SolidBrush (C 170 255 140 30)
    $g.FillPolygon($br, [System.Drawing.PointF[]]$pts); $br.Dispose()
  }
  $br = New-Object System.Drawing.SolidBrush (C 255 255 250 220)
  $g.FillEllipse($br, ($c - 12), ($c - 12), 24, 24); $br.Dispose()
  $g.Dispose()
  $cv.bmp.Save((Join-Path $texDir 'fireball.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}

function DrawIce($file, $alpha) {
  $S = 64
  $cv = NewCanvas $S; $g = $cv.g
  $rect = New-Object System.Drawing.Rectangle 0, 0, $S, $S
  $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (C $alpha 150 225 255), (C $alpha 40 130 230), 45.0
  $g.FillRectangle($br, $rect); $br.Dispose()
  $pen = New-Object System.Drawing.Pen (C 200 255 255 255), ([single]2)
  $g.DrawLine($pen, 6, 58, 30, 6); $g.DrawLine($pen, 36, 60, 56, 14); $pen.Dispose()
  $pen = New-Object System.Drawing.Pen (C 120 255 255 255), ([single]1)
  $g.DrawLine($pen, 14, 62, 40, 4); $g.DrawLine($pen, 48, 62, 62, 28); $pen.Dispose()
  $g.Dispose()
  $cv.bmp.Save((Join-Path $texDir $file), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}

function DrawMeteor {
  $S = 64
  $cv = NewCanvas $S; $g = $cv.g
  $rng = New-Object System.Random 11
  $g.Clear((C 255 38 24 20))
  for ($i = 0; $i -lt 220; $i++) {
    $x = $rng.Next(0, $S); $y = $rng.Next(0, $S); $v = $rng.Next(0, 28)
    $br = New-Object System.Drawing.SolidBrush (C 255 (38 + $v) (24 + $v) (20 + $v))
    $g.FillRectangle($br, $x, $y, 3, 3); $br.Dispose()
  }
  for ($i = 0; $i -lt 9; $i++) { # glowing cracks
    $x = $rng.Next(0, $S); $y = $rng.Next(0, $S); $pts = @((Pt $x $y))
    for ($k = 0; $k -lt 4; $k++) { $x += $rng.Next(-12, 13); $y += $rng.Next(-12, 13); $pts += , (Pt $x $y) }
    $pen = New-Object System.Drawing.Pen (C 255 255 120 20), ([single]2.2)
    $g.DrawLines($pen, [System.Drawing.PointF[]]$pts); $pen.Dispose()
    $pen = New-Object System.Drawing.Pen (C 255 255 220 120), ([single]0.8)
    $g.DrawLines($pen, [System.Drawing.PointF[]]$pts); $pen.Dispose()
  }
  $g.Dispose()
  $cv.bmp.Save((Join-Path $texDir 'meteor.png'), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}

# ------------------------------------------------------------------ models
function Face($tex) { return '{"uv": [0, 0, 16, 16], "texture": "#0"}' }
function BoxEl($from, $to, $rotAxis, $rotAngle) {
  $f = Face 0
  $faces = '"north": ' + $f + ', "east": ' + $f + ', "south": ' + $f + ', "west": ' + $f + ', "up": ' + $f + ', "down": ' + $f
  $s = '{"from": [' + ($from -join ', ') + '], "to": [' + ($to -join ', ') + '], "faces": {' + $faces + '}'
  if ($rotAngle -ne 0) { $s += ', "rotation": {"angle": ' + $rotAngle + ', "axis": "' + $rotAxis + '", "origin": [8, 8, 8]}' }
  return $s + '}'
}
function PlaneEl($axis) {
  $f = Face 0
  switch ($axis) {
    'y' { return '{"from": [0, 8, 0], "to": [16, 8, 16], "faces": {"up": ' + $f + ', "down": ' + $f + '}}' }
    'z' { return '{"from": [0, 0, 8], "to": [16, 16, 8], "faces": {"north": ' + $f + ', "south": ' + $f + '}}' }
    'x' { return '{"from": [8, 0, 0], "to": [8, 16, 16], "faces": {"east": ' + $f + ', "west": ' + $f + '}}' }
  }
}
function WriteModel($name, $texture, $elements) {
  $json = '{"textures": {"0": "gatebound:item/fx/' + $texture + '", "particle": "gatebound:item/fx/' + $texture + '"}, "elements": [' + ($elements -join ', ') + ']}'
  [System.IO.File]::WriteAllText((Join-Path $modelDir "$name.json"), $json, $enc)
  [System.IO.File]::WriteAllText((Join-Path $itemDir "$name.json"), '{"model": {"type": "minecraft:model", "model": "gatebound:item/fx/' + $name + '"}}', $enc)
}

# ------------------------------------------------------------------ run
$seed = 1
foreach ($name in $elements.Keys) {
  $e = $elements[$name]
  DrawCircle $name $e.glow $e.core ($seed * 977)
  DrawRing $name $e.glow $e.core
  WriteModel "circle_$name" "circle_$name" @((PlaneEl 'y'))
  WriteModel "ring_$name" "ring_$name" @((PlaneEl 'y'))
  $seed++
}
DrawFireball
WriteModel 'fireball' 'fireball' @((PlaneEl 'y'), (PlaneEl 'z'), (PlaneEl 'x'))

DrawIce 'ice_shard.png' 235
WriteModel 'ice_shard' 'ice_shard' @(
  (BoxEl @(6, 0, 6) @(10, 10, 10) 'y' 0), (BoxEl @(7, 10, 7) @(9, 16, 9) 'y' 0), (BoxEl @(7.6, 16, 7.6) @(8.4, 18.5, 8.4) 'y' 0),
  (BoxEl @(6, 0, 6) @(10, 10, 10) 'y' 45), (BoxEl @(7, 10, 7) @(9, 16, 9) 'y' 45), (BoxEl @(7.6, 16, 7.6) @(8.4, 18.5, 8.4) 'y' 45))
WriteModel 'ice_spike' 'ice_shard' @(
  (BoxEl @(3.5, 0, 3.5) @(12.5, 5, 12.5) 'y' 0), (BoxEl @(4.8, 5, 4.8) @(11.2, 10, 11.2) 'y' 0), (BoxEl @(6.1, 10, 6.1) @(9.9, 14, 9.9) 'y' 0),
  (BoxEl @(7.2, 14, 7.2) @(8.8, 18, 8.8) 'y' 0), (BoxEl @(3.5, 0, 3.5) @(12.5, 5, 12.5) 'y' 45), (BoxEl @(4.8, 5, 4.8) @(11.2, 10, 11.2) 'y' 45),
  (BoxEl @(6.1, 10, 6.1) @(9.9, 14, 9.9) 'y' 45), (BoxEl @(7.2, 14, 7.2) @(8.8, 18, 8.8) 'y' 45))

# A tall column of light: bright white-gold in the middle, fading to transparent at the sides, with faint streaks.
function DrawBeam($file, $glow, $core) {
  $W = 64; $H = 128
  $bmp = New-Object System.Drawing.Bitmap $W, $H, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  for ($x = 0; $x -lt $W; $x++) {
    $d = [math]::Abs($x - ($W - 1) / 2) / ($W / 2)
    $a = [math]::Pow([math]::Max(0, 1 - $d), 1.5)
    $mix = [math]::Pow([math]::Max(0, 1 - $d * 1.6), 2)
    for ($y = 0; $y -lt $H; $y++) {
      $streak = 0.88 + 0.12 * [math]::Sin($y * 0.35 + $x * 0.5)
      $alpha = [int][math]::Min(255, 255 * $a * $streak)
      $r = [int]($glow[0] + ($core[0] - $glow[0]) * $mix)
      $g = [int]($glow[1] + ($core[1] - $glow[1]) * $mix)
      $b = [int]($glow[2] + ($core[2] - $glow[2]) * $mix)
      $bmp.SetPixel($x, $y, (C $alpha $r $g $b))
    }
  }
  $bmp.Save((Join-Path $texDir $file), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}
DrawBeam 'light_beam.png' $elements['light'].glow $elements['light'].core
WriteModel 'light_beam' 'light_beam' @((PlaneEl 'z'), (PlaneEl 'x'))

DrawMeteor
WriteModel 'meteor' 'meteor' @(
  (BoxEl @(3, 3, 3) @(13, 13, 13) 'y' 0), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'y' 45), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'x' 45),
  (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'z' 45), (BoxEl @(2, 5, 5) @(14, 11, 11) 'y' 22.5), (BoxEl @(5, 2, 5) @(11, 14, 11) 'x' 22.5))

# ------------------------------------------------------------------ warrior set
function SavePng($cv, $file) {
  $cv.g.Dispose()
  $cv.bmp.Save((Join-Path $texDir $file), [System.Drawing.Imaging.ImageFormat]::Png)
  $cv.bmp.Dispose()
}
function PtArr($list) { return [System.Drawing.PointF[]]$list }

# A crescent slash: stacked arcs, thin and faint at the inside, bright at the outer edge, fading out at both tips.
function DrawSlash($file, $glow, $core) {
  $cv = NewCanvas 128; $g = $cv.g
  for ($k = 0; $k -lt 9; $k++) {
    $r = 40 + $k * 2.4; $cy = 92 - $k * 0.6
    $pts = @()
    for ($i = 0; $i -le 40; $i++) { $a = [math]::PI * (1.12 + 0.76 * $i / 40); $pts += , (Pt (64 + [math]::Cos($a) * $r * 1.25) ($cy + [math]::Sin($a) * $r)) }
    $alpha = [int](25 + $k * 22)
    $pen = New-Object System.Drawing.Pen (C $alpha $glow[0] $glow[1] $glow[2]), ([single]3.2)
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  }
  $pts = @()
  for ($i = 0; $i -le 40; $i++) { $a = [math]::PI * (1.14 + 0.72 * $i / 40); $pts += , (Pt (64 + [math]::Cos($a) * 59 * 1.25) (91.5 + [math]::Sin($a) * 59)) }
  $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]2.2)
  $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
  $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  SavePng $cv $file
}

# Ground cracks: dark fissures radiating from the centre with a hot ember line inside.
function DrawCrack($file, $glow, $core) {
  $cv = NewCanvas 256; $g = $cv.g; $c = 128
  $rng = New-Object System.Random 31
  for ($i = 0; $i -lt 11; $i++) {
    $a = 2 * [math]::PI * $i / 11 + $rng.NextDouble() * 0.3
    $x = $c; $y = $c; $pts = @((Pt $x $y)); $len = 0
    $maxLen = 90 + $rng.Next(0, 34)
    while ($len -lt $maxLen) {
      $step = 9 + $rng.Next(0, 9); $a += ($rng.NextDouble() - 0.5) * 0.7
      $x += [math]::Cos($a) * $step; $y += [math]::Sin($a) * $step; $len += $step
      $pts += , (Pt $x $y)
      if ($rng.Next(0, 5) -eq 0) {
        $ba = $a + ($rng.NextDouble() - 0.5) * 1.6; $bx = $x + [math]::Cos($ba) * 22; $by = $y + [math]::Sin($ba) * 22
        $pen = New-Object System.Drawing.Pen (C 220 25 18 14), ([single]2.2)
        $g.DrawLine($pen, [single]$x, [single]$y, [single]$bx, [single]$by); $pen.Dispose()
      }
    }
    $pen = New-Object System.Drawing.Pen (C 230 25 18 14), ([single]6); $pen.LineJoin = 'Round'; $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
    $pen = New-Object System.Drawing.Pen (C 190 $glow[0] $glow[1] $glow[2]), ([single]2.4); $pen.LineJoin = 'Round'
    $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
    $pen = New-Object System.Drawing.Pen (C 220 $core[0] $core[1] $core[2]), ([single]0.9)
    $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  }
  SavePng $cv $file
}

# A spectral greatsword, tip up: ghostly red-white blade with a fuller, a crossguard and a grip.
function DrawSword($file, $glow, $core) {
  $cv = NewCanvas 128; $g = $cv.g
  $br = New-Object System.Drawing.SolidBrush (C 70 $glow[0] $glow[1] $glow[2])
  $g.FillPolygon($br, (PtArr @((Pt 64 2), (Pt 80 24), (Pt 80 92), (Pt 48 92), (Pt 48 24)))); $br.Dispose()
  $br = New-Object System.Drawing.SolidBrush (C 220 $core[0] $core[1] $core[2])
  $g.FillPolygon($br, (PtArr @((Pt 64 8), (Pt 74 28), (Pt 74 90), (Pt 54 90), (Pt 54 28)))); $br.Dispose()
  $pen = New-Object System.Drawing.Pen (C 255 $glow[0] $glow[1] $glow[2]), ([single]2)
  $g.DrawPolygon($pen, (PtArr @((Pt 64 8), (Pt 74 28), (Pt 74 90), (Pt 54 90), (Pt 54 28)))); $pen.Dispose()
  GlowLines $g (PtArr @((Pt 64 24), (Pt 64 86))) $glow $core 0.8
  $br = New-Object System.Drawing.SolidBrush (C 255 $glow[0] $glow[1] $glow[2])
  $g.FillRectangle($br, 30, 90, 68, 8); $br.Dispose()
  $br = New-Object System.Drawing.SolidBrush (C 255 $core[0] $core[1] $core[2])
  $g.FillRectangle($br, 34, 92, 60, 3); $br.Dispose()
  $br = New-Object System.Drawing.SolidBrush (C 235 70 24 20)
  $g.FillRectangle($br, 59, 98, 10, 22); $g.FillEllipse($br, 57, 118, 14, 9); $br.Dispose()
  SavePng $cv $file
}

# A heater shield sigil with a glowing sword emblem, drawn upright.
function DrawShieldSigil($file, $glow, $core) {
  $cv = NewCanvas 256; $g = $cv.g
  $outline = @((Pt 128 14), (Pt 214 38), (Pt 214 128), (Pt 190 184), (Pt 128 240), (Pt 66 184), (Pt 42 128), (Pt 42 38), (Pt 128 14))
  $br = New-Object System.Drawing.SolidBrush (C 55 $glow[0] $glow[1] $glow[2])
  $g.FillPolygon($br, (PtArr $outline)); $br.Dispose()
  GlowLines $g (PtArr $outline) $glow $core 1.6
  $inner = @((Pt 128 34), (Pt 196 52), (Pt 196 126), (Pt 176 172), (Pt 128 218), (Pt 80 172), (Pt 60 126), (Pt 60 52), (Pt 128 34))
  GlowLines $g (PtArr $inner) $glow $core 0.7
  GlowLines $g (PtArr @((Pt 128 62), (Pt 128 180))) $glow $core 1.4
  GlowLines $g (PtArr @((Pt 100 92), (Pt 156 92))) $glow $core 1.4
  GlowLines $g (PtArr @((Pt 128 62), (Pt 118 74), (Pt 138 74), (Pt 128 62))) $glow $core 0.8
  SavePng $cv $file
}

# Brushed steel with rivets and a hot rim.
function DrawSteel($file) {
  $cv = NewCanvas 64; $g = $cv.g
  $rect = New-Object System.Drawing.Rectangle 0, 0, 64, 64
  $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (C 255 190 198 208), (C 255 92 100 112), 90.0
  $g.FillRectangle($br, $rect); $br.Dispose()
  $rng = New-Object System.Random 5
  for ($i = 0; $i -lt 40; $i++) { $y = $rng.Next(0, 64); $pen = New-Object System.Drawing.Pen (C 60 255 255 255), ([single]1); $g.DrawLine($pen, 0, $y, 64, $y); $pen.Dispose() }
  $pen = New-Object System.Drawing.Pen (C 255 210 80 40), ([single]3); $g.DrawRectangle($pen, 1, 1, 61, 61); $pen.Dispose()
  foreach ($pt in @(@(8, 8), @(52, 8), @(8, 52), @(52, 52))) { $br = New-Object System.Drawing.SolidBrush (C 255 60 66 76); $g.FillEllipse($br, $pt[0], $pt[1], 5, 5); $br.Dispose() }
  SavePng $cv $file
}

# A ring of eight blades, tips swept round, lying flat.
function DrawBladeRing($file, $glow, $core) {
  $cv = NewCanvas 256; $g = $cv.g; $c = 128
  GlowCircle $g $c $c 100 $glow $core 0.6
  for ($i = 0; $i -lt 8; $i++) {
    $a = 2 * [math]::PI * $i / 8
    $pts = @()
    foreach ($f in @(@(-0.20, 96), @(0.0, 118), @(0.34, 100), @(0.0, 106), @(-0.20, 96))) {
      $aa = $a + $f[0]; $pts += , (Pt ($c + [math]::Cos($aa) * $f[1]) ($c + [math]::Sin($aa) * $f[1]))
    }
    $br = New-Object System.Drawing.SolidBrush (C 215 $core[0] $core[1] $core[2])
    $g.FillPolygon($br, (PtArr $pts)); $br.Dispose()
    GlowLines $g (PtArr $pts) $glow $core 0.9
  }
  SavePng $cv $file
}

# Cracked grey-brown stone for spikes.
function DrawStone($file) {
  $cv = NewCanvas 64; $g = $cv.g
  $rng = New-Object System.Random 17
  $g.Clear((C 255 104 98 92))
  for ($i = 0; $i -lt 260; $i++) {
    $x = $rng.Next(0, 64); $y = $rng.Next(0, 64); $v = $rng.Next(-26, 26)
    $br = New-Object System.Drawing.SolidBrush (C 255 (104 + $v) (98 + $v) (92 + $v)); $g.FillRectangle($br, $x, $y, 3, 3); $br.Dispose()
  }
  for ($i = 0; $i -lt 5; $i++) {
    $x = $rng.Next(0, 64); $y = $rng.Next(0, 64); $pts = @((Pt $x $y))
    for ($k = 0; $k -lt 4; $k++) { $x += $rng.Next(-12, 13); $y += $rng.Next(-12, 13); $pts += , (Pt $x $y) }
    $pen = New-Object System.Drawing.Pen (C 255 40 36 34), ([single]1.4); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  }
  SavePng $cv $file
}

$w = $elements['warrior']
DrawSlash 'slash.png' $w.glow $w.core
WriteModel 'slash' 'slash' @((PlaneEl 'y'))
DrawCrack 'crack.png' $w.glow $w.core
WriteModel 'crack' 'crack' @((PlaneEl 'y'))
DrawSword 'sword.png' $w.glow $w.core
WriteModel 'sword' 'sword' @((PlaneEl 'z'), (PlaneEl 'x'))
DrawShieldSigil 'shield_sigil.png' $w.glow $w.core
WriteModel 'shield_sigil' 'shield_sigil' @((PlaneEl 'z'))
DrawSteel 'steel_plate.png'
WriteModel 'steel_plate' 'steel_plate' @((BoxEl @(5, 3, 7) @(11, 13, 9) 'y' 0), (BoxEl @(6, 4, 6.4) @(10, 12, 7) 'y' 0))
DrawBladeRing 'blade_ring.png' $w.glow $w.core
WriteModel 'blade_ring' 'blade_ring' @((PlaneEl 'y'))
DrawStone 'stone_spike.png'
WriteModel 'stone_spike' 'stone_spike' @(
  (BoxEl @(3.5, 0, 3.5) @(12.5, 5, 12.5) 'y' 15), (BoxEl @(4.8, 5, 4.8) @(11.2, 10, 11.2) 'y' 15), (BoxEl @(6.1, 10, 6.1) @(9.9, 14, 9.9) 'y' 15),
  (BoxEl @(7.2, 14, 7.2) @(8.8, 18, 8.8) 'y' 15), (BoxEl @(4, 0, 4) @(12, 4, 12) 'y' 50), (BoxEl @(5.5, 4, 5.5) @(10.5, 9, 10.5) 'y' 50))

# ------------------------------------------------------------------ mage form set (attachments and body projectiles)
function Rad($d) { return $d * [math]::PI / 180 }

# Two wings spread behind the body (256x256, symmetric). Styles: feather (white-gold), flame, membrane (bat), wind (streaks).
function DrawWings($file, $glow, $core, $fill, $style) {
  $cv = NewCanvas 256; $g = $cv.g
  for ($side = 0; $side -lt 2; $side++) {
    $g.ResetTransform()
    if ($side -eq 1) { $g.TranslateTransform(256, 0); $g.ScaleTransform(-1, 1) }
    $rx = 138; $ry = 150
    $tips = @()
    for ($i = 0; $i -lt 7; $i++) {
      $a = Rad (-82 + $i * 14.5)
      $len = @(78, 100, 118, 124, 116, 100, 82)[$i]
      $dx = [math]::Cos($a); $dy = [math]::Sin($a)
      $tx = $rx + $dx * $len; $ty = $ry + $dy * $len
      $tips += , (Pt $tx $ty)
      $px = - $dy; $py = $dx
      $w = 13
      $mx = $rx + $dx * $len * 0.5; $my = $ry + $dy * $len * 0.5
      $poly = PtArr @((Pt $rx $ry), (Pt ($mx + $px * $w) ($my + $py * $w)), (Pt $tx $ty), (Pt ($mx - $px * $w) ($my - $py * $w)))
      switch ($style) {
        'feather'  { $br = New-Object System.Drawing.SolidBrush (C 235 $fill[0] $fill[1] $fill[2]); $g.FillPolygon($br, $poly); $br.Dispose()
                     $pen = New-Object System.Drawing.Pen (C 255 $glow[0] $glow[1] $glow[2]), ([single]1.6); $g.DrawPolygon($pen, $poly); $pen.Dispose()
                     GlowLines $g (PtArr @((Pt $rx $ry), (Pt $tx $ty))) $glow $core 0.5 }
        'flame'    { $br = New-Object System.Drawing.SolidBrush (C 190 $glow[0] $glow[1] $glow[2]); $g.FillPolygon($br, $poly); $br.Dispose()
                     $inner = PtArr @((Pt $rx $ry), (Pt ($mx + $px * $w * 0.4) ($my + $py * $w * 0.4)), (Pt ($rx + $dx * $len * 0.85) ($ry + $dy * $len * 0.85)), (Pt ($mx - $px * $w * 0.4) ($my - $py * $w * 0.4)))
                     $br = New-Object System.Drawing.SolidBrush (C 235 $core[0] $core[1] $core[2]); $g.FillPolygon($br, $inner); $br.Dispose() }
        'wind'     { for ($k = -1; $k -le 1; $k++) {
                       $pts = @()
                       for ($t = 0; $t -le 12; $t++) { $f = $t / 12.0; $pts += , (Pt ($rx + $dx * $len * $f + $px * ($k * 7 + [math]::Sin($f * 5 + $i) * 4)) ($ry + $dy * $len * $f + $py * ($k * 7 + [math]::Sin($f * 5 + $i) * 4))) }
                       GlowLines $g (PtArr $pts) $glow $core 0.7 } }
      }
    }
    if ($style -eq 'membrane') {
      $pts = @((Pt $rx $ry))
      for ($i = 0; $i -lt 7; $i++) {
        $pts += , $tips[$i]
        if ($i -lt 6) { $m = Pt (($tips[$i].X + $tips[$i + 1].X) / 2 * 0.86 + $rx * 0.14) (($tips[$i].Y + $tips[$i + 1].Y) / 2 * 0.86 + $ry * 0.14); $pts += , $m }
      }
      $br = New-Object System.Drawing.SolidBrush (C 235 $fill[0] $fill[1] $fill[2]); $g.FillPolygon($br, (PtArr $pts)); $br.Dispose()
      for ($i = 0; $i -lt 7; $i++) { GlowLines $g (PtArr @((Pt $rx $ry), $tips[$i])) $glow $core 0.9 }
      GlowLines $g (PtArr $pts) $glow $core 0.5
    }
    # the shoulder bone
    $g.ResetTransform()
  }
  SavePng $cv $file
}

# A flat ring with spikes, for the halo above the head (128x128, lying flat).
function DrawHalo($file, $glow, $core, $spikes) {
  $cv = NewCanvas 128; $g = $cv.g; $c = 64
  GlowCircle $g $c $c 50 $glow $core 1.8
  GlowCircle $g $c $c 42 $glow $core 0.7
  for ($i = 0; $i -lt $spikes; $i++) {
    $a = 2 * [math]::PI * $i / $spikes
    GlowLines $g (PtArr @((Pt ($c + [math]::Cos($a) * 50) ($c + [math]::Sin($a) * 50)), (Pt ($c + [math]::Cos($a) * 62) ($c + [math]::Sin($a) * 62)))) $glow $core 0.9
  }
  SavePng $cv $file
}

# A tapered curved horn pair (256x128), for the head.
function DrawHorns($file, $glow, $core) {
  $cv = NewCanvas 256; $g = $cv.g
  for ($side = 0; $side -lt 2; $side++) {
    $g.ResetTransform()
    if ($side -eq 1) { $g.TranslateTransform(256, 0); $g.ScaleTransform(-1, 1) }
    $prev = $null
    for ($t = 0; $t -le 20; $t++) {
      $f = $t / 20.0
      $x = 100 - $f * 62 + [math]::Sin($f * 3.1) * 20; $y = 118 - $f * 100
      $cur = Pt $x $y
      if ($prev -ne $null) {
        $w = 22 * (1 - $f) + 3
        $pen = New-Object System.Drawing.Pen (C 255 $glow[0] $glow[1] $glow[2]), ([single]($w + 5)); $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
        $g.DrawLine($pen, $prev, $cur); $pen.Dispose()
        $pen = New-Object System.Drawing.Pen (C 255 62 16 10), ([single]$w); $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
        $g.DrawLine($pen, $prev, $cur); $pen.Dispose()
        $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]($w * 0.18)); $g.DrawLine($pen, $prev, $cur); $pen.Dispose()
      }
      $prev = $cur
    }
    $g.ResetTransform()
  }
  SavePng $cv $file
}

# A jagged crown of crystal spikes (256x128).
function DrawCrown($file, $glow, $core) {
  $cv = NewCanvas 256; $g = $cv.g
  $hs = @(60, 34, 84, 18, 100, 8, 88, 24, 62)
  $base = 116; $pts = @((Pt 24 $base))
  for ($i = 0; $i -lt 9; $i++) {
    $x0 = 24 + $i * 26; $x1 = $x0 + 26
    $pts += , (Pt ($x0 + 13) (118 - ($hs[$i] + 8)))
    $pts += , (Pt $x1 ($base - 12))
  }
  $pts += , (Pt 232 $base)
  $br = New-Object System.Drawing.SolidBrush (C 225 150 220 255); $g.FillPolygon($br, (PtArr $pts)); $br.Dispose()
  GlowLines $g (PtArr $pts) $glow $core 1.1
  for ($i = 0; $i -lt 9; $i++) { $x = 37 + $i * 26; GlowLines $g (PtArr @((Pt $x $base), (Pt $x (118 - ($hs[$i] + 8))))) $glow $core 0.5 }
  SavePng $cv $file
}

# A vertical jagged strip (64x128) for lightning bolts, with side branches.
function DrawBoltStrip($file, $glow, $core) {
  $cv = NewCanvas 128; $g = $cv.g
  $rng = New-Object System.Random 3
  $x = 64; $pts = @((Pt 64 0))
  for ($y = 12; $y -lt 128; $y += 12) { $x = 64 + $rng.Next(-16, 17); $pts += , (Pt $x $y) }
  $pts += , (Pt 64 128)
  GlowLines $g (PtArr $pts) $glow $core 2.2
  for ($i = 1; $i -lt $pts.Count - 1; $i += 2) {
    $b = $pts[$i]; $dir = if ($rng.Next(0, 2) -eq 0) { -1 } else { 1 }
    GlowLines $g (PtArr @($b, (Pt ($b.X + $dir * 18) ($b.Y + 14)), (Pt ($b.X + $dir * 28) ($b.Y + 30)))) $glow $core 1.0
  }
  SavePng $cv $file
}

# A wavy dark tendril strip (128x128).
function DrawTendril($file, $glow, $core) {
  $cv = NewCanvas 128; $g = $cv.g
  $pts = @()
  for ($y = 0; $y -le 128; $y += 4) { $pts += , (Pt (64 + [math]::Sin($y * 0.09) * 22) $y) }
  $pen = New-Object System.Drawing.Pen (C 150 $glow[0] $glow[1] $glow[2]), ([single]22); $pen.StartCap = 'Round'; $pen.EndCap = 'Round'; $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  $pen = New-Object System.Drawing.Pen (C 255 22 8 34), ([single]13); $pen.StartCap = 'Round'; $pen.EndCap = 'Round'; $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]1.6); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  SavePng $cv $file
}

# Stone with glowing seams for boulders (64x64).
function DrawBoulder($file, $glow, $core) {
  $cv = NewCanvas 64; $g = $cv.g
  $rng = New-Object System.Random 23
  $g.Clear((C 255 96 90 84))
  for ($i = 0; $i -lt 240; $i++) {
    $x = $rng.Next(0, 64); $y = $rng.Next(0, 64); $v = $rng.Next(-24, 24)
    $br = New-Object System.Drawing.SolidBrush (C 255 (96 + $v) (90 + $v) (84 + $v)); $g.FillRectangle($br, $x, $y, 3, 3); $br.Dispose()
  }
  for ($i = 0; $i -lt 7; $i++) {
    $x = $rng.Next(0, 64); $y = $rng.Next(0, 64); $pts = @((Pt $x $y))
    for ($k = 0; $k -lt 4; $k++) { $x += $rng.Next(-13, 14); $y += $rng.Next(-13, 14); $pts += , (Pt $x $y) }
    $pen = New-Object System.Drawing.Pen (C 255 $glow[0] $glow[1] $glow[2]), ([single]2.4); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
    $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]0.9); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  }
  SavePng $cv $file
}

# A swirling dark orb (64x64).
function DrawVoid($file, $glow, $core) {
  $cv = NewCanvas 64; $g = $cv.g
  $g.Clear((C 255 18 6 30))
  for ($arm = 0; $arm -lt 3; $arm++) {
    $pts = @()
    for ($t = 0; $t -le 30; $t++) { $r = 3 + $t * 1.0; $a = $arm * 2.1 + $t * 0.22; $pts += , (Pt (32 + [math]::Cos($a) * $r) (32 + [math]::Sin($a) * $r)) }
    $pen = New-Object System.Drawing.Pen (C 255 $glow[0] $glow[1] $glow[2]), ([single]3); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
    $pen = New-Object System.Drawing.Pen (C 255 $core[0] $core[1] $core[2]), ([single]1); $g.DrawLines($pen, (PtArr $pts)); $pen.Dispose()
  }
  $br = New-Object System.Drawing.SolidBrush (C 255 4 0 10); $g.FillEllipse($br, 26, 26, 12, 12); $br.Dispose()
  SavePng $cv $file
}

$fe = $elements['fire']; $ic = $elements['ice']; $li = $elements['lightning']; $wi = $elements['wind']
$ea = $elements['earth']; $lg = $elements['light']; $da = $elements['dark']

DrawWings 'wings_fire.png' $fe.glow $fe.core @(255, 140, 40) 'flame'
DrawWings 'wings_wind.png' $wi.glow $wi.core @(255, 255, 255) 'wind'
DrawWings 'wings_light.png' $lg.glow $lg.core @(255, 250, 230) 'feather'
DrawWings 'wings_dark.png' $da.glow $da.core @(28, 10, 44) 'membrane'
foreach ($n in @('fire', 'wind', 'light', 'dark')) { WriteModel "wings_$n" "wings_$n" @((PlaneEl 'z')) }

DrawHalo 'halo_lightning.png' $li.glow $li.core 12
DrawHalo 'halo_light.png' $lg.glow $lg.core 16
DrawHalo 'halo_dark.png' $da.glow $da.core 8
foreach ($n in @('lightning', 'light', 'dark')) { WriteModel "halo_$n" "halo_$n" @((PlaneEl 'y')) }

DrawHorns 'horns_fire.png' $fe.glow $fe.core
WriteModel 'horns_fire' 'horns_fire' @((PlaneEl 'z'), (PlaneEl 'x'))
DrawCrown 'crown_ice.png' $ic.glow $ic.core
WriteModel 'crown_ice' 'crown_ice' @((PlaneEl 'z'), (PlaneEl 'x'), (BoxEl @(0, 0, 7.95) @(16, 16, 8.05) 'y' 45), (BoxEl @(0, 0, 7.95) @(16, 16, 8.05) 'y' -45))

DrawBoltStrip 'bolt.png' $li.glow $li.core
WriteModel 'bolt' 'bolt' @((PlaneEl 'z'), (PlaneEl 'x'))
DrawTendril 'tendril.png' $da.glow $da.core
WriteModel 'tendril' 'tendril' @((PlaneEl 'z'), (PlaneEl 'x'))
DrawBoulder 'boulder.png' $ea.glow $ea.core
WriteModel 'boulder' 'boulder' @(
  (BoxEl @(3, 3, 3) @(13, 13, 13) 'y' 0), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'y' 45), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'x' 45),
  (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'z' 45), (BoxEl @(2, 5, 5) @(14, 11, 11) 'y' 22.5))
DrawVoid 'void_sphere.png' $da.glow $da.core
WriteModel 'void_sphere' 'void_sphere' @(
  (BoxEl @(3, 3, 3) @(13, 13, 13) 'y' 0), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'y' 45), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'x' 45),
  (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'z' 45))
DrawSlash 'wind_blade.png' $wi.glow $wi.core
WriteModel 'wind_blade' 'wind_blade' @((PlaneEl 'y'))


# ------------------------------------------------------------------ wings for the last three forms, and the wind tornado
DrawWings 'wings_ice.png' $ic.glow $ic.core @(185, 228, 255) 'feather'
DrawWings 'wings_lightning.png' $li.glow $li.core @(255, 236, 90) 'flame'
DrawWings 'wings_earth.png' $ea.glow $ea.core @(98, 92, 86) 'membrane'
foreach ($n in @('ice', 'lightning', 'earth')) { WriteModel "wings_$n" "wings_$n" @((PlaneEl 'z')) }

# Stacked flared rings of wind (the ring_wind texture), narrow at the bottom and wide at the top.
function RingPlane($y, $half) {
  $f = Face 0
  return '{"from": [' + (8 - $half) + ', ' + $y + ', ' + (8 - $half) + '], "to": [' + (8 + $half) + ', ' + $y + ', ' + (8 + $half) + '], "faces": {"up": ' + $f + ', "down": ' + $f + '}}'
}
WriteModel 'tornado' 'ring_wind' @((RingPlane 0.5 2.5), (RingPlane 3 3.4), (RingPlane 5.5 4.3), (RingPlane 8 5.3), (RingPlane 10.5 6.3), (RingPlane 13 7.4), (RingPlane 15.5 8))

# ------------------------------------------------------------------ Avatar of War wings
DrawWings 'wings_warrior.png' $elements['warrior'].glow $elements['warrior'].core @(112, 118, 130) 'membrane'
WriteModel 'wings_warrior' 'wings_warrior' @((PlaneEl 'z'))

# ------------------------------------------------------------------ mage form body skins (64x64 player skin layout, 4 animation frames each)
# Only the base-layer regions are painted; the outer-layer regions stay transparent, so the overlay never hides hats, jackets or sleeves.
$formDir = Join-Path $root 'textures\entity\form'
New-Item -ItemType Directory -Force $formDir | Out-Null

function Hash2($x, $y, $s) {
  $v = [math]::Sin($x * 12.9898 + $y * 78.233 + $s * 37.719) * 43758.5453
  return $v - [math]::Floor($v)
}
function Mix($a, $b, $t) {
  $t = [math]::Max(0, [math]::Min(1, $t))
  return @([int]($a[0] + ($b[0] - $a[0]) * $t), [int]($a[1] + ($b[1] - $a[1]) * $t), [int]($a[2] + ($b[2] - $a[2]) * $t))
}

# One pixel of one element's skin at animation phase $f (0 to 3).
function SkinPixel($el, $x, $y, $f) {
  $ph = $f * [math]::PI / 2
  switch ($el) {
    'fire' {
      $h = 0.5 + 0.5 * [math]::Sin($y * 0.62 - $ph * 1.0 + [math]::Sin($x * 0.8 + $ph) * 1.6) + 0.25 * (Hash2 $x $y $f) - 0.12
      if ($h -lt 0.5) { return Mix @(110, 18, 6) @(255, 100, 20) ($h * 2) }
      return Mix @(255, 100, 20) @(255, 232, 130) (($h - 0.5) * 2)
    }
    'ice' {
      $cell = Hash2 ([math]::Floor($x / 4)) ([math]::Floor($y / 4)) 7
      $c = Mix @(120, 190, 240) @(215, 244, 255) $cell
      if ((Hash2 $x $y ($f + 3)) -gt 0.955) { return @(255, 255, 255) }
      return $c
    }
    'lightning' {
      $line = [math]::Abs([math]::Sin($x * 0.75 + $f * 1.9 + [math]::Sin($y * 0.45 + $f) * 2.2))
      if ($line -lt 0.1) { return @(255, 250, 170) }
      if ($line -lt 0.28) { return Mix @(255, 230, 90) @(30, 40, 110) (($line - 0.1) / 0.18) }
      return Mix @(14, 20, 62) @(34, 48, 120) (Hash2 $x $y $f)
    }
    'wind' {
      $s = 0.5 + 0.5 * [math]::Sin($x * 0.95 + $y * 0.25 - $ph * 1.5 + [math]::Sin($y * 0.6) * 1.2)
      return Mix @(150, 225, 195) @(250, 255, 252) $s
    }
    'earth' {
      $base = Mix @(84, 78, 72) @(132, 124, 114) (Hash2 ([math]::Floor($x / 2)) ([math]::Floor($y / 2)) 5)
      $crack = [math]::Sin($x * 0.62 + [math]::Sin($y * 0.5) * 2.0) * [math]::Sin($y * 0.7 + [math]::Sin($x * 0.4) * 2.0)
      if ($crack -gt 0.86) { return Mix @(255, 140, 40) @(255, 230, 120) ($f / 3.0) }
      return $base
    }
    'light' {
      $d = [math]::Abs([math]::Sin(($x + $y) * 0.35 - $ph))
      $c = Mix @(255, 232, 150) @(255, 253, 232) (1 - $d)
      if ((Hash2 $x $y ($f + 9)) -gt 0.97) { return @(255, 255, 255) }
      return $c
    }
    'warrior' {
      if (($x % 8) -eq 0 -or ($y % 8) -eq 0) { return Mix @(200, 50, 24) @(255, 190, 80) (0.5 + 0.5 * [math]::Sin($ph + $x * 0.3)) }
      return Mix @(66, 72, 84) @(150, 158, 172) (Hash2 ([math]::Floor($x / 2)) ([math]::Floor($y / 2)) 3)
    }
    'dark' {
      $s = 0.5 + 0.5 * [math]::Sin(($x + $y) * 0.5 - $ph * 1.2 + [math]::Sin($x * 0.5) * 1.4)
      return Mix @(12, 4, 22) @(120, 46, 190) ([math]::Pow($s, 3))
    }
  }
}

function DrawSkin($el, $f, $eye) {
  $bmp = New-Object System.Drawing.Bitmap 64, 64, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $regions = @(@(0, 0, 32, 16), @(16, 16, 24, 16), @(40, 16, 16, 16), @(0, 16, 16, 16), @(32, 48, 16, 16), @(16, 48, 16, 16))
  foreach ($r in $regions) {
    for ($y = $r[1]; $y -lt $r[1] + $r[3]; $y++) {
      for ($x = $r[0]; $x -lt $r[0] + $r[2]; $x++) {
        $c = SkinPixel $el $x $y $f
        $bmp.SetPixel($x, $y, (C 255 $c[0] $c[1] $c[2]))
      }
    }
  }
  # glowing eyes on the face (the head's front is the 8x8 block at 8,8)
  foreach ($ex in @(9, 10, 13, 14)) { foreach ($ey in @(12, 13)) { $bmp.SetPixel($ex, $ey, (C 255 $eye[0] $eye[1] $eye[2])) } }
  $bmp.Save((Join-Path $formDir ("{0}_{1}.png" -f $el, $f)), [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

$eyes = @{ fire = @(255, 255, 220); ice = @(255, 255, 255); lightning = @(255, 255, 255); wind = @(70, 150, 120); earth = @(255, 200, 80); warrior = @(255, 210, 90); light = @(255, 255, 255); dark = @(255, 60, 230) }
foreach ($el in @('fire', 'ice', 'lightning', 'wind', 'earth', 'light', 'dark', 'warrior')) {
  for ($f = 0; $f -lt 4; $f++) { DrawSkin $el $f $eyes[$el] }
}
Write-Output 'done'
