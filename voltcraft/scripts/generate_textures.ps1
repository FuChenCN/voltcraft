# Generates 32x32 placeholder block textures for VoltCraft.
# Run: pwsh -File scripts/generate_textures.ps1
#
# Outputs to src/main/resources/assets/voltcraft/textures/block/
#
# Style: chunky pixel art, voltage-tier colors, simple lighting.

Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$out  = Join-Path $root 'src/main/resources/assets/voltcraft/textures/block'
New-Item -ItemType Directory -Force -Path $out | Out-Null

$SIZE = 32

function New-Bmp {
    $bmp = New-Object System.Drawing.Bitmap $SIZE, $SIZE, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    return $bmp
}

function Save-Bmp([System.Drawing.Bitmap]$bmp, [string]$name) {
    $path = Join-Path $out "$name.png"
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "wrote $name.png"
}

function Hex([string]$h) {
    $h = $h.TrimStart('#')
    if ($h.Length -eq 6) { $h = "FF$h" }
    [System.Drawing.Color]::FromArgb([Convert]::ToInt32($h.Substring(0,2),16),
                                     [Convert]::ToInt32($h.Substring(2,2),16),
                                     [Convert]::ToInt32($h.Substring(4,2),16),
                                     [Convert]::ToInt32($h.Substring(6,2),16))
}

function Fill([System.Drawing.Bitmap]$bmp, [System.Drawing.Color]$c) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $brush = New-Object System.Drawing.SolidBrush $c
    $g.FillRectangle($brush, 0, 0, $SIZE, $SIZE)
    $brush.Dispose()
    $g.Dispose()
}

function Rect([System.Drawing.Bitmap]$bmp, [int]$x, [int]$y, [int]$w, [int]$h, [System.Drawing.Color]$c) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $brush = New-Object System.Drawing.SolidBrush $c
    $g.FillRectangle($brush, $x, $y, $w, $h)
    $brush.Dispose()
    $g.Dispose()
}

function Pixel([System.Drawing.Bitmap]$bmp, [int]$x, [int]$y, [System.Drawing.Color]$c) {
    if ($x -ge 0 -and $x -lt $SIZE -and $y -ge 0 -and $y -lt $SIZE) {
        $bmp.SetPixel($x, $y, $c)
    }
}

# Voltage tier color palette (true-ish electrical color codes)
$tierColors = @{
    'low'        = @{ main = Hex '6B7280'; accent = Hex '94A3B8'; mark = Hex 'FACC15' }  # gray + yellow stripe (220V neutral)
    'medium'     = @{ main = Hex '1E3A8A'; accent = Hex '3B82F6'; mark = Hex '93C5FD' }  # navy blue (10kV)
    'high'       = @{ main = Hex 'B45309'; accent = Hex 'F59E0B'; mark = Hex 'FED7AA' }  # amber/orange (35kV)
    'extra_high' = @{ main = Hex '7F1D1D'; accent = Hex 'DC2626'; mark = Hex 'FCA5A5' }  # crimson (110kV)
}

# === Cables: braided cable cross-section style ===
# Center bundle of 3 strands (hot/neutral/ground), insulated outer ring.
function Make-Cable($tier) {
    $c = $tierColors[$tier]
    $bmp = New-Bmp
    Fill $bmp (Hex '141414')
    # outer insulation
    Rect $bmp 4 4 24 24 $c.main
    # inner conductor area (darker)
    Rect $bmp 8 8 16 16 (Hex '0F172A')
    # 3 conductor dots: hot (red), neutral (blue), ground (yellow/green)
    Rect $bmp 10 10 4 4 (Hex 'EF4444')   # hot
    Rect $bmp 18 10 4 4 (Hex '3B82F6')   # neutral
    Rect $bmp 14 18 4 4 (Hex 'EAB308')   # ground
    # tier accent stripes top + bottom (so adjacent cables show banding)
    Rect $bmp 4 4 24 2 $c.accent
    Rect $bmp 4 26 24 2 $c.accent
    # tier mark dot top-left corner for instant identification
    Rect $bmp 1 1 3 3 $c.mark
    Save-Bmp $bmp "${tier}_voltage_cable"
}

foreach ($t in @('low','medium','high','extra_high')) { Make-Cable $t }

# === Transformer: front (input/output indicator), side (cooling fins), top (insulators) ===
$transformerBody = Hex '4B5563'
$transformerDark = Hex '1F2937'
$transformerHi   = Hex '9CA3AF'

function Make-TransformerFront($tier) {
    $c = $tierColors[$tier]
    $bmp = New-Bmp
    Fill $bmp $transformerBody
    # outer bevel
    Rect $bmp 0 0 32 1 $transformerHi
    Rect $bmp 0 0 1 32 $transformerHi
    Rect $bmp 0 31 32 1 $transformerDark
    Rect $bmp 31 0 1 32 $transformerDark
    # warning sign frame
    Rect $bmp 6 6 20 20 $transformerDark
    # voltage tier color block (the "label")
    Rect $bmp 8 8 16 12 $c.main
    Rect $bmp 8 8 16 2 $c.accent
    Rect $bmp 8 18 16 2 $c.accent
    # zigzag lightning bolt
    Pixel $bmp 14 11 $c.mark
    Pixel $bmp 15 11 $c.mark
    Pixel $bmp 13 12 $c.mark
    Pixel $bmp 14 12 $c.mark
    Pixel $bmp 14 13 $c.mark
    Pixel $bmp 15 13 $c.mark
    Pixel $bmp 16 13 $c.mark
    Pixel $bmp 14 14 $c.mark
    Pixel $bmp 15 14 $c.mark
    Pixel $bmp 16 14 $c.mark
    Pixel $bmp 17 14 $c.mark
    Pixel $bmp 16 15 $c.mark
    Pixel $bmp 17 15 $c.mark
    Pixel $bmp 17 16 $c.mark
    # output port at bottom (where cable attaches)
    Rect $bmp 14 22 4 4 (Hex '111827')
    Rect $bmp 15 23 2 2 $c.accent
    Save-Bmp $bmp "${tier}_voltage_transformer_front"
}

function Make-TransformerSide($tier) {
    $c = $tierColors[$tier]
    $bmp = New-Bmp
    Fill $bmp $transformerBody
    # bevel
    Rect $bmp 0 0 32 1 $transformerHi
    Rect $bmp 0 0 1 32 $transformerHi
    Rect $bmp 0 31 32 1 $transformerDark
    Rect $bmp 31 0 1 32 $transformerDark
    # cooling fins: vertical bars
    for ($x = 4; $x -lt 28; $x += 4) {
        Rect $bmp $x 6 2 20 $transformerDark
        Rect $bmp ($x + 1) 6 1 20 $transformerHi
    }
    # tier color band along the top
    Rect $bmp 2 2 28 2 $c.main
    Save-Bmp $bmp "${tier}_voltage_transformer_side"
}

foreach ($t in @('low','medium','high','extra_high')) {
    Make-TransformerFront $t
    Make-TransformerSide $t
}

# Shared transformer top: 4 ceramic insulators
$bmp = New-Bmp
Fill $bmp $transformerBody
# bevel
Rect $bmp 0 0 32 1 $transformerHi
Rect $bmp 0 0 1 32 $transformerHi
Rect $bmp 0 31 32 1 $transformerDark
Rect $bmp 31 0 1 32 $transformerDark
# 4 insulator dots arranged in 2x2 with disc shading
$insulatorPositions = @(@(8,8), @(20,8), @(8,20), @(20,20))
foreach ($p in $insulatorPositions) {
    $px = $p[0]; $py = $p[1]
    # disc body
    Rect $bmp $px $py 4 4 (Hex 'D1D5DB')
    Rect $bmp ($px+1) ($py+1) 2 2 (Hex 'F3F4F6')
    Rect $bmp ($px+3) ($py+3) 1 1 (Hex '6B7280')
}
# central oil-cooler crosshatch
Rect $bmp 13 13 6 6 $transformerDark
Rect $bmp 14 14 4 4 (Hex '111827')
Save-Bmp $bmp 'transformer_top'

# === Breaker: faceplate with toggle handle (closed/tripped per tier) ===
$breakerBody = Hex '374151'
$breakerHi   = Hex '6B7280'
$breakerDark = Hex '111827'

function Make-BreakerFront($tier, $tripped) {
    $c = $tierColors[$tier]
    $bmp = New-Bmp
    Fill $bmp $breakerBody
    # bevel
    Rect $bmp 0 0 32 1 $breakerHi
    Rect $bmp 0 0 1 32 $breakerHi
    Rect $bmp 0 31 32 1 $breakerDark
    Rect $bmp 31 0 1 32 $breakerDark
    # tier strip top
    Rect $bmp 2 2 28 3 $c.main
    Rect $bmp 2 2 28 1 $c.accent
    # central toggle slot (recessed)
    Rect $bmp 10 8 12 18 $breakerDark
    Rect $bmp 11 9 10 16 (Hex '1F2937')

    if ($tripped) {
        # red OFF indicator + handle pushed up
        # status light
        Rect $bmp 12 10 8 4 (Hex '7F1D1D')
        Rect $bmp 13 11 6 2 (Hex 'EF4444')
        # handle in upper position with red end
        Rect $bmp 14 16 4 8 (Hex 'B91C1C')
        Rect $bmp 14 16 4 2 (Hex 'FECACA')  # highlight on top of handle
        # small "I/O" label (here just a dash to indicate OFF)
        Pixel $bmp 15 25 (Hex 'FCA5A5')
        Pixel $bmp 16 25 (Hex 'FCA5A5')
    } else {
        # green ON indicator + handle pushed down
        Rect $bmp 12 10 8 4 (Hex '14532D')
        Rect $bmp 13 11 6 2 (Hex '22C55E')
        # handle in lower position with green-ish neutral
        Rect $bmp 14 16 4 8 (Hex '4B5563')
        Rect $bmp 14 22 4 2 (Hex 'BBF7D0')  # highlight at bottom
        Pixel $bmp 16 25 (Hex 'D1FAE5')
    }
    Save-Bmp $bmp "${tier}_voltage_breaker_$(if($tripped){'tripped'}else{'closed'})"
}

foreach ($t in @('low','medium','high','extra_high')) {
    Make-BreakerFront $t $false
    Make-BreakerFront $t $true
}

# Shared breaker top + side
$bmp = New-Bmp
Fill $bmp $breakerBody
Rect $bmp 0 0 32 1 $breakerHi
Rect $bmp 0 0 1 32 $breakerHi
Rect $bmp 0 31 32 1 $breakerDark
Rect $bmp 31 0 1 32 $breakerDark
# row of small terminal screws
for ($x = 6; $x -lt 28; $x += 6) {
    Rect $bmp $x 8 4 4 (Hex 'D1D5DB')
    Rect $bmp ($x+1) 9 2 2 (Hex '4B5563')
    Rect $bmp $x 20 4 4 (Hex 'D1D5DB')
    Rect $bmp ($x+1) 21 2 2 (Hex '4B5563')
}
Save-Bmp $bmp 'breaker_top'

$bmp = New-Bmp
Fill $bmp $breakerBody
Rect $bmp 0 0 32 1 $breakerHi
Rect $bmp 0 0 1 32 $breakerHi
Rect $bmp 0 31 32 1 $breakerDark
Rect $bmp 31 0 1 32 $breakerDark
# vertical mounting groove
Rect $bmp 14 4 4 24 $breakerDark
Rect $bmp 15 4 2 24 (Hex '1F2937')
# screw heads top + bottom
Rect $bmp 14 4 4 4 (Hex 'D1D5DB')
Rect $bmp 14 24 4 4 (Hex 'D1D5DB')
Pixel $bmp 15 5 (Hex '4B5563')
Pixel $bmp 16 6 (Hex '4B5563')
Pixel $bmp 15 25 (Hex '4B5563')
Pixel $bmp 16 26 (Hex '4B5563')
Save-Bmp $bmp 'breaker_side'

# === Terminal: 3 colored screw posts (hot/neutral/ground) on a faceplate ===
$terminalBody = Hex '475569'
$terminalHi   = Hex '94A3B8'
$terminalDark = Hex '0F172A'

function Make-TerminalFront($tier, $state) {
    $c = $tierColors[$tier]
    $bmp = New-Bmp
    Fill $bmp $terminalBody
    # bevel
    Rect $bmp 0 0 32 1 $terminalHi
    Rect $bmp 0 0 1 32 $terminalHi
    Rect $bmp 0 31 32 1 $terminalDark
    Rect $bmp 31 0 1 32 $terminalDark
    # tier strip top
    Rect $bmp 2 2 28 3 $c.main
    Rect $bmp 2 2 28 1 $c.accent
    # 3 screw posts L (hot=red), N (neutral=blue), E (ground=yellow/green)
    # base color depends on state
    $hotColor    = Hex 'EF4444'
    $neutralColor= Hex '3B82F6'
    $groundColor = Hex 'EAB308'
    if ($state -eq 'fault') {
        # MISSING_GROUND or HOT_NEUTRAL_SWAPPED: ground or hot/neutral darkened
        $groundColor = Hex '4B5563'    # ground "missing"
    } elseif ($state -eq 'short') {
        # SHORT: cross wire visible between hot and neutral/ground (red overlay)
        $hotColor = Hex 'B91C1C'
        $neutralColor = Hex '7F1D1D'
    }
    # 3 posts, evenly spaced
    $posY = 14
    $posL = 6
    $posN = 14
    $posE = 22
    # screw caps with tiny center divot
    function DrawPost([int]$x, [int]$y, [System.Drawing.Color]$col) {
        Rect $bmp $x $y 6 8 $terminalDark
        Rect $bmp ($x+1) ($y+1) 4 6 $col
        Pixel $bmp ($x+2) ($y+3) $terminalDark
        Pixel $bmp ($x+3) ($y+3) $terminalDark
        Pixel $bmp ($x+2) ($y+4) $terminalDark
        Pixel $bmp ($x+3) ($y+4) $terminalDark
    }
    DrawPost $posL $posY $hotColor
    DrawPost $posN $posY $neutralColor
    DrawPost $posE $posY $groundColor
    # post labels
    Rect $bmp ($posL+1) 9 4 1 (Hex 'F8FAFC')
    Rect $bmp ($posN+1) 9 4 1 (Hex 'F8FAFC')
    Rect $bmp ($posE+1) 9 4 1 (Hex 'F8FAFC')

    if ($state -eq 'short') {
        # red diagonal "danger" stripe across the face
        for ($i = 0; $i -lt 32; $i++) {
            Pixel $bmp $i ((28 - $i) % 32) (Hex 'EF4444')
        }
    }
    if ($state -eq 'fault') {
        # yellow warning triangle small in corner
        Pixel $bmp 26 26 (Hex 'FACC15')
        Pixel $bmp 25 27 (Hex 'FACC15')
        Pixel $bmp 27 27 (Hex 'FACC15')
        Pixel $bmp 24 28 (Hex 'FACC15')
        Pixel $bmp 25 28 (Hex 'FACC15')
        Pixel $bmp 26 28 (Hex 'FACC15')
        Pixel $bmp 27 28 (Hex 'FACC15')
        Pixel $bmp 28 28 (Hex 'FACC15')
        Pixel $bmp 26 27 (Hex '111827')
    }
    Save-Bmp $bmp "${tier}_voltage_terminal_$state"
}

foreach ($t in @('low','medium','high','extra_high')) {
    Make-TerminalFront $t 'correct'
    Make-TerminalFront $t 'fault'
    Make-TerminalFront $t 'short'
}

# Shared terminal top + side
$bmp = New-Bmp
Fill $bmp $terminalBody
Rect $bmp 0 0 32 1 $terminalHi
Rect $bmp 0 0 1 32 $terminalHi
Rect $bmp 0 31 32 1 $terminalDark
Rect $bmp 31 0 1 32 $terminalDark
# 3 small holes for cable entry on top
Rect $bmp 8 12 4 8 $terminalDark
Rect $bmp 14 12 4 8 $terminalDark
Rect $bmp 20 12 4 8 $terminalDark
Save-Bmp $bmp 'terminal_top'

$bmp = New-Bmp
Fill $bmp $terminalBody
Rect $bmp 0 0 32 1 $terminalHi
Rect $bmp 0 0 1 32 $terminalHi
Rect $bmp 0 31 32 1 $terminalDark
Rect $bmp 31 0 1 32 $terminalDark
# horizontal mounting bracket
Rect $bmp 4 14 24 4 $terminalDark
Rect $bmp 5 15 22 2 (Hex '1F2937')
Save-Bmp $bmp 'terminal_side'

Write-Host ""
Write-Host "All textures generated to: $out"
