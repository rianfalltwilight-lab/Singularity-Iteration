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

    # Packed horizontal strips for the central castings and terminal housings.
    img, d = canvas(WHITE)
    for row, tint in enumerate((LIT, WHITE, EDGE, PEARL, SEAM, GLINT, WHITE, EDGE,
                                IRON, STEEL, MID, "#9F9E9E", PEARL, WHITE, EDGE, SHADOW)):
        d.line((0, row, 15, row), fill=tint)
    for x in (1, 14):
        d.point((x, 1), fill="#9F9E9E")
    d.line((3, 8, 12, 8), fill=MID)
    d.line((6, 8, 10, 8), fill=IRON)
    d.line((3, 9, 12, 9), fill=EDGE)
    for x in (3, 12):
        d.point((x, 3), fill=SEAM)
    d.line((6, 6, 9, 6), fill=PEARL)
    d.point((1, 6), fill="#9F9E9E")
    d.point((12, 6), fill="#9F9E9E")
    textures["shell"] = img

    # White flange and a graphite-lined well under the sample sleeve.
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

    # The EV transformer family has a dark central terminal in a square
    # insulating seat, flanked by two rows of contacts on the outer casting.
    img, d = canvas(PEARL)
    d.rectangle((1, 1, 14, 14), outline=WHITE)
    d.line((2, 1, 13, 1), fill=GLINT)
    d.line((1, 2, 1, 13), fill=LIT)
    d.line((3, 4, 3, 11), fill=SEAM)
    d.line((12, 4, 12, 11), fill=SEAM)
    for x in (3, 12):
        for y in (5, 9):
            d.line((x, y, x, y + 1), fill=EDGE)
    d.rectangle((5, 5, 10, 10), fill=WHITE)
    d.rectangle((6, 6, 9, 9), fill=EDGE)
    d.rectangle((7, 7, 8, 8), fill=STEEL)
    d.point((7, 7), fill=MID)
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        d.point((x, y), fill=EDGE)
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

    textures["return_housing"] = terminals()

    # Dark, thick, segmented reflector plates with exposed clamp jaws. UV
    # islands occupy the first 8 columns (surface) and strips at rows 9..12.
    img, d = canvas(IRON)
    d.rectangle((0, 0, 7, 6), fill=STEEL, outline=IRON)
    for x in (1, 4):
        d.rectangle((x, 1, x + 2, 4), fill=MID)
        d.line((x, 1, x + 2, 1), fill="#9F9E9E")
        d.line((x, 3, x + 2, 3), fill=STEEL)
    d.line((0, 9, 15, 9), fill=MID)
    d.line((0, 10, 15, 10), fill=IRON)
    d.line((0, 11, 15, 11), fill=STEEL)
    for x in (0, 7):
        d.point((x, 10), fill=SHADOW)
    textures["reflector"] = img

    # Each 6x8 pane has a pale edge seal, and one short glint away from the
    # specimen. This transmits the real background through all four sides.
    img, d = canvas((162, 204, 207, 29))
    for x in (0, 8):
        d.line((x, 0, x + 5, 0), fill=(170, 194, 197, 72))
        d.line((x, 7, x + 5, 7), fill=(135, 162, 170, 70))
        d.line((x, 1, x, 6), fill=(182, 206, 208, 60))
        d.line((x + 5, 1, x + 5, 6), fill=(133, 163, 168, 52))
    for x, y, alpha in ((0, 3, 82), (1, 2, 110), (2, 1, 84),
                         (11, 2, 78), (12, 1, 64)):
        d.point((x, y), fill=(226, 243, 249, alpha))
    textures["optical_glass"] = img
    return textures


def terminals(signal=None):
    """Paint the three orientations of a clamped, insulated bus contact."""
    if signal is None:
        img, d = canvas(IRON)
        crown = ("8777778", "5066605", "7000007")
        foot = ("8778", "5065", "5665")
        crown_side = ("8777778", "7006007", "5066605")
        foot_side = ("8778", "5665", "7007", "5665")
        colors = (IRON, IRON, IRON, IRON, IRON, STEEL, DARK, SEAM, LIT)
    else:
        img, d = canvas((0, 0, 0, 0))
        crown = (".......", "..343..", "...5...")
        foot = ("....", ".34.", ".25.")
        crown_side = ("...4...", "...3...", "...2...")
        foot_side = (".43.", ".42.", ".32.", "....")
        colors = signal

    def tile(rows):
        result = Image.new("RGBA", (len(rows[0]), len(rows)))
        draw = ImageDraw.Draw(result)
        for y, row in enumerate(rows):
            for x, c in enumerate(row):
                if c != ".":
                    draw.point((x, y), fill=colors[int(c)])
        return result

    for rows, positions in ((crown, ((0, 0), (8, 0), (12, 0))),
                            (foot, ((0, 4), (8, 8), (12, 8)))):
        top = tile(rows)
        img.paste(top, positions[0])
        img.paste(top.transpose(Image.Transpose.ROTATE_270), positions[1])
        img.paste(top.transpose(Image.Transpose.ROTATE_90), positions[2])
    img.paste(tile(crown_side), (0, 9))
    img.paste(tile(foot_side), (0, 12))
    return img


def field_parts(palette):
    textures = {}
    for state, colors in palette.items():
        suffix = "_on" if state == "on" else ""
        img, d = canvas((0, 0, 0, 0))
        # Facing sides and front lips share the same central field contact.
        for x, z, s in ((1, 0, 2), (2, 0, 4), (3, 0, 2),
                        (1, 1, 4), (3, 1, 4), (1, 2, 2), (3, 2, 2)):
            d.point((x, z), fill=colors[s])
            d.point((8 + x, 4 - z), fill=colors[s])
        for x, s in ((1, 2), (2, 4), (3, 2)):
            d.point((x, 8), fill=colors[s])
        for x, s in enumerate((2, 4, 5)):
            d.point((x, 12), fill=colors[s])
        textures["field_marks" + suffix] = img
        textures["electrode_signal" + suffix] = terminals(colors)
    return textures


def model(working):
    layers = {"solid": [], "cutout": [], "translucent": []}
    glow = "_on" if working else ""

    def box(name, lo, hi, faces, layer="solid", glowing=(), angle=0, pivot=None):
        # Face material entries are (texture, atlas U, atlas V). Calculate each
        # UV span from the box to maintain one texture pixel per world unit.
        dx, dy, dz = (b - a for a, b in zip(lo, hi))
        extent = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy),
                  "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
        boundary = dict(zip(ALL, (lo[2] == 0, hi[0] == 16, hi[2] == 16,
                                  lo[0] == 0, hi[1] == 16, lo[1] == 0)))
        element = {"name": name, "from": lo, "to": hi}
        if angle:
            element["rotation"] = {"angle": angle, "axis": "y", "origin": pivot}
        data = {}
        for direction, (tex, u, v) in faces.items():
            width, height = extent[direction]
            face = {"uv": [u, v, u + width, v + height], "texture": "#" + tex}
            if boundary[direction] and (not angle or direction in ("up", "down")):
                face["cullface"] = direction
            if direction in glowing and working:
                face["neoforge_data"] = {"block_light": 15, "sky_light": 15}
            data[direction] = face
        element["faces"] = data
        layers[layer].append(element)

    def skin(directions, material, u, v):
        return dict.fromkeys(directions, (material, u, v))

    box("central_foot", [3.5, 0.125, 3.5], [12.5, 1.125, 12.5], {
        **skin(SIDES, "hardware", 0, 13), "down": ("deck", 3.5, 3.5)})
    box("lower_casting", [3, 1, 3], [13, 3, 13], {
        **skin(SIDES, "shell", 3, 0), "up": ("deck", 3, 3), "down": ("hardware", 0, 3)})
    box("lower_sleeve_seal", [5, 3, 5], [11, 4, 11], {
        **skin(SIDES, "hardware", 0, 2), "up": ("deck", 5, 5)})
    box("upper_casting", [4, 12, 4], [12, 14, 12], {
        **skin(SIDES, "shell", 4, 5), "up": ("service_hatch", 4, 4), "down": ("deck", 4, 4)})
    box("top_terminal", [6.5, 14, 6.5], [9.5, 16, 9.5], {
        **skin(SIDES, "hardware", 0, 1), "up": ("service_hatch", 6.5, 6.5)})

    # Three physically disconnected electrode pairs. Their centres have a
    # 120-degree radial layout; the two angled crowns use a 90-degree-swapped
    # footprint and a legal Java 22.5-degree tilt around the vertical axis.
    # As in the 1.7.10 rendered shape, only the transparent sleeve spans the
    # middle. The terminals seat against the central castings at their ends.
    bias = 1 / 64
    stations = (("rear", 8, 13.25, 0, "south", 0),
                ("west", 3, 5, -22.5, "west", 1),
                ("east", 13, 5, 22.5, "east", 2))
    for station, cx, cz, angle, outward, orientation in stations:
        for end, y, height, length in (("foot", 0, 4, 4), ("crown", 12.5, 3, 7)):
            dx, dz = (length, 3) if station == "rear" else (3, length)
            lo = [cx - dx / 2, y, cz - dz / 2]
            hi = [cx + dx / 2, y + height, cz + dz / 2]
            pivot = [cx, y, cz]
            top_uvs = ((0, 0), (8, 0), (12, 0)) if end == "crown" else ((0, 4), (8, 8), (12, 8))
            u, v = top_uvs[orientation]
            row = 9 if end == "crown" else 12
            faces = {**skin(SIDES, "shell", 0, 12), outward: ("return_housing", 0, row),
                     "up": ("return_housing", u, v), "down": ("hardware", 0, 3)}
            box(f"{station}_{end}", lo, hi, faces, angle=angle, pivot=pivot)
            # A single two-sided mask folds the bus contact from the top onto
            # the outside of each end block. Its metal housing shades normally.
            shift = {"south": (0, bias), "west": (-bias, 0), "east": (bias, 0)}[outward]
            lo = [lo[0] + shift[0], lo[1] + bias, lo[2] + shift[1]]
            hi = [hi[0] + shift[0], hi[1] + bias, hi[2] + shift[1]]
            box(f"{station}_{end}_glow", lo, hi,
                {"up": ("electrode_signal" + glow, u, v),
                 outward: ("electrode_signal" + glow, 0, row)},
                layer="cutout", glowing=("up", outward), angle=angle, pivot=pivot)

    # Two small field tips project into the ends of the glass. The central
    # 5x5x5 volume around [8,8,8] is reserved for the real recipe sample.
    for end, y, exposed in (("lower", 4, "up"), ("upper", 11, "down")):
        box(end + "_field_tip", [5.5, y, 5.5], [10.5, y + 1, 10.5], {
            **skin(SIDES, "reflector", 0, 10), exposed: ("reflector", 0, 0)})
        offset = bias if end == "lower" else -bias
        box("core_glow" if end == "lower" else "upper_reflector_glow",
            [5.5, y + offset, 5.5 - bias], [10.5, y + 1 + offset, 10.5 - bias],
            {"north": ("field_marks" + glow, 0, 8),
             exposed: ("field_marks" + glow, 0 if end == "lower" else 8, 0)},
            layer="cutout", glowing=("north", exposed))

    box("status_glow", [6.5, 1, 3 - bias], [9.5, 2, 3 - bias],
        {"north": ("field_marks" + glow, 0, 12)}, layer="cutout", glowing=("north",))
    box("sample_sleeve_glass", [5, 4, 5], [11, 12, 11], {
        "north": ("optical_glass", 0, 0), "south": ("optical_glass", 0, 0),
        "west": ("optical_glass", 8, 0), "east": ("optical_glass", 8, 0)}, layer="translucent")

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
