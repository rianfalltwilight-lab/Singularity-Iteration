#!/usr/bin/env python3
"""Render the optional horizontal beamline pack beside the retained MT1 machine.

Run from any directory with Python, NumPy, Pillow and a Chinese font.
"""
import argparse
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

from mt1_preview import (
    ACCENTS, COMPRESSOR, INK, LABELS, LINE, MACHINE, MUTED, PALE, PAPER, ROOT, STAGE,
    VARIANTS as RETAINED_TEXTURES, VIEW_SURFACE, faces, find_font,
    inventory_projection, inventory_slot, render, sample_ingot,
)


BASE = ROOT / "docs/art/mt1/beamline"
ASSETS = BASE / "pack/assets/mio_icif"
MODELS = ASSETS / "models/block/producer"
TEXTURES = (ASSETS / "textures", BASE / "variant_b")
OUTPUT = ROOT / "docs/art/previews/MT1-beamline.png"
VIEWS_OUTPUT = ROOT / "docs/art/previews/MT1-beamline-views.png"
LOADED_EYE = (-.32, .26, -1.)
EMPTY_EYE = (.50, .29, -1.)
SAMPLE = (8, 9, 8)


def page(size, font_path):
    sheet = Image.new("RGB", size, PAPER)
    draw = ImageDraw.Draw(sheet)
    fonts = [find_font(font_path, bold) for bold in (False, True)]

    @lru_cache(maxsize=None)
    def font(size, bold):
        return ImageFont.truetype(fonts[bold], size)

    def label(x, y, message, size, color=INK, bold=False, anchor="lm"):
        draw.text((x, y), message, font=font(size, bold), fill=color, anchor=anchor)
    return sheet, draw, label


def make_sheet(font_path=None):
    sheet, draw, label = page((1440, 994), font_path)
    label(40, 55, "分子重组仪", 40, bold=True)
    label(278, 58, "横向束线方案", 27, MUTED)
    label(1399, 57, "工作态的铁锭仅作装料示意", 18, MUTED, anchor="rm")

    ingot = sample_ingot(centre=SAMPLE)
    states = []
    for index, textures in enumerate(TEXTURES):
        idle = faces(MACHINE, textures, models=MODELS)
        working = faces(MACHINE + "_on", textures, models=MODELS)
        states.append((idle, working))
        x = 40 + 686 * index
        draw.rounded_rectangle((x, 103, x + 673, 474), 8, fill=STAGE)
        draw.rectangle((x + 22, 126, x + 26, 151), fill=ACCENTS[index])
        label(x + 38, 138, LABELS[index], 24, "#fafcfb", True)
        label(x + 444, 139, "工作态", 17, PALE, anchor="rm")
        sheet.paste(render(working + ingot, LOADED_EYE, 17.8, (443, 298), STAGE, centre=(8, 6.5, 8)), (x + 8, 162))
        draw.line((x + 456, 163, x + 456, 440), fill="#495559")
        label(x + 488, 175, "待机物品栏", 19, "#fafcfb", True)
        big = inventory_projection(textures, 8, assets=ASSETS)
        small = inventory_projection(textures, assets=ASSETS)
        inventory_slot(sheet, big, (x + 488, 206))
        label(x + 488, 373, "GUI 8× · 离线渲染", 15, PALE)
        inventory_slot(sheet, small, (x + 488, 400))
        label(x + 518, 408, "1× · 16 像素", 15, PALE)

    label(40, 521, "空腔状态", 26, bold=True)
    label(1399, 524, "从另一侧看接收面", 17, MUTED, anchor="rm")
    draw.rounded_rectangle((40, 553, 1399, 748), 8, fill=VIEW_SURFACE)
    for index, pair in enumerate(states):
        for state_index, quads in enumerate(pair):
            mid = 209 + (index * 2 + state_index) * 340
            if index + state_index:
                draw.line((mid - 170, 573, mid - 170, 726), fill=LINE)
            sheet.paste(render(quads, EMPTY_EYE, 8., (244, 148), VIEW_SURFACE, centre=(8, 6.6, 8)), (mid - 122, 562))
            label(mid, 724, f"{LABELS[index]}  ·  {('待机', '工作')[state_index]}", 17, anchor="mm")

    label(40, 828, "同尺度轮廓", 26, bold=True)
    label(41, 867, "青蓝工作态 · 空腔", 17, MUTED)
    silhouette_eye = (-.22, .20, -1.)
    retained = faces(MACHINE + "_on", RETAINED_TEXTURES[0])
    for i, (quads, caption) in enumerate(((retained, "保留版 · 立式三向电极"), (states[0][1], "候选版 · 横向束线"))):
        mid = 623 + 431 * i
        sheet.paste(render(quads, silhouette_eye, 8.6, (267, 173), PAPER), (mid - 133, 767))
        label(mid, 959, caption, 18, anchor="mm")
    return sheet


def make_views_sheet(font_path=None):
    sheet, draw, label = page((1440, 944), font_path)
    label(40, 54, "横向束线", 40, bold=True)
    label(243, 59, "方块四视图", 27, MUTED)
    label(1399, 57, "分子重组仪 · 空腔", 18, MUTED, anchor="rm")
    views = (((0, 0, -1), "正面 · 北"), ((-1, 0, 0), "侧面 · 西"), ((0, 1, 0), "顶面"), (LOADED_EYE, "斜视"))
    for palette, textures in enumerate(TEXTURES):
        left = 40 + palette * 686
        for state, title in enumerate(("待机", "工作")):
            top = 100 + state * 239
            quads = faces(MACHINE + ("_on" if state else ""), textures, models=MODELS)
            draw.rounded_rectangle((left, top, left + 673, top + 222), 8, fill=VIEW_SURFACE)
            label(left + 21, top + 29, f"{LABELS[palette]}  ·  {title}", 22, bold=True)
            for i, (eye, caption) in enumerate(views):
                mid = left + 85 + i * 167
                picture = render(quads, eye, 6.6, (151, 137), VIEW_SURFACE, centre=(8, 6.5, 8))
                sheet.paste(picture, (mid - 75, top + 48))
                label(mid, top + 199, caption, 16, MUTED, anchor="mm")

    draw.line((40, 595, 1399, 595), fill=LINE)
    label(40, 643, "方块尺寸对照", 27, bold=True)
    label(41, 680, "同一视角与比例", 17, MUTED)
    machines = ((faces(MACHINE + "_on", TEXTURES[0], models=MODELS), "A 青蓝 · 工作"),
                (faces(COMPRESSOR, RETAINED_TEXTURES[0]), "奇点迭代 · 电动压缩机"),
                (faces(MACHINE + "_on", TEXTURES[1], models=MODELS), "B 紫粉 · 工作"))
    for i, (quads, caption) in enumerate(machines):
        mid = 483 + i * 345
        picture = render(quads, LOADED_EYE, 10., (244, 220), PAPER)
        sheet.paste(picture, (mid - 122, 656))
        label(mid, 900, caption, 18, anchor="mm")
    return sheet


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", help="path to a Chinese TrueType/OpenType font")
    options = parser.parse_args()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    for make, path in ((make_sheet, OUTPUT), (make_views_sheet, VIEWS_OUTPUT)):
        make(options.font).save(path, optimize=True)
        print(path.relative_to(ROOT))
