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
PORCELAIN, SEAM = "#F1F1F1", "#DFDFDF"
STEEL, GRAPHITE = "#8E8E92", "#464648"
# Outer electrode, status, field lip, field surface, inner contact, hot spot.
PALETTES = {
    "a": {
        "off": ("#49575B", "#43575B", "#3D5A60", "#426168", "#47676C", "#4D7072"),
        "on": ("#4F959F", "#41818A", "#2ADED3", "#02D1F3", "#3DBBE3", "#B4FFF3"),
    },
    "b": {
        "off": ("#544D59", "#514956", "#594C62", "#64526B", "#6A5671", "#715F77"),
        "on": ("#8F71A0", "#80658B", "#D673EF", "#E891E8", "#C884D5", "#FFC7F0"),
    },
}


def canvas(color):
    img = Image.new("RGBA", (16, 16), color)
    return img, ImageDraw.Draw(img)


def neutral_parts():
    textures = {}

    # All unbroken faces of the castings use the same fired ceramic. Face
    # lighting supplies the edge contrast at each change of direction.
    textures["shell"] = Image.new("RGBA", (16, 16), PORCELAIN)
    img, d = canvas(PORCELAIN)
    d.rectangle((5, 5, 10, 10), fill=STEEL)
    textures["deck"] = img

    # The removable top contact sits inside a quiet square seat.
    img, d = canvas(PORCELAIN)
    d.rectangle((6, 6, 9, 9), fill=SEAM)
    d.rectangle((7, 7, 8, 8), fill=STEEL)
    textures["service_hatch"] = img

    # Steel on the sleeve seal and tip fittings; a graphite sole is visible
    # below the ceramic base. Keep the undersides of the radial arms plain.
    img, d = canvas(STEEL)
    d.line((0, 13, 15, 13), fill=GRAPHITE)
    textures["hardware"] = img
    textures["return_housing"] = terminals()
    textures["reflector"] = Image.new("RGBA", (16, 16), STEEL)

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
        img, _ = canvas(PORCELAIN)
        # The outermost row of the top tile meets the first row on its flank.
        # Identical graphite runs on both sides of the fold form one socket.
        crown = ("0000000", "0011100", "0011100")
        foot = ("0000", "0110", "0110")
        crown_side = ("0011100", "0011100", "0000000")
        foot_side = ("0110", "0110", "0000", "0000")
        colors = (PORCELAIN, GRAPHITE)
    else:
        img, _ = canvas((0, 0, 0, 0))
        crown = (".......", ".......", "...0...")
        foot = ("....", "....", ".0..")
        crown_side = ("...0...", ".......", ".......")
        foot_side = (".0..", "....", "....", "....")
        colors = (signal,)

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
        electrode, status, lip, surface, contact, hot = colors
        # One uninterrupted emitting edge at each tip. The brightest pixel
        # lies on the inward-facing surface, close to the suspended sample.
        for x, z, color in ((1, 0, surface), (2, 0, surface), (3, 0, surface),
                            (2, 1, contact), (2, 2, hot)):
            d.point((x, z), fill=color)
            d.point((8 + x, 4 - z), fill=color)
        d.line((1, 8, 3, 8), fill=lip)
        d.point((1, 12), fill=status)
        textures["field_marks" + suffix] = img
        textures["electrode_signal" + suffix] = terminals(electrode)
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
