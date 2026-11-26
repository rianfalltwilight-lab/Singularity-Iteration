#!/usr/bin/env python3
"""Render docs/art/previews/MT1.png from the exported models and item sprites.

Requires NumPy, Pillow and a Chinese font (pass --font if needed).
"""
import argparse
import json
import math
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from item_model_preview import FACES, NORMALS, rot


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/mio_icif"
MODELS = ASSETS / "models/block/producer"
VARIANTS = (ASSETS / "textures", ROOT / "docs/art/mt1/variant_b")
OUTPUT = ROOT / "docs/art/previews/MT1.png"
ICON = "item/producer/item_molecular_transformer.png"
MACHINE = "block_molecular_transformer"
COMPRESSOR = "block_compressor_elc"

ISO = (-0.57, 0.39, -1.0)
VIEWS = ((0, 0, -1), (-1, 0, 0), (0, 1, 0), ISO)
PAPER, WHITE, LINE = "#f2f3f0", "#fafbf8", "#d9deda"
INK, MUTED = "#202c30", "#566365"
STAGE, PALE = "#283339", "#c3cdd0"
ACCENTS = ("#5eddd9", "#e7a6ea")
LABELS = ("A  青蓝", "B  紫粉")


def default_uv(direction, lo, hi):
    x, y, z = lo
    X, Y, Z = hi
    return {
        "down": (x, 16 - Z, X, 16 - z), "up": (x, z, X, Z),
        "north": (16 - X, 16 - Y, 16 - x, 16 - y), "south": (x, 16 - Y, X, 16 - y),
        "west": (z, 16 - Y, Z, 16 - y), "east": (16 - Z, 16 - Y, 16 - z, 16 - y),
    }[direction]


@lru_cache(maxsize=None)
def texture(path):
    with Image.open(path) as image:
        return np.asarray(image.convert("RGBA"))


@lru_cache(maxsize=None)
def faces(model_name, bank):
    model = json.loads((MODELS / (model_name + ".json")).read_text(encoding="utf8"))
    result = []
    children = model.get("children", {}).values()
    for part in (model, *children):
        names = {**model.get("textures", {}), **part.get("textures", {})}
        translucent = part.get("render_type") == "minecraft:translucent"
        for element in part.get("elements", []):
            lo, hi = element["from"], element["to"]
            rotate = element.get("rotation", {})
            for direction, face in element["faces"].items():
                corners = np.where(FACES[direction], hi, lo).astype(float)
                normal = NORMALS[direction]
                if rotate.get("angle"):
                    axis, angle, origin = rotate["axis"], rotate["angle"], rotate["origin"]
                    corners = np.array([rot(p, axis, angle, origin) for p in corners])
                    if rotate.get("rescale"):
                        factors = np.full(3, 1 / math.cos(math.radians(angle)))
                        factors["xyz".index(axis)] = 1
                        corners = origin + (corners - origin) * factors
                    normal = rot(normal, axis, angle, (0, 0, 0))
                name = face["texture"]
                while name.startswith("#"):
                    name = names[name[1:]]
                pixels = texture(bank / (name.split(":")[-1] + ".png"))
                u, v, U, V = face.get("uv", default_uv(direction, lo, hi))
                uv = np.array([(u, v), (u, V), (U, V), (U, v)], dtype=float) / 16
                uv = np.roll(uv, -face.get("rotation", 0) // 90, axis=0)
                light = face.get("neoforge_data", element.get("neoforge_data", {}))
                unlit = not element.get("shade", True) or light.get("block_light", 0) == 15
                result.append((corners, uv, normal, pixels, translucent, unlit))
    return result


def render(quads, eye, scale, size, background):
    """Orthographic textured quads, opaque depth first and glass sorted per pixel."""
    sampling = 2
    w, h = (n * sampling for n in size)
    eye = np.array(eye, dtype=float)
    eye /= np.linalg.norm(eye)
    right = np.cross([0, 1, 0], eye) if abs(eye[1]) != 1 else np.array([-1., 0, 0])
    right /= np.linalg.norm(right)
    up = np.cross(eye, right)
    camera = np.stack((right, -up, eye), axis=1)
    rgb = np.empty((h, w, 3), dtype=np.uint8)
    rgb[:] = tuple(bytes.fromhex(background[1:]))
    zbuffer = np.full((h, w), -np.inf, dtype=np.float32)
    glass = []
    for corners, uv, normal, pixels, transparent, unlit in quads:
        if np.dot(normal, eye) < 1e-7:
            continue
        points = (corners - 8) @ camera
        points[:, :2] *= scale * sampling
        points[:, :2] += [w / 2, h / 2]
        x0, y0 = np.maximum(0, np.floor(points[:, :2].min(axis=0)).astype(int))
        x1, y1 = np.minimum([w, h], np.ceil(points[:, :2].max(axis=0)).astype(int))
        if x1 <= x0 or y1 <= y0:
            continue
        a, b = points[1] - points[0], points[3] - points[0]
        det = a[0] * b[1] - a[1] * b[0]
        if abs(det) < 1e-8:
            continue
        yy, xx = np.ogrid[y0:y1, x0:x1]
        dx, dy = xx + .5 - points[0, 0], yy + .5 - points[0, 1]
        s, t = (dx * b[1] - dy * b[0]) / det, (dy * a[0] - dx * a[1]) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        z = points[0, 2] + s * a[2] + t * b[2]
        texel = uv[0] + s[..., None] * (uv[1] - uv[0]) + t[..., None] * (uv[3] - uv[0])
        th, tw = pixels.shape[:2]
        indices = np.clip((texel * [tw, th]).astype(int), 0, [tw - 1, th - 1])
        rgba = pixels[indices[:, :, 1], indices[:, :, 0]]
        # Vanilla face lighting: up 1.0, sides north/south .8, west/east .6, down .5.
        nx, ny, nz = normal
        shade = 1 if unlit else .6 * nx ** 2 + (1 if ny > 0 else .5) * ny ** 2 + .8 * nz ** 2
        color = np.rint(rgba[:, :, :3] * shade).astype(np.uint8)
        mask = inside & (rgba[:, :, 3] > 0)
        region = np.s_[y0:y1, x0:x1]
        if transparent:
            glass.append((region, z, mask, color, rgba[:, :, 3]))
        else:
            visible = mask & (z > zbuffer[region])
            rgb[region][visible] = color[visible]
            zbuffer[region][visible] = z[visible]

    # Sparse glass fragments sorted far-to-near within each pixel. Occluded panels
    # are discarded against the final opaque depth before any blending takes place.
    fragments = []
    for (row, col), z, mask, color, alpha in glass:
        yy, xx = np.nonzero(mask & (z > zbuffer[row, col] + 1e-5))
        if len(xx):
            position = (yy + row.start) * w + xx + col.start
            fragments.append((position, z[yy, xx], color[yy, xx], alpha[yy, xx]))
    if fragments:
        pos, depth, colors, opacities = [np.concatenate(data) for data in zip(*fragments)]
        order = np.lexsort((depth, pos))
        pos, colors, opacities = pos[order], colors[order], opacities[order]
        starts = np.flatnonzero(np.r_[True, pos[1:] != pos[:-1]])
        counts = np.diff(np.r_[starts, len(pos)])
        flat = rgb.reshape(-1, 3)
        for layer in range(counts.max()):
            idx = starts[counts > layer] + layer
            targets = pos[idx]
            blend = opacities[idx, None] / 255
            flat[targets] = np.rint(colors[idx] * blend + flat[targets] * (1 - blend))
    return Image.fromarray(rgb).resize(size, Image.Resampling.BOX)


def find_font(override, bold):
    if override:
        return override
    candidates = (
        "msyhbd.ttc" if bold else "msyh.ttc",
        "NotoSansCJK-Bold.ttc" if bold else "NotoSansCJK-Regular.ttc",
        "PingFang.ttc",
        "/System/Library/Fonts/PingFang.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-" + ("Bold" if bold else "Regular") + ".ttc",
    )
    for candidate in candidates:
        try:
            ImageFont.truetype(candidate, 16)
            return candidate
        except OSError:
            continue
    raise SystemExit("Install a Chinese font or pass --font /path/to/font.otf")


def inventory_slot(sheet, icon, xy, magnification):
    slot = Image.new("RGB", (18, 18), "#8b8b8b")
    pen = ImageDraw.Draw(slot)
    pen.line([(0, 17), (0, 0), (17, 0)], fill="#373737")
    pen.line([(1, 17), (17, 17), (17, 1)], fill="#ffffff")
    slot.paste(icon, (1, 1), icon)
    size = 18 * magnification
    sheet.paste(slot.resize((size, size), Image.Resampling.NEAREST), xy)


def make_sheet(font_path=None):
    sheet = Image.new("RGB", (1440, 1150), PAPER)
    draw = ImageDraw.Draw(sheet)
    fonts = tuple(find_font(font_path, bold) for bold in (False, True))

    @lru_cache(maxsize=None)
    def font(size, bold):
        return ImageFont.truetype(fonts[bold], size)

    def label(x, y, string, size, color=INK, bold=False, anchor="lm"):
        draw.text((x, y), string, font=font(size, bold), fill=color, anchor=anchor)

    label(42, 50, "分子重组仪", 43, bold=True)
    label(1396, 56, "奇点迭代   /   机器美术", 20, MUTED, anchor="rm")

    width, hero_y = 669, 98
    variants = []
    for version, bank in enumerate(VARIANTS):
        idle, active = (faces(name, bank) for name in (MACHINE, MACHINE + "_on"))
        variants.append((idle, active))
        left = 42 + version * 686
        draw.rounded_rectangle((left, hero_y, left + width, 478), 10, fill=STAGE)
        draw.rectangle((left + 23, 120, left + 27, 149), fill=ACCENTS[version])
        label(left + 39, 134, LABELS[version], 26, "#fbfcf9", True)
        label(left + width - 24, 135, "工作态", 18, PALE, anchor="rm")
        sheet.paste(render(active, ISO, 13.8, (415, 311), STAGE), (left + 9, 161))
        draw.line((left + 427, 178, left + 427, 443), fill="#455157")
        label(left + 473, 188, "物品栏图标", 20, "#ecf1f0", True)
        with Image.open(bank / ICON) as source:
            icon = source.convert("RGBA")
        inventory_slot(sheet, icon, (left + 473, 219), 8)
        label(left + 473, 385, "8×", 15, PALE)
        inventory_slot(sheet, icon, (left + 473, 415), 1)
        label(left + 506, 423, "1×   原尺寸", 15, PALE)

    label(42, 524, "待机与工作", 27, bold=True)
    label(1397, 527, "正面朝北", 17, MUTED, anchor="rm")
    for state, state_text in enumerate(("待机", "工作")):
        for version, states in enumerate(variants):
            left, top = 42 + version * 686, 559 + state * 178
            draw.rounded_rectangle((left, top, left + width, top + 166), 7, fill=WHITE, outline=LINE)
            for i, (eye, caption) in enumerate(zip(VIEWS, ("正面", "西侧", "顶部", "斜视"))):
                mid = left + 84 + i * 166
                sheet.paste(render(states[state], eye, 4.5, (139, 109), WHITE), (mid - 70, top + 2))
                label(mid, top + 120, caption, 15, MUTED, anchor="mm")
            draw.line((left + 16, top + 135, left + width - 16, top + 135), fill=LINE)
            label(left + 18, top + 150, LABELS[version] + "  ·  " + state_text, 15, INK, True)

    # Use the original compressor model and its atlas UVs, at the same world-unit
    # scale, centre and camera as the two candidate machines alongside it.
    top = 939
    draw.line((42, top - 20, 1397, top - 20), fill="#c9d1ce", width=2)
    label(43, top + 61, "方块尺寸对照", 27, bold=True)
    label(44, top + 99, "三台使用同一视角与比例", 17, MUTED)
    compare = ((variants[0][1], "青蓝 · 工作"), (faces(COMPRESSOR, VARIANTS[0]), "原版电动压缩机"),
               (variants[1][1], "紫粉 · 工作"))
    for i, (model, caption) in enumerate(compare):
        mid = 557 + 345 * i
        sheet.paste(render(model, ISO, 6.2, (166, 159), PAPER), (mid - 83, top - 3))
        label(mid, top + 172, caption, 17, INK, anchor="mm")
    return sheet


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", help="path to a Chinese TrueType/OpenType font")
    args = parser.parse_args()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    make_sheet(args.font).save(OUTPUT, optimize=True)
    print(OUTPUT.relative_to(ROOT))
