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

    # 0..2 are the lower fascia, 5..7 the upper fascia. The two frame rails
    # at 8/9 are darker gaskets against the pale front corner castings.
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

    # White flange and a graphite-lined well. Keep the chamber surfaces quiet
    # around the metal sample and the active edges of the two reflectors.
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

    # The opaque liner doubles as a contrasting, unmarked sample background.
    img, d = canvas(IRON)
    d.rectangle((0, 0, 9, 9), fill="#333437", outline="#3F4043")
    d.line((1, 0, 8, 0), fill=IRON)
    for x in (0, 9):
        d.point((x, 1), fill=STEEL)
        d.point((x, 8), fill=STEEL)
    textures["chamber"] = img

    # Rear winding housings close off the rear half of each side. Mirror the
    # outside faces so that the covered bus bars run forward on both sides.
    img, d = canvas(WHITE)
    d.rectangle((0, 0, 7, 9), fill=PEARL, outline=SEAM)
    d.line((0, 1, 0, 8), fill=WHITE)
    d.rectangle((2, 2, 5, 7), fill=EDGE)
    d.rectangle((2, 2, 4, 6), fill=IRON)
    for z in (3, 5):
        d.rectangle((2, z, 4, z), fill=MID)
    d.point((6, 1), fill=SHADOW)
    d.point((1, 8), fill=SHADOW)
    img.paste(img.crop((0, 0, 8, 10)).transpose(Image.Transpose.FLIP_LEFT_RIGHT), (8, 0))
    textures["return_housing"] = img

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

    # Sparse diagonal reflections reference the irradiant glass pane. The
    # centre stays clear, including on the shorter side windows.
    img, d = canvas((178, 205, 201, 18))
    d.line((0, 0, 11, 0), fill=(143, 161, 160, 49))
    d.line((0, 7, 11, 7), fill=(115, 132, 134, 43))
    for pos, a in (((1, 4), 76), ((2, 3), 112), ((3, 3), 99), ((4, 2), 82),
                   ((9, 6), 40), ((10, 5), 46)):
        d.point(pos, fill=(226, 243, 249, a))
    # Side glass occupies 7x8 at U=0,V=8 and doesn't repeat the front glints.
    d.line((0, 8, 6, 8), fill=(143, 161, 160, 48))
    d.line((0, 15, 6, 15), fill=(115, 132, 134, 40))
    d.point((4, 10), fill=(218, 240, 228, 70))
    d.point((5, 9), fill=(218, 240, 228, 62))
    textures["optical_glass"] = img
    return textures


def field_parts(palette):
    textures = {}
    for state, colors in palette.items():
        suffix = "_on" if state == "on" else ""
        img, d = canvas((0, 0, 0, 0))
        # The underside is UV-flipped in vanilla, hence the reversed second
        # island. Both opposed heads light the edge closest to the observer.
        for x, z, s in ((1, 0, 2), (2, 0, 4), (5, 0, 4), (6, 0, 2),
                        (2, 1, 5), (5, 1, 5), (2, 2, 3), (5, 2, 3),
                        (2, 3, 1), (5, 3, 1)):
            d.point((x, z), fill=colors[s])
            d.point((8 + x, 6 - z), fill=colors[s])
        for x, s in ((1, 2), (2, 4), (5, 4), (6, 2)):
            d.point((x, 8), fill=colors[s])
        for x, s in enumerate((2, 4, 5)):
            d.point((x, 12), fill=colors[s])
        textures["field_marks" + suffix] = img
    return textures


def model(working):
    layers = {"solid": [], "cutout": [], "translucent": []}
    glow = "_on" if working else ""

    def box(name, lo, hi, faces, layer="solid", glowing=()):
        # Face material entries are (texture, atlas U, atlas V). Calculate each
        # UV span from the box to maintain one texture pixel per world unit.
        dx, dy, dz = (b - a for a, b in zip(lo, hi))
        extent = {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy),
                  "west": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
        boundary = dict(zip(ALL, (lo[2] == 0, hi[0] == 16, hi[2] == 16,
                                  lo[0] == 0, hi[1] == 16, lo[1] == 0)))
        element = {"name": name, "from": lo, "to": hi}
        data = {}
        for direction, (tex, u, v) in faces.items():
            width, height = extent[direction]
            face = {"uv": [u, v, u + width, v + height], "texture": "#" + tex}
            if boundary[direction]:
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
    box("upper_casting", [0, 13, 0], [16, 15, 16], {
        **skin(SIDES, "shell", 0, 5), "up": ("deck", 0, 0), "down": ("deck", 0, 0)})
    box("service_hatch", [1, 15, 1], [15, 16, 15], {
        **skin(SIDES, "shell", 0, 3), "up": ("service_hatch", 1, 1)})

    # The white rear return housings carry both castings. In front, the corner
    # posts are only one unit deep, allowing the side glass to wrap forward.
    for side, x, nose, outside, inside, u in (
            ("west", 0, 0, "west", "east", 0), ("east", 13, 14, "east", "west", 8)):
        box(side + "_return_housing", [x, 3, 8], [x + 3, 13, 16], {
            "north": ("hardware", 0, 2), "south": ("service_hatch", 1, 3),
            outside: ("return_housing", u, 0), inside: ("chamber", 0, 0)})
        box(side + "_front_post", [nose, 4, 0], [nose + 2, 12, 1], {
            **skin(("north", outside), "service_hatch", 1, 2),
            **skin(("south", inside), "hardware", 0, 4)})
        for end, y, exposed in (("lower", 3, "up"), ("upper", 12, "down")):
            box(f"{side}_{end}_shoulder", [x, y, 0], [x + 3, y + 1, 8], {
                **skin(("north", "east", "west"), "shell", 0, 3),
                exposed: ("service_hatch", 2, 2)})
    box("rear_pressure_liner", [3, 3, 9], [13, 13, 16], {
        "north": ("chamber", 0, 0), "south": ("service_hatch", 3, 3)})

    for end, y, exposed, v in (("lower", 3, "up", 8), ("upper", 12, "down", 9)):
        box(end + "_window_rail", [3, y, 0.5], [13, y + 1, 2], {
            "north": ("shell", 2, v), "south": ("hardware", 0, 12),
            exposed: ("hardware", 0, 12)})

    # Each transformer backs a wide laminar reflector. The six-unit gap
    # between their working faces is empty in every baked block state. A
    # future item renderer can place its sample about [8,8,6].
    for end, y, plate_y, exposed in (("lower", 3, 4, "up"), ("upper", 12, 11, "down")):
        box(end + "_transformer_seat", [3, y, 3], [13, y + 1, 9], {
            **skin(("north", "east", "west"), "hardware", 0, 0),
            exposed: ("hardware", 0, 3)})
        box(end + "_reflector", [4, plate_y, 2], [12, plate_y + 1, 9], {
            **skin(("north", "west", "east"), "reflector", 0, 10),
            exposed: ("reflector", 0, 0),
            # The one-unit nose projects beyond the underlying transformer seat.
            ("down" if end == "lower" else "up"): ("reflector", 0, 0)})

        # An emissive mask folds over the forward edge. Its transparent pixels
        # expose the normally lit metal. Keep the lower service name core_glow.
        bias = 1 / 64
        offset = bias if end == "lower" else -bias
        box("core_glow" if end == "lower" else "upper_reflector_glow",
            [4, plate_y + offset, 2 - bias], [12, plate_y + 1 + offset, 9 - bias],
            {"north": ("field_marks" + glow, 0, 8),
             exposed: ("field_marks" + glow, 0 if end == "lower" else 8, 0)},
            layer="cutout", glowing=("north", exposed))

    box("status_glow", [4, 3, 0.484375], [7, 4, 0.484375],
        {"north": ("field_marks" + glow, 0, 12)}, layer="cutout", glowing=("north",))
    # Only the visible glass between the front posts and forward of each rear
    # housing is drawn. No front glass sits behind opaque corner castings.
    box("front_observation_glass", [2, 4, 1], [14, 12, 1],
        {"north": ("optical_glass", 0, 0)}, layer="translucent")
    box("side_observation_glass", [1, 4, 1], [15, 12, 8],
        skin(("west", "east"), "optical_glass", 0, 8), layer="translucent")

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
