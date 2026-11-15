# Draws the WhisperAsh icon (dark circle, light ring, white waveform bars) at
# every size the app needs and writes icon.png (1024) and icon.ico (16-256).
# Re-runnable:  powershell -ExecutionPolicy Bypass -File scripts\whisperash-make-icons.ps1
param([string]$OutDir = (Join-Path $PSScriptRoot '..\src\assets'))
Add-Type -AssemblyName System.Drawing

function New-IconBitmap([int]$size) {
  $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
  $g.Clear([System.Drawing.Color]::Transparent)

  # Full-bleed dark circle (so the taskbar shows a big round icon, like Chrome).
  $pad = [Math]::Max(0.5, $size * 0.02)
  $d = $size - 2 * $pad
  $bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 24, 24, 27))
  $g.FillEllipse($bg, $pad, $pad, $d, $d)

  # Light ring, as on the pill button.
  if ($size -ge 24) {
    $ringW = [Math]::Max(1.0, $size * 0.045)
    $ringInset = $size * 0.20
    $ring = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(235, 255, 255, 255)), $ringW
    $g.DrawEllipse($ring, $ringInset, $ringInset, $size - 2 * $ringInset, $size - 2 * $ringInset)
  }

  # Waveform bars (short, tall, medium) in the centre.
  $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
  $barW = [Math]::Max(1.5, $size * ($(if ($size -ge 24) { 0.055 } else { 0.10 })))
  $gap = $barW * 0.9
  $heights = @(0.16, 0.30, 0.20)
  if ($size -lt 24) { $heights = @(0.30, 0.52, 0.36) }
  $total = 3 * $barW + 2 * $gap
  $x = ($size - $total) / 2
  foreach ($h in $heights) {
    $bh = $size * $h
    $y = ($size - $bh) / 2
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $r = $barW
    $path.AddArc($x, $y, $r, $r, 180, 180)
    $path.AddArc($x, $y + $bh - $r, $r, $r, 0, 180)
    $path.CloseFigure()
    $g.FillPath($white, $path)
    $x += $barW + $gap
  }
  $g.Dispose()
  return $bmp
}

function Get-PngBytes($bmp) {
  $ms = New-Object System.IO.MemoryStream
  $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
  return ,$ms.ToArray()
}

$big = New-IconBitmap 1024
$big.Save((Join-Path $OutDir 'icon.png'), [System.Drawing.Imaging.ImageFormat]::Png)
"wrote icon.png"

# ICO with PNG-compressed entries (supported since Windows Vista).
$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$images = foreach ($s in $sizes) { ,(Get-PngBytes (New-IconBitmap $s)) }
$fs = [System.IO.File]::Create((Join-Path $OutDir 'icon.ico'))
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
  $s = $sizes[$i]; $len = $images[$i].Length
  $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
  $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
  $bw.Write([byte]0); $bw.Write([byte]0)
  $bw.Write([UInt16]1); $bw.Write([UInt16]32)
  $bw.Write([UInt32]$len); $bw.Write([UInt32]$offset)
  $offset += $len
}
foreach ($img in $images) { $bw.Write($img) }
$bw.Close()
"wrote icon.ico ($($sizes -join ', ') px)"
