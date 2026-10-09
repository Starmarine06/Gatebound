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

DrawMeteor
WriteModel 'meteor' 'meteor' @(
  (BoxEl @(3, 3, 3) @(13, 13, 13) 'y' 0), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'y' 45), (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'x' 45),
  (BoxEl @(3.5, 3.5, 3.5) @(12.5, 12.5, 12.5) 'z' 45), (BoxEl @(2, 5, 5) @(14, 11, 11) 'y' 22.5), (BoxEl @(5, 2, 5) @(11, 14, 11) 'x' 22.5))
Write-Output 'done'
