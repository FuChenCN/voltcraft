# 手画机器顶面贴图（6 接线柱版）
# 不批量循环 —— 每个像素的位置/颜色都在源码里硬编码

Add-Type -AssemblyName System.Drawing

$assets = Join-Path $PSScriptRoot ".." "src" "main" "resources" "assets" "voltcraft" "textures" "block"

# tier 等级带颜色（左下角 3×2 小色块；区分玩家面前是低压还是高压机器）
$tierBands = @{
    'low_voltage'        = [System.Drawing.Color]::FromArgb(200, 200, 200)  # 银白
    'medium_voltage'     = [System.Drawing.Color]::FromArgb(80, 130, 220)   # 钴蓝
    'high_voltage'       = [System.Drawing.Color]::FromArgb(240, 140, 30)   # 警示橙
    'extra_high_voltage' = [System.Drawing.Color]::FromArgb(200, 50, 40)    # 警示红
}

# 接线柱颜色
$colorL = [System.Drawing.Color]::FromArgb(192, 48, 48)    # 红 = LIVE
$colorN = [System.Drawing.Color]::FromArgb(44, 111, 224)   # 蓝 = NEUTRAL
$colorE = [System.Drawing.Color]::FromArgb(192, 176, 32)   # 黄绿 = EARTH

# 柱子环（每个柱子一圈深色边）
$ringColor = [System.Drawing.Color]::FromArgb(20, 20, 20)

# 底色：深灰金属（不写 noise，纯色更干净）
$base = [System.Drawing.Color]::FromArgb(56, 56, 56)
$baseDark = [System.Drawing.Color]::FromArgb(38, 38, 38)
$baseLight = [System.Drawing.Color]::FromArgb(72, 72, 72)

# 中间分隔列（u=7 与 u=8）：用稍深的灰暗示 IN / OUT 分界
$divider = [System.Drawing.Color]::FromArgb(28, 28, 28)

# ============================================================
# 通用辅助：在 bitmap 上画一个 2×2 的"接线柱"（中心高亮 + 深边）
# ============================================================
function Draw-Stud([System.Drawing.Bitmap]$bmp, [int]$cx, [int]$cy, [System.Drawing.Color]$inner) {
    # 4×4 区域：外圈深边，2×2 内芯彩色
    for ($dy = -2; $dy -le 1; $dy++) {
        for ($dx = -2; $dx -le 1; $dx++) {
            $x = $cx + $dx
            $y = $cy + $dy
            if ($x -lt 0 -or $x -gt 15 -or $y -lt 0 -or $y -gt 15) { continue }
            # 外圈深边（dx,dy 中至少一个是 -2 或 1）
            $onRing = ($dx -eq -2 -or $dx -eq 1 -or $dy -eq -2 -or $dy -eq 1)
            if ($onRing) {
                $bmp.SetPixel($x, $y, $ringColor)
            } else {
                # 内 2×2 区域：左上像素提亮，模拟金属反光
                if ($dx -eq -1 -and $dy -eq -1) {
                    $r = [Math]::Min(255, $inner.R + 60)
                    $g = [Math]::Min(255, $inner.G + 60)
                    $b = [Math]::Min(255, $inner.B + 60)
                    $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($r, $g, $b))
                } else {
                    $bmp.SetPixel($x, $y, $inner)
                }
            }
        }
    }
}

function Build-TopTexture([System.Drawing.Color]$tierBand) {
    $bmp = New-Object System.Drawing.Bitmap 16, 16

    # 1) 底色：先全填 base，然后给四角和边缘洒一圈深一点的"接缝"
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $bmp.SetPixel($x, $y, $base)
        }
    }

    # 边缘 1 像素深色描边（让方块面板感更明显）
    for ($i = 0; $i -lt 16; $i++) {
        $bmp.SetPixel($i, 0,  $baseDark)
        $bmp.SetPixel($i, 15, $baseDark)
        $bmp.SetPixel(0,  $i, $baseDark)
        $bmp.SetPixel(15, $i, $baseDark)
    }

    # 顶部 1 行高光（u=1..14, v=1）模拟金属边缘亮反
    for ($x = 1; $x -le 14; $x++) {
        $bmp.SetPixel($x, 1, $baseLight)
    }

    # 2) 中间分隔列（u=7..8，v=1..14）颜色稍深
    for ($y = 1; $y -le 14; $y++) {
        $bmp.SetPixel(7, $y, $divider)
        $bmp.SetPixel(8, $y, $divider)
    }

    # 分隔列上的两个螺丝（v=4 和 v=11）让它看起来像盖板
    $screwColor = [System.Drawing.Color]::FromArgb(160, 160, 160)
    $bmp.SetPixel(7, 4,  $screwColor); $bmp.SetPixel(8, 4,  $screwColor)
    $bmp.SetPixel(7, 11, $screwColor); $bmp.SetPixel(8, 11, $screwColor)

    # 3) 6 个接线柱
    # TopAnchorLayout: Z_L=0.20, Z_N=0.50, Z_E=0.80, X_IN=0.25, X_OUT=0.75
    # 16 像素映射：Z*16 = L_v=3, N_v=8, E_v=13；X_IN_u=4, X_OUT_u=12
    # Draw-Stud(cx,cy) 画在 cx-2..cx+1, cy-2..cy+1 的 4x4 区域，所以中心略偏

    # E（黄绿）行 v≈2-3
    Draw-Stud $bmp 4  3  $colorE   # E_IN
    Draw-Stud $bmp 12 3  $colorE   # E_OUT

    # N（蓝）行 v≈7-8
    Draw-Stud $bmp 4  8  $colorN   # N_IN
    Draw-Stud $bmp 12 8  $colorN   # N_OUT

    # L（红）行 v≈12-13
    Draw-Stud $bmp 4  13 $colorL   # L_IN
    Draw-Stud $bmp 12 13 $colorL   # L_OUT

    # 4) 左下角等级带（u=0..2, v=14..15）3×2 像素
    for ($y = 14; $y -le 15; $y++) {
        for ($x = 0; $x -le 2; $x++) {
            $bmp.SetPixel($x, $y, $tierBand)
        }
    }
    # 等级带边框（黑色单像素描边在它上方和右边）
    $bmp.SetPixel(0, 13, $ringColor); $bmp.SetPixel(1, 13, $ringColor); $bmp.SetPixel(2, 13, $ringColor)
    $bmp.SetPixel(3, 14, $ringColor); $bmp.SetPixel(3, 15, $ringColor)

    return $bmp
}

# ============================================================
# 写四个 tier 的 _top 贴图，覆盖三种机器（变压器/空开/端子）共用文件名时各自存
# ============================================================
foreach ($tier in $tierBands.Keys) {
    $bmp = Build-TopTexture $tierBands[$tier]
    $out = Join-Path $assets ($tier + '_machine_top.png')
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $out"
}

Write-Host "done."
