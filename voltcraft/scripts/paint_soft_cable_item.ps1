# 手画软线物品贴图（4 个 tier 各一张 16x16）。
# 物品图标 = 一捆软线插头（黑外皮 + L/N/E 三色编织）+ 一段斜垂的线尾 + 左下角 tier 等级带。
# 每个像素位置都在源码里硬编码，不用循环算纹路。

Add-Type -AssemblyName System.Drawing

$assets = Join-Path $PSScriptRoot ".." "src" "main" "resources" "assets" "voltcraft" "textures" "item"
if (-not (Test-Path $assets)) { New-Item -ItemType Directory -Path $assets | Out-Null }

# 颜色
$K   = [System.Drawing.Color]::FromArgb(20, 20, 20)        # 橡胶黑
$R   = [System.Drawing.Color]::FromArgb(192, 48, 48)       # L 红
$N   = [System.Drawing.Color]::FromArgb(44, 111, 224)      # N 蓝
$E   = [System.Drawing.Color]::FromArgb(192, 176, 32)      # E 黄绿
$T   = [System.Drawing.Color]::Transparent

$tierBands = @{
    'low_voltage'        = [System.Drawing.Color]::FromArgb(200, 200, 200)
    'medium_voltage'     = [System.Drawing.Color]::FromArgb(80, 130, 220)
    'high_voltage'       = [System.Drawing.Color]::FromArgb(240, 140, 30)
    'extra_high_voltage' = [System.Drawing.Color]::FromArgb(200, 50, 40)
}

# 用一张「字符栅格」字符到颜色的映射来手填每个像素
# . = transparent, k = 黑, R = 红, N = 蓝, E = 黄绿, T = tier 等级带

function Build-Icon([System.Drawing.Color]$tierBand) {
    $bmp = New-Object System.Drawing.Bitmap 16, 16
    # 先全部填透明
    for ($y = 0; $y -lt 16; $y++) {
        for ($x = 0; $x -lt 16; $x++) {
            $bmp.SetPixel($x, $y, $T)
        }
    }

    # 16x16 字符栅格——每一行 16 个字符（用空格分隔以便对齐阅读）
    # 头部：一团「插头」用横截面剖视图呈现三色编织
    # 尾部：一段斜垂下的线缆（外皮黑 + 中央细像素彩条）
    # 左下：tier 等级带
    $rows = @(
        # 0
        '. . . . . . . . . . . . . . . .'
        # 1：插头边缘
        '. . . . k k k k k k . . . . . .'
        # 2：插头边缘 + 第一层 L
        '. . . k k R R R R k k . . . . .'
        # 3：第一层 L + 第二层 E
        '. . k k R R E E R R k k . . . .'
        # 4：L + E + N 内核
        '. . k R R E N N E R R k . . . .'
        # 5：L + E + N 中央
        '. . k R E N N N N E R k . . . .'
        # 6：和上对称
        '. . k R E N N N N E R k . . . .'
        # 7
        '. . k R R E N N E R R k . . . .'
        # 8：插头底缘
        '. . . k k R R R R k k . . . . .'
        # 9：插头底缘
        '. . . . . k k k k . . . . . . .'
        # 10：开始斜垂线尾（一段 1-2 像素粗的黑线尾巴）
        '. . . . . . . k . . . . . . . .'
        # 11
        '. . . . . . . k . . . . . . . .'
        # 12
        '. . . . . . . k k . . . . . . .'
        # 13
        '. . . . . . . . k . . . . . . .'
        # 14：tier 等级带从左下角凸起
        'T T T . . . . . k k . . . . . .'
        # 15
        'T T T . . . . . . k . . . . . .'
    )

    for ($y = 0; $y -lt 16; $y++) {
        $cells = $rows[$y].Split(' ')
        for ($x = 0; $x -lt 16; $x++) {
            $c = $cells[$x]
            switch ($c) {
                'k' { $bmp.SetPixel($x, $y, $K); break }
                'R' { $bmp.SetPixel($x, $y, $R); break }
                'N' { $bmp.SetPixel($x, $y, $N); break }
                'E' { $bmp.SetPixel($x, $y, $E); break }
                'T' { $bmp.SetPixel($x, $y, $tierBand); break }
                default { } # 透明
            }
        }
    }

    return $bmp
}

foreach ($tier in $tierBands.Keys) {
    $bmp = Build-Icon $tierBands[$tier]
    $out = Join-Path $assets ($tier + '_soft_cable.png')
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $out"
}

Write-Host "done."
