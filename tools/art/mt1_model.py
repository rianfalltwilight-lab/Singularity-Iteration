#!/usr/bin/env python3
"""Generate the molecular transformer's Java block models and both part-texture sets."""
import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/mio_icif"
PARTS = Path("block/producer/molecular_transformer")
TEXTURE_REF = "mio_icif:" + PARTS.as_posix() + "/"
SIDES = ("north", "east", "south", "west")
ALL = (*SIDES, "up", "down")
DARK, IRON, STEEL, MID = "#2A2A2C", "#464648", "#606063", "#8E8E92"
SHADOW, EDGE, SEAM = "#838383", "#AEAEAE", "#D0D0D0"
WHITE, PEARL, LIT, GLINT = "#DFDFDF", "#ECEAEB", "#F1F1F1", "#FBFBFB"
PALETTES = {
    "a": {
        "off": ("#26353D", "#304B58", "#386474", "#437B86", "#508F94", "#73AAAA"),
        "on": ("#145267", "#137F98", "#3DBBE3", "#02D1F3", "#2ADED3", "#B4FFF3"),
    },
    "b": {
        "off": ("#342D3C", "#4C3957", "#62476C", "#765483", "#875D8D", "#AC83A4"),
        "on": ("#563165", "#86489B", "#B46BD8", "#D673EF", "#ED95D8", "#FFC7F0"),
    },
}


def canvas(color):
    img = Image.new("RGBA", (16, 16), color)
    return img, ImageDraw.Draw(img)


def neutral_parts():
    textures = {}

    # Horizontal strips for the plinth/crown fascias, return yokes and desk lip.
    img, d = canvas(WHITE)
    for row, tint in enumerate((LIT, WHITE, EDGE, PEARL, SEAM, GLINT, WHITE, EDGE,
                                IRON, STEEL, MID, "#9F9E9E", PEARL, WHITE, EDGE, SHADOW)):
        d.line((0, row, 15, row), fill=tint)
    for x in (1, 14):
        d.point((x, 1), fill=SHADOW)
    for x in (3, 12):
        d.point((x, 3), fill=SEAM)
    d.line((6, 6, 9, 6), fill=PEARL)
    d.point((1, 6), fill="#9F9E9E")
    d.point((12, 6), fill="#9F9E9E")
    textures["shell"] = img

    # A single casting: pale outer flange around a graphite pressure seal.
    img, d = canvas(PEARL)
    d.rectangle((0, 0, 15, 15), outline=SEAM)
    d.line((1, 0, 14, 0), fill=LIT)
    d.line((0, 1, 0, 14), fill=WHITE)
    d.rectangle((2, 2, 13, 13), fill=EDGE, outline=WHITE)
    d.rectangle((3, 2, 12, 13), fill=IRON)
    d.rectangle((2, 4, 13, 11), fill=IRON)
    d.rectangle((3, 4, 12, 11), fill=STEEL)
    d.rectangle((4, 4, 11, 11), fill=MID)
    for x, z in ((1, 1), (14, 1), (1, 14), (14, 14)):
        d.point((x, z), fill=SHADOW)
    textures["deck"] = img

    # Upper service access: one recessed pair of transverse bus-bar channels.
    img, d = canvas(PEARL)
    d.rectangle((3, 3, 12, 12), fill=SEAM)
    d.rectangle((4, 3, 11, 11), fill=WHITE)
    d.line((5, 3, 10, 3), fill=GLINT)
    for x in (5, 9):
        d.rectangle((x, 5, x + 1, 9), fill=EDGE)
        d.line((x, 5, x, 8), fill=IRON)
        d.point((x, 5), fill=STEEL)
        d.point((x + 1, 9), fill=PEARL)
    d.point((4, 10), fill=SHADOW)
    d.point((11, 4), fill=SHADOW)
    textures["service_hatch"] = img

    # Hardware atlas. The top two rows are winding laminations, the tall panel
    # below them is the exposed return spine, the bottom rows are the dark lip.
    img, d = canvas(IRON)
    d.line((0, 0, 15, 0), fill=MID)
    d.line((0, 1, 15, 1), fill=STEEL)
    d.rectangle((0, 2, 15, 3), fill="#9F9E9E")
    for x in (1, 4, 7, 10, 13):
        d.line((x, 0, x, 1), fill=IRON)
        d.point((x, 2), fill=STEEL)
    for y in range(5, 12, 2):
        d.line((0, y, 15, y), fill=STEEL)
    d.line((0, 12, 15, 12), fill=IRON)
    d.line((0, 13, 15, 13), fill=DARK)
    d.line((0, 14, 15, 14), fill=STEEL)
    d.line((0, 15, 15, 15), fill=SHADOW)
    textures["hardware"] = img

    # Inner segmented pressure liner. Its paired, broken field traces are a
    # separate single-sided mask so that the dark wall still receives shading.
    img, d = canvas(IRON)
    d.rectangle((0, 0, 9, 9), fill=STEEL, outline=IRON)
    d.polygon([(3, 1), (6, 1), (8, 3), (8, 6), (6, 8), (3, 8),
               (1, 6), (1, 3)], fill=DARK, outline=IRON)
    for x in (0, 9):
        d.point((x, 1), fill=MID)
        d.point((x, 8), fill=MID)
    textures["chamber"] = img

    img, d = canvas((174, 202, 207, 16))
    d.line((0, 0, 9, 0), fill=(160, 184, 194, 55))
    d.line((0, 1, 0, 8), fill=(186, 205, 209, 48))
    d.line((9, 1, 9, 9), fill=(132, 155, 164, 43))
    d.line((0, 9, 8, 9), fill=(123, 148, 156, 42))
    for pos, a in (((1, 2), 83), ((2, 1), 85), ((1, 3), 40), ((2, 2), 41),
                   ((3, 1), 36), ((7, 8), 49), ((8, 7), 45)):
        d.point(pos, fill=(233, 244, 245, a))
    textures["optical_glass"] = img
    return textures


def field_parts(palette):
    textures = {}
    for state, colors in palette.items():
        suffix = "_on" if state == "on" else ""
        img, d = canvas(colors[0])
        # Individual facets of the tilted sample cube have different highlights.
        motifs = (
            ("2441", "4532", "3241", "2110"), ("2230", "3421", "2131", "1100"),
            ("1441", "3432", "2311", "2100"), ("2321", "3411", "2130", "1100"),
            ("3452", "4523", "3252", "2321"), ("1210", "2321", "1120", "1000"),
        )
        for i, motif in enumerate(motifs):
            u, v = (i % 4) * 4, (i // 4) * 4
            for y, row in enumerate(motif):
                for x, value in enumerate(row):
                    d.point((u + x, v + y), fill=colors[int(value)])
        textures["crystal" + suffix] = img

        img, d = canvas((0, 0, 0, 0))
        # Two short opposed arcs on the rear liner leave open space around the sample.
        for x, mirror in ((1, 1), (8, -1)):
            for dx, y, shade in ((1, 2, 1), (0, 3, 2), (0, 4, 4),
                                 (0, 5, 2), (0, 6, 1), (1, 7, 1)):
                d.point((x + dx * mirror, y), fill=colors[shade])
        # Three front status cells share the mask atlas but have independent UVs.
        for x, value in enumerate((2, 4, 5)):
            d.point((x, 12), fill=colors[value])
        textures["field_marks" + suffix] = img
    return textures


def model(working):
    layers = {"solid": [], "cutout": [], "translucent": []}
    glow = "_on" if working else ""

    def box(name, lo, hi, faces, layer="solid", rotation=None, glowing=()):
        # Face material entries are (texture, atlas U, atlas V). Calculate each
        # UV span from the box to maintain one texture pixel per world unit.
        dx, dy, dz = (b - a for a, b in zip(lo, hi))
        extent = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy),
                  "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
        boundary = dict(zip(ALL, (lo[2] == 0, hi[0] == 16, hi[2] == 16,
                                  lo[0] == 0, hi[1] == 16, lo[1] == 0)))
        element = {"name": name, "from": lo, "to": hi}
        if rotation is not None:
            element["rotation"] = rotation
        data = {}
        for direction, (tex, u, v) in faces.items():
            width, height = extent[direction]
            face = {"uv": [u, v, u + width, v + height], "texture": "#" + tex}
            if boundary[direction] and rotation is None:
                face["cullface"] = direction
            if direction in glowing and working:
                face["neoforge_data"] = {"block_light": 15, "sky_light": 15}
            data[direction] = face
        element["faces"] = data
        layers[layer].append(element)

    def skin(directions, material, u, v):
        return dict.fromkeys(directions, (material, u, v))

    box("isolation_foot", [1, 0, 1], [15, 1, 15], {
        **skin(SIDES, "hardware", 0, 13), "down": ("deck", 1, 1)})
    box("lower_casting", [0, 1, 0], [16, 3, 16], {
        **skin(SIDES, "shell", 0, 0), "up": ("deck", 0, 0), "down": ("shell", 0, 0)})
    box("upper_casting", [1, 13, 1], [15, 15, 15], {
        **skin(SIDES, "shell", 0, 5), "up": ("deck", 1, 1), "down": ("deck", 1, 1)})
    box("service_hatch", [3, 15, 3], [13, 16, 13], {
        **skin(SIDES, "shell", 0, 3), "up": ("service_hatch", 3, 3)})

    # Exterior C-shaped return yokes. Only the narrow rear spine links the two
    # shoulders; the centre and the front of each side window stay unobstructed.
    for side, x in (("left", 0), ("right", 14)):
        for end, y, exposed in (("lower", 3, "up"), ("upper", 11, "down")):
            faces = {**skin(("north", "south"), "hardware", 0, 0),
                     **skin(("west", "east"), "shell", 0, 12),
                     exposed: ("hardware", 0, 3)}
            if end == "upper":
                # Half of the upper shoulder projects past the edge of the cover.
                faces["up"] = ("shell", 0, 5)
            box(f"{side}_{end}_return", [x, y, 5], [x + 2, y + 2, 13], faces)
        box(side + "_return_spine", [x, 5, 10], [x + 2, 11, 13],
            skin(SIDES, "hardware", 0, 4))

    # Four paired pole shoes taper from the seat toward the suspended sample.
    for side, x, tip in (("left", 4, 4), ("right", 9, 10)):
        for end, y, seat_y, exposed in (("lower", 4, 3, "up"), ("upper", 11, 12, "down")):
            box(f"{side}_{end}_pole_seat", [x, seat_y, 5], [x + 3, seat_y + 1, 11],
                {**skin(SIDES, "hardware", 0, 1), exposed: ("hardware", 0, 3)})
            box(f"{side}_{end}_pole_tip", [tip, y, 6], [tip + 2, y + 1, 10],
                {**skin(SIDES, "hardware", 2, 2), exposed: ("shell", 3, 8)})

    box("rear_pressure_liner", [3, 3, 12], [13, 13, 14], {
        "north": ("chamber", 0, 0), "south": ("service_hatch", 3, 3),
        **skin(("east", "west"), "hardware", 1, 3)})
    box("liner_glow", [3, 3, 11.984375], [13, 13, 11.984375],
        {"north": ("field_marks" + glow, 0, 0)}, layer="cutout", glowing=("north",))
    box("status_console", [3, 3, 0.5], [13, 4, 1.5], {
        **skin(SIDES, "hardware", 0, 13), "up": ("hardware", 2, 11)})
    box("status_glow", [6.5, 3, 0.484375], [9.5, 4, 0.484375],
        {"north": ("field_marks" + glow, 0, 12)}, layer="cutout", glowing=("north",))
    box("core_glow", [6, 6, 5], [10, 10, 9],
        {face: ("crystal" + glow, (i % 4) * 4, (i // 4) * 4)
         for i, face in enumerate(ALL)},
        rotation={"origin": [8, 8, 7], "axis": "z", "angle": 45}, glowing=ALL)
    # Single continuous three-sided pane. Deck, lid and pressure liner close
    # the other three sides, so they don't need inward glass surfaces.
    box("observation_glass", [3, 3, 2], [13, 13, 12],
        skin(("north", "west", "east"), "optical_glass", 0, 0), layer="translucent")

    children = {}
    for layer, elements in layers.items():
        materials = sorted({face["texture"][1:] for el in elements for face in el["faces"].values()})
        children[layer] = {
            "parent": "minecraft:block/block", "render_type": "minecraft:" + layer,
            "textures": {name: TEXTURE_REF + name for name in materials}, "elements": elements,
        }
    return {"parent": "minecraft:block/block", "loader": "neoforge:composite",
            "textures": {"particle": TEXTURE_REF + "shell"}, "children": children}


def main():
    neutral = neutral_parts()
    for variant, dest in (("a", ASSETS / "textures"), ("b", ROOT / "docs/art/mt1/variant_b")):
        directory = dest / PARTS
        directory.mkdir(parents=True, exist_ok=True)
        for name, img in {**neutral, **field_parts(PALETTES[variant])}.items():
            img.save(directory / (name + ".png"), optimize=True)

    directory = ASSETS / "models/block/producer"
    for state, name in ((False, "block_molecular_transformer"), (True, "block_molecular_transformer_on")):
        with (directory / (name + ".json")).open("w", encoding="utf8", newline="\n") as out:
            json.dump(model(state), out, indent=2)
            out.write("\n")


if __name__ == "__main__":
    main()
