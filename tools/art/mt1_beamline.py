#!/usr/bin/env python3
"""Build the independent MT1 beamline pack. The beam travels from +X to -X."""
import json
from pathlib import Path

from PIL import Image

from mt1_model import GRAPHITE, PALETTES, PORCELAIN, SEAM, SIDES, STEEL, canvas


ROOT = Path(__file__).resolve().parents[2]
TRIAL = ROOT / "docs/art/mt1/beamline"
ASSETS = TRIAL / "pack/assets/mio_icif"
PARTS = Path("block/producer/mt1_beamline")
REF = "mio_icif:" + PARTS.as_posix() + "/"


def neutral_parts():
    result = {
        "porcelain": Image.new("RGBA", (16, 16), PORCELAIN),
        "steel": Image.new("RGBA", (16, 16), STEEL),
        "graphite": Image.new("RGBA", (16, 16), GRAPHITE),
    }

    # Top of the optical bench: a single machined locating strip beneath each
    # mount. No stripe or illuminated beam runs between the instrument heads.
    img, d = canvas(PORCELAIN)
    for x in (2, 13):
        d.line((x, 3, x, 6), fill=SEAM)
    result["bench"] = img

    # The source's outboard plate contains its supply connector. The back of
    # the thinner detector carries a smaller, flush square calibration cover.
    img, d = canvas(PORCELAIN)
    d.rectangle((1, 1, 6, 6), outline=SEAM)
    d.rectangle((3, 3, 4, 4), fill=GRAPHITE)
    d.rectangle((10, 2, 13, 5), outline=SEAM)
    result["service"] = img

    # Two 5x5 faces seen along the beam axis. A fine transverse slit in the
    # source, and two jaws enclosing the detector aperture, mark its depth.
    img, d = canvas(STEEL)
    d.rectangle((1, 1, 3, 3), fill="#737478")
    d.line((0, 2, 4, 2), fill=GRAPHITE)
    d.rectangle((9, 1, 11, 3), fill=GRAPHITE)
    d.line((8, 2, 12, 2), fill=GRAPHITE)
    d.point((10, 1), fill="#737478")
    d.point((10, 3), fill="#737478")
    # The two small front lips continue the etched slit to the player side.
    d.line((0, 10, 1, 10), fill=GRAPHITE)
    result["aperture"] = img

    # The bent observation cover is open at the two optical ports. Keep the
    # centre of each pane clear; the tiny reflected edge marks carry its shape.
    img, d = canvas((171, 201, 203, 20))
    for u in (0, 8):
        d.line((u, 0, u + 5, 0), fill=(159, 185, 190, 73))
        d.line((u, 7, u + 5, 7), fill=(130, 156, 162, 54))
        d.line((u, 1, u, 6), fill=(176, 200, 202, 45))
        d.line((u + 5, 1, u + 5, 6), fill=(143, 169, 176, 42))
    for x, y, alpha in ((0, 3, 74), (1, 2, 105), (2, 1, 79)):
        d.point((x, y), fill=(225, 243, 246, alpha))
    d.rectangle((0, 9, 5, 14), outline=(153, 179, 186, 56))
    result["hood_glass"] = img
    return result


def signals(palette):
    result = {}
    for state, colors in palette.items():
        outer, _, lip, surface, contact, peak = colors
        img, d = canvas((0, 0, 0, 0))
        # Brightest point at the optical centre. The receiving aperture has
        # the same centre and a quieter pilot line, visible from both ends.
        for u, color in enumerate((lip, surface, peak, contact, lip)):
            d.point((u, 2), fill=color)
        for u, color in enumerate((outer, lip, surface, lip, outer), 8):
            d.point((u, 2), fill=color)
        d.line((0, 10, 1, 10), fill=lip)
        d.line((8, 10, 9, 10), fill=outer)
        result["slits" + ("_on" if state == "on" else "")] = img
    return result


def model(working):
    layers = {"solid": [], "cutout": [], "translucent": []}

    def skin(directions, texture, u=0, v=0):
        return dict.fromkeys(directions, (texture, u, v))

    def box(name, lo, hi, faces, layer="solid"):
        dx, dy, dz = (b - a for a, b in zip(lo, hi))
        spans = {"north": (dx, dy), "south": (dx, dy), "west": (dz, dy),
                 "east": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
        edges = {"north": lo[2] == 0, "south": hi[2] == 16, "west": lo[0] == 0,
                 "east": hi[0] == 16, "up": hi[1] == 16, "down": lo[1] == 0}
        data = {}
        for face, (tex, u, v) in faces.items():
            w, h = spans[face]
            props = {"texture": "#" + tex, "uv": [u, v, u + w, v + h]}
            if edges[face]:
                props["cullface"] = face
            if layer == "cutout" and working:
                props["neoforge_data"] = {"block_light": 15, "sky_light": 15}
            data[face] = props
        layers[layer].append({"name": name, "from": lo, "to": hi, "faces": data})

    for name, x in (("detector", 1), ("source", 11)):
        box(name + "_foot", [x, 0, 4], [x + 4, 1, 12],
            skin((*SIDES, "down"), "graphite"))
    box("ceramic_bench", [0.5, 1, 3], [15.5, 3, 13], {
        **skin((*SIDES, "down"), "porcelain"), "up": ("bench", 0, 0)})

    box("source_mount", [13.5, 3, 6], [15.5, 5, 10], skin(SIDES, "steel"))
    box("detector_mount", [1.5, 3, 6.5], [3.5, 5.5, 9.5], skin(SIDES, "steel"))
    box("sample_stage", [4.5, 3, 4.5], [11.5, 4, 11.5], {
        **skin(SIDES, "steel"), "up": ("porcelain", 0, 0)})

    # North-facing player sees the source on the left (+X). The broad body
    # and two successively smaller stages house the source optics. The single
    # plate on the right carries a compact receiving aperture.
    box("source_housing", [13.5, 5, 4], [16, 13, 12], {
        **skin((*SIDES, "up", "down"), "porcelain"), "east": ("service", 0, 0)})
    box("source_shoulder", [12.5, 6, 5], [13.5, 12, 11],
        skin(("west", "north", "south", "up", "down"), "porcelain"))
    box("detector_housing", [1.5, 5.5, 4.5], [3.5, 12.5, 11.5], {
        **skin((*SIDES, "up", "down"), "porcelain"), "west": ("service", 8, 0)})

    light = "slits_on" if working else "slits"
    bias = 1 / 64
    for name, lo, hi, inside, u, offset in (
            ("source", 11, 12.5, "west", 0, -bias),
            ("detector", 3.5, 4.5, "east", 8, bias)):
        box(name + "_aperture", [lo, 6.5, 5.5], [hi, 11.5, 10.5], {
            inside: ("aperture", u, 0), "north": ("aperture", 0, 8),
            **skin(("south", "up", "down"), "steel")})
        box(name + "_slit_glow",
            [lo + offset, 6.5, 5.5 - bias], [hi + offset, 11.5, 10.5 - bias],
            {inside: (light, u, 0), "north": (light, u, 8)}, layer="cutout")

    # This one-piece inverted U covers the stage. The sample box
    # [5.5,6.5,5.5]..[10.5,11.5,10.5] and the horizontal beam stay empty.
    box("sample_hood_glass", [5, 4, 5], [11, 12, 11], {
        "north": ("hood_glass", 0, 0), "south": ("hood_glass", 8, 0),
        "up": ("hood_glass", 0, 9)}, layer="translucent")

    children = {}
    for layer, elements in layers.items():
        keys = sorted({f["texture"][1:] for e in elements for f in e["faces"].values()})
        children[layer] = {
            "parent": "minecraft:block/block", "render_type": "minecraft:" + layer,
            "textures": {key: REF + key for key in keys}, "elements": elements,
        }
    return {"parent": "minecraft:block/block", "loader": "neoforge:composite",
            "textures": {"particle": REF + "porcelain"}, "children": children}


def main():
    neutral = neutral_parts()
    for directory, palette in ((ASSETS / "textures", "a"), (TRIAL / "variant_b", "b")):
        target = directory / PARTS
        target.mkdir(parents=True, exist_ok=True)
        for name, img in {**neutral, **signals(PALETTES[palette])}.items():
            img.save(target / (name + ".png"), optimize=True)
    target = ASSETS / "models/block/producer"
    target.mkdir(parents=True, exist_ok=True)
    for state in (False, True):
        path = target / ("block_molecular_transformer" + ("_on" if state else "") + ".json")
        with path.open("w", encoding="utf8", newline="\n") as out:
            json.dump(model(state), out, indent=2)
            out.write("\n")


if __name__ == "__main__":
    main()
