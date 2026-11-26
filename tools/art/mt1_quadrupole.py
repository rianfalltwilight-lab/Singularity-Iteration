#!/usr/bin/env python3
"""Build the four-corner, vertical-chamber MT1 art candidate."""
import json
from pathlib import Path

from PIL import Image

from mt1_model import GRAPHITE, PALETTES, PORCELAIN, SIDES, STEEL, canvas


ROOT = Path(__file__).resolve().parents[2]
TRIAL = ROOT / "docs/art/mt1/quadrupole"
ASSETS = TRIAL / "pack/assets/mio_icif"
PARTS = Path("block/producer/mt1_quadrupole")
REF = "mio_icif:" + PARTS.as_posix() + "/"


def neutral_parts():
    # The vertical ceramic returns need a darker glaze than the top. With
    # the same white, one lit side disappears into the vanilla inventory slot.
    result = {"porcelain": Image.new("RGBA", (16, 16), "#D0D0D0")}
    # World-plan UVs keep the stamped relief continuous across each L's elbow.
    img, d = canvas(PORCELAIN)
    for right in (False, True):
        for back in (False, True):
            marks = [(2, 5), (2, 2), (5, 2)]
            d.line([(15 - x if right else x, 15 - z if back else z)
                    for x, z in marks], fill="#DDE0DF", width=1)
    result["ceramic_corner"] = img

    # Separate UV islands for the closed access lid, the four internal pole
    # seats and the vertical edge of the metal closure.
    img, d = canvas("#A2A9AD")
    d.rectangle((0, 0, 7, 7), fill="#A5AFB3", outline="#829095")
    d.line((1, 0, 6, 0), fill="#B6BFC1")
    d.line((0, 1, 0, 6), fill="#A5B0B4")
    d.rectangle((8, 0, 15, 7), fill=STEEL)
    for x, z in ((3, 1), (4, 1), (3, 6), (4, 6), (1, 3), (1, 4), (6, 3), (6, 4)):
        d.point((8 + x, z), fill=GRAPHITE)
    d.rectangle((0, 8, 15, 15), fill="#818B90")
    result["metal_closure"] = img

    # A smoked optical column, with broad transmissive windows and quiet
    # perimeter seals. There is no opaque backboard behind the specimen.
    img, d = canvas((112, 130, 139, 148))
    d.rectangle((8, 0, 15, 8), fill=(122, 139, 146, 143))
    for u in (0, 8):
        d.line((u, 0, u + 7, 0), fill=(66, 82, 92, 160))
        d.line((u, 8, u + 7, 8), fill=(65, 81, 91, 153))
        d.line((u, 1, u, 7), fill=(108, 130, 136, 128))
        d.line((u + 7, 1, u + 7, 7), fill=(67, 85, 98, 141))
    # Two deliberately low-contrast glints stay above the central sightline.
    for x, y, color in ((1, 3, (160, 178, 185, 122)),
                        (2, 2, (177, 192, 196, 131)),
                        (3, 1, (157, 173, 180, 112)),
                        (12, 2, (141, 163, 175, 111)),
                        (13, 1, (155, 174, 181, 117))):
        d.point((x, y), fill=color)
    result["smoked_glass"] = img
    return result


def field_marks(palette):
    parts = {}
    for state, colors in palette.items():
        outer, _, lip, surface, _, _ = colors
        img, d = canvas((0, 0, 0, 0))
        # Facing the sample, the four contacts follow the four clear slots
        # between clamps. A tiny pilot on the near edges is visible head-on.
        for x, z in ((3, 1), (4, 1), (3, 6), (4, 6), (1, 3), (1, 4), (6, 3), (6, 4)):
            d.point((x, z), fill=surface if x in (1, 6) else lip)
        d.line((3, 10, 4, 10), fill=outer)
        parts["field" + ("_on" if state == "on" else "")] = img
    return parts


def model(working):
    layers = {"solid": [], "cutout": [], "translucent": []}

    def box(name, lo, hi, surfaces, layer="solid"):
        dx, dy, dz = (b - a for a, b in zip(lo, hi))
        sizes = {"north": (dx, dy), "south": (dx, dy), "west": (dz, dy),
                 "east": (dz, dy), "up": (dx, dz), "down": (dx, dz)}
        outside = {"north": lo[2] == 0, "south": hi[2] == 16, "west": lo[0] == 0,
                   "east": hi[0] == 16, "down": lo[1] == 0, "up": hi[1] == 16}
        faces = {}
        for direction, (name_ref, u, v) in surfaces.items():
            w, h = sizes[direction]
            face = {"texture": "#" + name_ref, "uv": [u, v, u + w, v + h]}
            if outside[direction]:
                face["cullface"] = direction
            if layer == "cutout" and working:
                face["neoforge_data"] = {"block_light": 15, "sky_light": 15}
            faces[direction] = face
        layers[layer].append({"name": name, "from": lo, "to": hi, "faces": faces})

    # Each quadrant occupies 7x7; its two 4-wide legs meet without overlap.
    # The other leg and the central 8x8 metal closure cover the stepped inner
    # walls. Their contact faces don't enter the baked model.
    for level, y in (("lower", 0), ("upper", 13)):
        for right, ew in ((False, "west"), (True, "east")):
            for back, ns in ((False, "north"), (True, "south")):
                next_ew = "west" if right else "east"
                next_ns = "north" if back else "south"
                segments = (("arm", (0, 0, 7, 4), (ns, ew, next_ew)),
                            ("return", (0, 4, 4, 7), (ew, next_ns)))
                for segment, (x, z, X, Z), walls in segments:
                    if right:
                        x, X = 16 - X, 16 - x
                    if back:
                        z, Z = 16 - Z, 16 - z
                    surfaces = dict.fromkeys(walls, ("porcelain", 0, 0))
                    surfaces.update(up=("ceramic_corner", x, z), down=("porcelain", 0, 0))
                    box(f"{level}_{ns}_{ew}_{segment}", [x, y, z], [X, y + 3, Z], surfaces)

    # The closures slightly overhang the inner ends vertically, exposing
    # their steel lips below/above the ceramic fittings. These are the two
    # driven ends of the chamber, so no permanent object occupies its middle.
    light = "field_on" if working else "field"
    bias = 1 / 64
    for end, lo, hi, inward, outward in (("lower", 0, 3.5, "up", "down"),
                                         ("upper", 12.5, 16, "down", "up")):
        surfaces = dict.fromkeys(SIDES, ("metal_closure", 0, 8))
        surfaces.update({inward: ("metal_closure", 8, 0), outward: ("metal_closure", 0, 0)})
        box(end + "_closure", [4, lo, 4], [12, hi, 12], surfaces)
        y = hi - 1 + bias if end == "lower" else lo - bias
        box("core_glow" if end == "lower" else "upper_contacts_glow",
            [4 - bias, y, 4 - bias], [12 - bias, y + 1, 12 - bias],
            {inward: (light, 0, 0), "north": (light, 0, 10), "west": (light, 0, 10)},
            layer="cutout")

    # A single glass surface on each side closes the full square work column.
    # Both end faces are closed by the solid plates above.
    box("vertical_chamber_glass", [4, 3.5, 4], [12, 12.5, 12], {
        "north": ("smoked_glass", 0, 0), "south": ("smoked_glass", 0, 0),
        "east": ("smoked_glass", 8, 0), "west": ("smoked_glass", 8, 0)},
        layer="translucent")

    children = {}
    for layer, elements in layers.items():
        materials = sorted({face["texture"][1:] for el in elements for face in el["faces"].values()})
        children[layer] = {
            "parent": "minecraft:block/block", "render_type": "minecraft:" + layer,
            "textures": {key: REF + key for key in materials}, "elements": elements,
        }
    return {"parent": "minecraft:block/block", "loader": "neoforge:composite",
            "textures": {"particle": REF + "porcelain"}, "children": children}


def main():
    neutral = neutral_parts()
    for directory, variant in ((ASSETS / "textures", "a"), (TRIAL / "variant_b", "b")):
        target = directory / PARTS
        target.mkdir(parents=True, exist_ok=True)
        for name, img in {**neutral, **field_marks(PALETTES[variant])}.items():
            img.save(target / (name + ".png"), optimize=True)
    target = ASSETS / "models/block/producer"
    target.mkdir(parents=True, exist_ok=True)
    for active in (False, True):
        dest = target / ("block_molecular_transformer" + ("_on" if active else "") + ".json")
        with dest.open("w", encoding="utf8", newline="\n") as out:
            json.dump(model(active), out, indent=2)
            out.write("\n")


if __name__ == "__main__":
    main()
