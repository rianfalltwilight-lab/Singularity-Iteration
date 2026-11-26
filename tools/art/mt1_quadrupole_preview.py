#!/usr/bin/env python3
"""Render the four-corner MT1 candidate and its orthographic review sheet."""
import argparse

import mt1_beamline_preview as beamline
from mt1_preview import (
    ACCENTS, INK, LABELS, LINE, MACHINE, MUTED, PALE, PAPER, ROOT, STAGE,
    VARIANTS as RETAINED_TEXTURES, VIEW_SURFACE, faces, render, sample_ingot,
)


BASE = ROOT / "docs/art/mt1/quadrupole"
ASSETS = BASE / "pack/assets/mio_icif"
MODELS = ASSETS / "models/block/producer"
TEXTURES = (ASSETS / "textures", BASE / "variant_b")
OUTPUT = ROOT / "docs/art/previews/MT1-quadrupole.png"
VIEWS_OUTPUT = ROOT / "docs/art/previews/MT1-quadrupole-views.png"
CAMERA = (-.68, .43, -1.)
SAMPLE = (8, 8, 8)


def make_sheet(font_path=None):
    sheet, draw, label = beamline.page((1440, 910), font_path)
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

    label(40, 607, "上下端部", 25, bold=True)
    label(632, 607, "三种轮廓", 25, bold=True)
    label(1398, 610, "同一视角与比例 · 青蓝工作态", 17, MUTED, anchor="rm")
    draw.rounded_rectangle((40, 646, 589, 870), 8, fill=VIEW_SURFACE)
    draw.line((314, 665, 314, 850), fill=LINE)
    current = faces(MACHINE + "_on", TEXTURES[0], models=MODELS)
    for mid, eye, caption in ((177, (0, 1, 0), "顶视"), (451, (0, -1, 0), "底视")):
        sheet.paste(render(current, eye, 9.7, (183, 173), VIEW_SURFACE), (mid - 92, 651))
        label(mid, 843, caption, 17, INK, anchor="mm")
    comparison = (
        (faces(MACHINE + "_on", RETAINED_TEXTURES[0]), "立式三向电极"),
        (faces(MACHINE + "_on", beamline.TEXTURES[0], models=beamline.MODELS), "横向束线"),
        (current, "四角立式"),
    )
    for i, (geometry, caption) in enumerate(comparison):
        mid = 749 + i * 254
        sheet.paste(render(geometry, CAMERA, 8., (208, 191), PAPER), (mid - 104, 639))
        label(mid, 848, caption, 18, anchor="mm")
    return sheet


def make_views_sheet(font_path=None):
    return beamline.make_views_sheet(font_path, design="四角立式", models=MODELS, banks=TEXTURES,
                                     camera=CAMERA, centre=SAMPLE, view_scale=5.9, compare_scale=9.)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", help="path to a Chinese TrueType/OpenType font")
    options = parser.parse_args()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    for make, path in ((make_sheet, OUTPUT), (make_views_sheet, VIEWS_OUTPUT)):
        make(options.font).save(path, optimize=True)
        print(path.relative_to(ROOT))
