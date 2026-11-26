"""Build editable Blockbench projects from the two NeoForge models; embeds the selected palette."""

import argparse
import base64
import json
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/mio_icif"
MODEL = "molecular_transformer"
SIDES = ("north", "east", "south", "west", "up", "down")


def stable_id(key):
    return str(uuid5(NAMESPACE_URL, "mio_icif/" + MODEL + "/" + key))


def texture_ref(slot, slots):
    while slot.startswith("#"):
        slot = slots[slot[1:]]
    return slot


def make_project(model, name, texture_root):
    textures = []
    indices = {}
    particle = texture_ref(model["textures"]["particle"], model["textures"])

    def texture_index(ref):
        if ref not in indices:
            namespace, location = ref.split(":")
            resource = Path(location + ".png")
            data = (texture_root / resource).read_bytes()
            indices[ref] = len(textures)
            textures.append({
                "id": resource.stem, "uuid": stable_id(ref), "name": resource.name,
                "namespace": namespace, "folder": resource.parent.as_posix(),
                "internal": True, "particle": ref == particle, "render_sides": "front",
                "source": "data:image/png;base64," + base64.b64encode(data).decode("ascii"),
            })
        return indices[ref]

    texture_index(particle)
    elements, groups, outliner = [], [], []
    for group, child in model["children"].items():
        slots = {**model["textures"], **child.get("textures", {})}
        members = []
        for source in child["elements"]:
            cube = {key: value for key, value in source.items() if key not in ("faces", "rotation")}
            uid = stable_id(source["name"])
            cube.update(type="cube", uuid=uid, autouv=0, box_uv=False)
            if "rotation" in source:
                rotation = source["rotation"]
                angles = [0, 0, 0]
                angles["xyz".index(rotation["axis"])] = rotation["angle"]
                cube.update(origin=rotation["origin"], rotation=angles, rescale=rotation.get("rescale", False))
            cube["faces"] = {side: {"texture": None} for side in SIDES}
            for side, face in source["faces"].items():
                index = texture_index(texture_ref(face["texture"], slots))
                bb_face = dict(face, texture=index)
                if "tintindex" in bb_face:
                    bb_face["tint"] = bb_face.pop("tintindex")
                cube["faces"][side] = bb_face
            elements.append(cube)
            members.append(uid)

        group_id = stable_id("group/" + group)
        groups.append({"uuid": group_id, "name": group, "origin": [8, 8, 8]})
        outliner.append({"uuid": group_id, "isOpen": True, "children": members})

    return {
        "meta": {"format_version": "5.0", "model_format": "java_block", "box_uv": False},
        "name": name, "parent": model.get("parent", "minecraft:block/block"),
        "ambientocclusion": model.get("ambientocclusion", True),
        "resolution": {"width": 16, "height": 16},
        "elements": elements, "groups": groups, "outliner": outliner, "textures": textures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--palette", choices=("a", "b"), default="a")
    parser.add_argument("--output", type=Path, help="Directory for both .bbmodel files")
    args = parser.parse_args()
    texture_root = ASSETS / "textures" if args.palette == "a" else ROOT / "docs/art/mt1/variant_b"
    default_output = ROOT / ("tools/models" if args.palette == "a" else "build/art/mt1/variant_b")
    output = args.output or default_output
    output.mkdir(parents=True, exist_ok=True)
    for name in (MODEL, MODEL + "_on"):
        model = json.loads((ASSETS / "models/block/producer" / f"block_{name}.json").read_bytes())
        data = make_project(model, name, texture_root)
        (output / f"{name}.bbmodel").write_text(
            json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
