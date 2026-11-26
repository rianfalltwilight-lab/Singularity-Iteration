#!/usr/bin/env python3
"""Render the four-corner MT1 candidate and its orthographic review sheet."""
import argparse
from pathlib import Path

from PIL import Image

import mt1_beamline_preview as beamline
from mt1_preview import (
    ACCENTS, INK, LABELS, MACHINE, MUTED, PALE, PAPER, ROOT, STAGE,
    faces, render, sample_ingot,
)


BASE = ROOT / "docs/art/mt1/quadrupole"
ASSETS = BASE / "pack/assets/mio_icif"
MODELS = ASSETS / "models/block/producer"
TEXTURES = (ASSETS / "textures", BASE / "variant_b")
OUTPUT = ROOT / "docs/art/previews/MT1-quadrupole.png"
VIEWS_OUTPUT = ROOT / "docs/art/previews/MT1-quadrupole-views.png"
REFERENCE = BASE / "reference/c3ff6228-detail.png"
CAMERA = (-.68, .43, -1.)
SAMPLE = (8, 8, 8)


def detail_plate(models, textures, *, working=False):
    """The same north-west ceramic corner and north window in every comparison."""
    geometry = faces(MACHINE + ("_on" if working else ""), textures, models=models)
    plate = Image.new("RGB", (250, 341), PAPER)
    # Isolate one complete L (x,z = 0..7) including its two inward terminals.
    corner = render(geometry, (0, 1, 0), 21., (202, 174), STAGE, centre=(3.5, 16, 3.5))
    top = Image.new("RGB", (250, 174), STAGE)
    top.paste(corner, (24, 0))
    plate.paste(top)
    # The 8×9 glass face fills this crop vertically; the aim marks sit at y=8.
    pane = render(geometry, (0, 0, -1), 16., (250, 155), STAGE, centre=(8, 8, 4))
    plate.paste(pane, (0, 186))
    return plate


def make_sheet(font_path=None):
    sheet, draw, label = beamline.page((1440, 1060), font_path)
    label(40, 53, "分子重组仪", 40, bold=True)
    label(276, 57, "四角立式方案", 27, MUTED)
    label(1399, 57, "工作态的铁锭仅作装料示意", 18, MUTED, anchor="rm")
    ingot = sample_ingot(centre=SAMPLE)
    for index, textures in enumerate(TEXTURES):
        left = 40 + 686 * index
        working = faces(MACHINE + "_on", textures, models=MODELS)
        draw.rounded_rectangle((left, 102, left + 673, 552), 9, fill=STAGE)
        draw.rectangle((left + 22, 127, left + 26, 152), fill=ACCENTS[index])
        label(left + 39, 139, LABELS[index], 24, "#fafcfb", True)
        label(left + 438, 140, "工作态", 17, PALE, anchor="rm")
        sheet.paste(render(working + ingot, CAMERA, 15.2, (438, 379), STAGE), (left + 8, 161))
        draw.line((left + 455, 161, left + 455, 511), fill="#495559")
        beamline.inventory_panel(sheet, label, left + 485, 193, textures, ASSETS)

    label(40, 605, "细节对照", 26, bold=True)
    label(1399, 607, "同角度 · 同尺度", 17, MUTED, anchor="rm")
    label(40, 755, "瓷夹顶面", 21, bold=True)
    label(40, 792, "香槟钛嵌线与端点", 16, MUTED)
    label(40, 930, "正面玻璃", 21, bold=True)
    label(40, 967, "两侧雾白对准刻线", 16, MUTED)
    with Image.open(REFERENCE) as source:
        baseline = source.convert("RGB")
    comparison = (
        ("上一版 · 待机", baseline),
        ("新版 · 待机", detail_plate(MODELS, TEXTURES[0])),
        ("青蓝 · 工作", detail_plate(MODELS, TEXTURES[0], working=True)),
        ("紫粉 · 工作", detail_plate(MODELS, TEXTURES[1], working=True)),
    )
    for i, (caption, detail) in enumerate(comparison):
        left = 244 + i * 302
        label(left + 125, 652, caption, 18, INK, True, anchor="mm")
        sheet.paste(detail, (left, 681))
    return sheet


def make_views_sheet(font_path=None):
    return beamline.make_views_sheet(font_path, design="四角立式", models=MODELS, banks=TEXTURES,
                                     camera=CAMERA, centre=SAMPLE, view_scale=5.9, compare_scale=9.)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", help="path to a Chinese TrueType/OpenType font")
    parser.add_argument("--reference-assets", type=Path, metavar="ASSETS",
                        help="capture the baseline once using the c3ff6228 pack's assets/mio_icif directory")
    options = parser.parse_args()
    if options.reference_assets:
        if not REFERENCE.exists():
            old = options.reference_assets
            REFERENCE.parent.mkdir(parents=True, exist_ok=True)
            detail_plate(old / "models/block/producer", old / "textures").save(REFERENCE, optimize=True)
        print(REFERENCE.relative_to(ROOT))
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        for make, path in ((make_sheet, OUTPUT), (make_views_sheet, VIEWS_OUTPUT)):
            make(options.font).save(path, optimize=True)
            print(path.relative_to(ROOT))
