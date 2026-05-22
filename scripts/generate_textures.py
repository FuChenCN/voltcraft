#!/usr/bin/env python3
"""生成 VoltCraft 矿物和物品的占位纹理（16x16 RGBA PNG）"""

import struct, zlib, os

TEXTURE_DIR = os.path.join(os.path.dirname(__file__), '..', 'voltcraft', 'src', 'main', 'resources', 'assets', 'voltcraft', 'textures')


def create_png(path, pixels):
    width, height = 16, 16

    def chunk(chunk_type, data):
        c = chunk_type + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    header = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    raw = b''
    for row in pixels:
        raw += b'\x00' + bytes(row)
    idat = chunk(b'IDAT', zlib.compress(raw))
    iend = chunk(b'IEND', b'')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(header + ihdr + idat + iend)


def make_ore(p_r, p_g, p_b, s_r, s_g, s_b):
    """矿石方块纹理：矿物斑点 + 石头背景"""
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            if (x * 3 + y * 7) % 5 < 2:
                row.extend([p_r, p_g, p_b, 255])
            elif (x + y) % 3 == 0:
                row.extend([max(p_r - 30, 0), max(p_g - 20, 0), max(p_b - 10, 0), 255])
            else:
                row.extend([s_r, s_g, s_b, 255])
        pixels.append(row)
    return pixels


def make_item_nugget(r, g, b):
    """原矿物品纹理：不规则块状"""
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            cx, cy = abs(x - 7.5), abs(y - 7.5)
            if cx + cy < 7:
                if (x + y) % 2 == 0:
                    row.extend([r, g, b, 255])
                else:
                    row.extend([max(r - 20, 0), max(g - 15, 0), max(b - 10, 0), 255])
            else:
                row.extend([0, 0, 0, 0])
        pixels.append(row)
    return pixels


def make_ingot(r, g, b):
    """锭物品纹理：矩形金属锭"""
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            if 3 <= x <= 12 and 4 <= y <= 11:
                if y == 4 or y == 11 or x == 3 or x == 12:
                    row.extend([max(r - 30, 0), max(g - 30, 0), max(b - 30, 0), 255])
                elif (x + y) % 2 == 0:
                    row.extend([r, g, b, 255])
                else:
                    row.extend([max(r - 15, 0), max(g - 15, 0), max(b - 15, 0), 255])
            else:
                row.extend([0, 0, 0, 0])
        pixels.append(row)
    return pixels


def make_spring():
    """弹簧物品纹理"""
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            if 4 <= y <= 11 and 5 <= x <= 10:
                if (y + x) % 3 == 0:
                    row.extend([192, 192, 200, 255])
                else:
                    row.extend([150, 150, 160, 255])
            elif 6 <= x <= 9 and (y <= 3 or y >= 12):
                row.extend([130, 130, 140, 255])
            else:
                row.extend([0, 0, 0, 0])
        pixels.append(row)
    return pixels


def make_fuse():
    """熔断器物品纹理"""
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            if 5 <= y <= 10 and 3 <= x <= 12:
                if x <= 4 or x >= 11:
                    row.extend([180, 170, 150, 255])
                else:
                    row.extend([200, 180, 140, 255])
            elif 7 <= y <= 8 and 5 <= x <= 10:
                row.extend([100, 100, 100, 255])
            else:
                row.extend([0, 0, 0, 0])
        pixels.append(row)
    return pixels


# === 异极矿 (Hemimorphite) - 锌 ===
create_png(f'{TEXTURE_DIR}/block/hemimorphite_ore.png', make_ore(160, 170, 180, 128, 128, 128))
create_png(f'{TEXTURE_DIR}/block/deepslate_hemimorphite_ore.png', make_ore(150, 160, 170, 100, 100, 100))
create_png(f'{TEXTURE_DIR}/item/zinc_ingot.png', make_ingot(170, 180, 190))
create_png(f'{TEXTURE_DIR}/item/raw_hemimorphite.png', make_item_nugget(150, 160, 170))

# === 蔷薇灰石 (Rhodonite) - 锰 ===
create_png(f'{TEXTURE_DIR}/block/rhodonite_ore.png', make_ore(180, 100, 110, 128, 128, 128))
create_png(f'{TEXTURE_DIR}/block/deepslate_rhodonite_ore.png', make_ore(170, 95, 105, 100, 100, 100))
create_png(f'{TEXTURE_DIR}/item/raw_manganese.png', make_item_nugget(160, 110, 90))
create_png(f'{TEXTURE_DIR}/item/manganese_ingot.png', make_ingot(200, 195, 200))

# === 镍蛇纹石 (Garnierite) - 镍 ===
create_png(f'{TEXTURE_DIR}/block/garnierite_ore.png', make_ore(120, 160, 90, 128, 128, 128))
create_png(f'{TEXTURE_DIR}/block/deepslate_garnierite_ore.png', make_ore(110, 150, 85, 100, 100, 100))
create_png(f'{TEXTURE_DIR}/item/raw_nickel.png', make_item_nugget(130, 170, 100))
create_png(f'{TEXTURE_DIR}/item/nickel_ingot.png', make_ingot(180, 190, 170))

# === 白铅矿 (Cerussite) - 铅 ===
create_png(f'{TEXTURE_DIR}/block/cerussite_ore.png', make_ore(190, 190, 200, 128, 128, 128))
create_png(f'{TEXTURE_DIR}/block/deepslate_cerussite_ore.png', make_ore(180, 180, 190, 100, 100, 100))
create_png(f'{TEXTURE_DIR}/item/raw_lead.png', make_item_nugget(160, 160, 170))
create_png(f'{TEXTURE_DIR}/item/lead_ingot.png', make_ingot(140, 140, 150))

# === 零部件 ===
create_png(f'{TEXTURE_DIR}/item/spring.png', make_spring())
create_png(f'{TEXTURE_DIR}/item/fuse.png', make_fuse())

print("所有纹理已生成完成")
