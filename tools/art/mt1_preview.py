#!/usr/bin/env python3
"""Render docs/art/previews/MT1.png from the exported block and item models.

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
MACHINE = "block_molecular_transformer"
COMPRESSOR = "block_compressor_elc"
ITEM_MODEL = ASSETS / "models/item/producer" / (MACHINE + ".json")
# Minecraft 1.21.1 assets/minecraft/models/block/block.json, display.gui.
BLOCK_GUI = {"rotation": (30, 225, 0), "translation": (0, 0, 0), "scale": (.625, .625, .625)}
# Lighting.setupFor3DItems -> GlStateManager.setupGui3DDiffuseLighting.
# These are the two normalized shader directions in camera space, Y up.
GUI_LIGHTS = np.array([[-.9334393, .2626947, -.2443002], [-.1035714, .9766068, .1884464]])
SLOT_GRAY = "#8b8b8b"

ISO = (-0.57, 0.39, -1.0)
LOADED_VIEW = (-0.27, 0.18, -1.0)
VIEWS = ((0, 0, -1), (-1, 0, 0), (0, 1, 0), ISO)
PAPER, VIEW_SURFACE, LINE = "#f2f3f0", "#d9e0de", "#c2cbc8"
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
def faces(model_name, bank, *, item=False):
    model = json.loads((MODELS / (model_name + ".json")).read_text(encoding="utf8"))
    result = []
    children = model.get("children", {})
    order = model.get("item_render_order", children) if item else children
    for part in (model, *(children[name] for name in order)):
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


def sample_ingot():
    """Preview-only solid: a clipped casting with a top bevel and a darker foot."""
    centre = np.array([8, 8, 8])
    sections = []
    for y, half_x, half_z, end_cut in (
        (-.82, 2.27, 1.03, .36), (-.58, 2.62, 1.25, .46),
        (.40, 2.45, 1.17, .46), (.75, 2.17, .92, .36),
    ):
        x, z, k = half_x, half_z, end_cut
        outline = ((x - k, -z), (x, -z + k), (x, z - k), (x - k, z),
                   (-x + k, z), (-x, z - k), (-x, -z + k), (-x + k, -z))
        sections.append(np.array([(px, y, pz) for px, pz in outline]))

    triangles = []
    def pose(point):
        # Pitch the item toward the observation window, clear of both jaws.
        point = rot(point, "z", -12, (0, 0, 0))
        point = rot(point, "x", -10, (0, 0, 0))
        return rot(point, "y", -14, (0, 0, 0))

    def swatch(rows, tones):
        rgba = [(*bytes.fromhex(tone), 255) for tone in tones]
        return np.array([[rgba[int(t)] for t in row] for row in rows], dtype=np.uint8)

    top = swatch(("12211110", "12332211", "11223321", "01122211"),
                 ("d6dfe1", "e3e8e7", "edf0ed", "ffffff"))
    flank = swatch(("12222111", "11111221", "00111111"), ("a6b1bc", "b2bec7", "c4ced3"))

    def surface(vertices, hex_color, pixels=None, axes=None):
        normal = np.cross(vertices[1] - vertices[0], vertices[2] - vertices[0])
        normal /= np.linalg.norm(normal)
        if np.dot(normal, np.mean(vertices, axis=0)) < 0:
            normal = -normal
        points = np.array([pose(v * .86) for v in vertices]) + centre
        uv = np.zeros((len(vertices), 2))
        if pixels is None:
            pixels = np.array([[(*bytes.fromhex(hex_color), 255)]], dtype=np.uint8)
        else:
            plane = vertices[:, axes]
            uv = (plane - plane.min(axis=0)) / np.ptp(plane, axis=0)
            uv[:, 1] = 1 - uv[:, 1]
        posed_normal = pose(normal)
        for i in range(1, len(points) - 1):
            idx = [0, i, i + 1]
            triangles.append((points[idx], uv[idx], posed_normal, pixels, False, False))

    surface(sections[0], "747d89")
    surface(sections[-1], None, top, [0, 2])
    tones = (("858c99", "75808c"), ("b4bec7", "aab4bf"), ("e1e6e8", "d2d9de"))
    for level, (lower, upper) in enumerate(zip(sections, sections[1:])):
        # Distinct silver facets make the cut ends, broad side and crown readable
        # through either tint, without emission or a painted outline.
        for i in range(8):
            j = (i + 1) % 8
            vertices = np.array([lower[i], lower[j], upper[j], upper[i]])
            if level == 1 and i in (3, 7):
                surface(vertices, None, flank, [0, 1])
            else:
                surface(vertices, tones[level][i % 2])
    return triangles


def render(quads, eye, scale, size, background, *, item_gui=False):
    """Textured quads and triangles, opaque depth first and glass sorted per pixel."""
    sampling = 1 if item_gui else 2
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
        a, b = points[1] - points[0], points[-1] - points[0]
        det = a[0] * b[1] - a[1] * b[0]
        if abs(det) < 1e-8:
            continue
        yy, xx = np.ogrid[y0:y1, x0:x1]
        dx, dy = xx + .5 - points[0, 0], yy + .5 - points[0, 1]
        s, t = (dx * b[1] - dy * b[0]) / det, (dy * a[0] - dx * a[1]) / det
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if len(points) == 3:
            inside &= s + t <= 1
        z = points[0, 2] + s * a[2] + t * b[2]
        texel = uv[0] + s[..., None] * (uv[1] - uv[0]) + t[..., None] * (uv[-1] - uv[0])
        th, tw = pixels.shape[:2]
        indices = np.clip((texel * [tw, th]).astype(int), 0, [tw - 1, th - 1])
        rgba = pixels[indices[:, :, 1], indices[:, :, 0]]
        if item_gui:
            # Entity item shaders use minecraft_mix_light, even at full GUI lightmap.
            shade = min(1., .4 + .6 * np.maximum(GUI_LIGHTS @ normal, 0.).sum())
        else:
            nx, ny, nz = normal
            shade = 1 if unlit else .6 * nx ** 2 + (1 if ny > 0 else .5) * ny ** 2 + .8 * nz ** 2
        color = np.rint(rgba[:, :, :3] * shade).astype(np.uint8)
        # The layered cutout and translucent entity shaders discard alpha < 0.1.
        mask = inside & (rgba[:, :, 3] >= (26 if item_gui else 1))
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


def inventory_model():
    item = json.loads(ITEM_MODEL.read_text(encoding="utf8"))
    namespace, ref = item["parent"].split(":")
    model_path = ASSETS / "models" / (ref + ".json")
    model = json.loads(model_path.read_text(encoding="utf8"))
    if namespace != "mio_icif" or model_path.parent != MODELS or model["parent"] != "minecraft:block/block":
        raise ValueError(f"Unexpected machine item parent chain: {item['parent']} / {model['parent']}")
    gui = item.get("display", {}).get("gui", model.get("display", {}).get("gui", BLOCK_GUI))
    return model_path.stem, gui


def inventory_projection(bank, density=1, *, pose=None):
    """Render the item parent directly at the selected Minecraft GUI pixel density."""
    model_name, selected_gui = inventory_model()
    gui = pose or selected_gui
    rx, ry, rz = gui.get("rotation", (0, 0, 0))
    scale = np.array(gui.get("scale", (1, 1, 1)))
    translation = np.array(gui.get("translation", (0, 0, 0)))
    # ItemTransform.apply uses JOML rotationXYZ (Z, then Y, then X on a point).
    basis = []
    for unit in np.eye(3):
        for axis, angle in (("z", rz), ("y", ry), ("x", rx)):
            unit = rot(unit, axis, angle, (0, 0, 0))
        basis.append(unit)
    rotation = np.array(basis)
    projected = []
    for corners, uv, normal, pixels, translucent, light in faces(model_name, bank, item=True):
        vertices = ((corners - 8) * scale) @ rotation + translation + 8
        norm = (normal / scale) @ rotation
        projected.append((vertices, uv, norm / np.linalg.norm(norm), pixels, translucent, light))
    return render(projected, (0, 0, 1), density, (16 * density, 16 * density), SLOT_GRAY, item_gui=True)


def inventory_slot(sheet, icon, xy):
    density = icon.width // 16
    size = 18 * density
    slot = Image.new("RGB", (size, size), SLOT_GRAY)
    pen = ImageDraw.Draw(slot)
    pen.rectangle((0, 0, size - 1, density - 1), fill="#373737")
    pen.rectangle((0, density, density - 1, size - 1), fill="#373737")
    pen.rectangle((density, size - density, size - 1, size - 1), fill="#ffffff")
    pen.rectangle((size - density, density, size - 1, size - 1), fill="#ffffff")
    slot.paste(icon, (density, density))
    sheet.paste(slot, xy)


def make_sheet(font_path=None):
    sheet = Image.new("RGB", (1440, 1310), PAPER)
    draw = ImageDraw.Draw(sheet)
    fonts = tuple(find_font(font_path, bold) for bold in (False, True))

    @lru_cache(maxsize=None)
    def font(size, bold):
        return ImageFont.truetype(fonts[bold], size)

    def label(x, y, string, size, color=INK, bold=False, anchor="lm"):
        draw.text((x, y), string, font=font(size, bold), fill=color, anchor=anchor)

    label(42, 50, "分子重组仪", 43, bold=True)
    label(300, 56, "装料示意与物品栏投影", 22, MUTED)
    label(1396, 56, "奇点迭代   /   机器美术", 20, MUTED, anchor="rm")

    width, hero_y = 669, 98
    variants = []
    ingot = sample_ingot()
    for version, bank in enumerate(VARIANTS):
        idle, active = (faces(name, bank) for name in (MACHINE, MACHINE + "_on"))
        variants.append((idle, active))
        left = 42 + version * 686
        draw.rounded_rectangle((left, hero_y, left + width, 478), 10, fill=STAGE)
        draw.rectangle((left + 23, 120, left + 27, 149), fill=ACCENTS[version])
        label(left + 39, 134, LABELS[version], 26, "#fbfcf9", True)
        label(left + width - 24, 135, "工作态 · 铁锭样品示意", 18, PALE, anchor="rm")
        sheet.paste(render(active + ingot, LOADED_VIEW, 15.0, (415, 311), STAGE), (left + 9, 161))
        draw.line((left + 427, 178, left + 427, 443), fill="#455157")
        label(left + 473, 188, "物品栏 · 待机", 20, "#ecf1f0", True)
        inventory_slot(sheet, inventory_projection(bank, 8), (left + 473, 219))
        label(left + 473, 385, "GUI 8× · 离线渲染", 15, PALE)
        inventory_slot(sheet, inventory_projection(bank), (left + 473, 415))
        label(left + 504, 423, "GUI 1× · 16 像素", 15, PALE)

    # The middle slot changes just the rotation, showing what the angle contributes
    # independently of the larger final scale. All three use the same idle assets.
    _, selected_gui = inventory_model()
    angle_only = dict(BLOCK_GUI, rotation=selected_gui["rotation"])
    label(42, 543, "物品栏视角对照", 27, bold=True)
    label(43, 583, "同一青蓝待机模型 · GUI 5×", 17, MUTED)
    for i, (caption, gui) in enumerate((("方块默认", BLOCK_GUI), ("只调角度", angle_only), ("最终摆位", selected_gui))):
        left = 362 + i * 345
        inventory_slot(sheet, inventory_projection(VARIANTS[0], 5, pose=gui), (left, 512))
        pitch, yaw, _ = gui["rotation"]
        side = "北偏东" if yaw >= 180 else "北偏西"
        label(left + 105, 527, caption, 20, bold=True)
        label(left + 105, 558, f"俯视 {pitch}° · {side} {abs(yaw-180)}°", 16, MUTED)
        label(left + 105, 585, f"模型比例 {gui['scale'][0]:g}", 16, MUTED)
    draw.line((42, 627, 1397, 627), fill=LINE)

    label(42, 684, "方块模型四视图", 27, bold=True)
    label(1397, 687, "空腔  /  正面朝北", 17, MUTED, anchor="rm")
    for state, state_text in enumerate(("待机", "工作")):
        for version, states in enumerate(variants):
            left, top = 42 + version * 686, 719 + state * 178
            draw.rounded_rectangle((left, top, left + width, top + 166), 7, fill=VIEW_SURFACE, outline=LINE)
            for i, (eye, caption) in enumerate(zip(VIEWS, ("正面", "西侧", "顶部", "斜视"))):
                mid = left + 84 + i * 166
                sheet.paste(render(states[state], eye, 4.5, (139, 109), VIEW_SURFACE), (mid - 70, top + 2))
                label(mid, top + 120, caption, 15, MUTED, anchor="mm")
            draw.line((left + 16, top + 135, left + width - 16, top + 135), fill=LINE)
            label(left + 18, top + 150, LABELS[version] + "  ·  " + state_text, 15, INK, True)

    # Use the original compressor model and its atlas UVs, at the same world-unit
    # scale, centre and camera as the two candidate machines alongside it.
    top = 1099
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
