# 生成 UnU-Player 图标资源: Windows ICO(多尺寸 PNG 条目) + Android adaptive icon 前景(各密度)
# 依赖: .NET System.Drawing(Windows 内置, 无需额外安装)
# 用法: powershell -ExecutionPolicy Bypass -File scripts/windows/generate-icons.ps1
# 源图: img/icon.png(465x465 RGBA 透明底)。产物提交进仓库, 脚本仅作重新生成入口。

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path "$PSScriptRoot\..\..").Path
$sourcePng = Join-Path $repoRoot "img\icon.png"
$icoOutDir = Join-Path $repoRoot "desktopApp\icons"
$icoOutPath = Join-Path $icoOutDir "icon.ico"
$resRoot = Join-Path $repoRoot "androidApp\src\main\res"

if (-not (Test-Path $sourcePng)) {
    throw "源图标不存在: $sourcePng"
}

Add-Type -AssemblyName System.Drawing

# 读取源图(保持 32bit ARGB, 透明底)
$src = [System.Drawing.Image]::FromFile($sourcePng)

function New-SizedBitmap([int]$size, [System.Drawing.Image]$image) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $bmp.SetResolution(96.0, 96.0)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($image, 0, 0, $size, $size)
    $g.Dispose()
    return $bmp
}

function Save-PngBytes([System.Drawing.Bitmap]$bmp) {
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bytes = $ms.ToArray()
    $ms.Dispose()
    return $bytes
}

# ---------- 1. Windows ICO(多尺寸, PNG 条目; Vista+ 通用) ----------
$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
$pngBytesList = @()
foreach ($s in $icoSizes) {
    $bmp = New-SizedBitmap $s $src
    $pngBytesList += ,(Save-PngBytes $bmp)
    $bmp.Dispose()
}

$count = $pngBytesList.Count
$headerSize = 6
$entrySize = 16
$dataOffset = $headerSize + $entrySize * $count

$out = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $out
# ICONDIR
$bw.Write([uint16]0)        # reserved
$bw.Write([uint16]1)        # type = 1(ICO)
$bw.Write([uint16]$count)   # image count
# ICONDIRENTRY × count
for ($i = 0; $i -lt $count; $i++) {
    $sz = $icoSizes[$i]
    [byte[]]$bytes = $pngBytesList[$i]
    $dim = if ($sz -ge 256) { [byte]0 } else { [byte]$sz }
    $bw.Write([byte]$dim)              # width(0 表示 256)
    $bw.Write([byte]$dim)              # height(0 表示 256)
    $bw.Write([byte]0)                 # colorCount
    $bw.Write([byte]0)                 # reserved
    $bw.Write([uint16]1)               # planes
    $bw.Write([uint16]32)              # bitCount
    $bw.Write([uint32]$bytes.Length)   # bytesInRes
    $bw.Write([uint32]$dataOffset)     # imageOffset
    $dataOffset += $bytes.Length
}
# PNG 图像数据
for ($i = 0; $i -lt $count; $i++) {
    [byte[]]$bytes = $pngBytesList[$i]
    $bw.Write($bytes)
}
$bw.Flush()

New-Item -ItemType Directory -Force -Path $icoOutDir | Out-Null
[System.IO.File]::WriteAllBytes($icoOutPath, $out.ToArray())
$bw.Dispose()
$out.Dispose()
Write-Host ("已生成 ICO: {0} ({1} 个尺寸)" -f $icoOutPath, $count)

# ---------- 2. Android adaptive icon 前景(各密度; 内容缩进 60% 安全区居中, 不裁) ----------
# adaptive icon 画布 108dp, 系统裁外圈约 18%; 内容缩到 60% 居中, 落在 66dp 安全区内。
$densities = [ordered]@{
    'mdpi'    = 108
    'hdpi'    = 162
    'xhdpi'   = 216
    'xxhdpi'  = 324
    'xxxhdpi' = 432
}
$scaleRatio = 0.60

foreach ($kv in $densities.GetEnumerator()) {
    $name = $kv.Key
    $canvas = $kv.Value
    $contentSize = [int]($canvas * $scaleRatio)
    $offset = [int](($canvas - $contentSize) / 2)

    $bmp = New-Object System.Drawing.Bitmap $canvas, $canvas
    $bmp.SetResolution(96.0, 96.0)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.DrawImage($src, $offset, $offset, $contentSize, $contentSize)
    $g.Dispose()

    $outDir = Join-Path $resRoot "mipmap-$name"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $outPath = Join-Path $outDir "ic_launcher_foreground.png"
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("已生成前景: {0} ({1}x{1}, content={2})" -f $outPath, $canvas, $contentSize)
}

$src.Dispose()
Write-Host "完成。"
