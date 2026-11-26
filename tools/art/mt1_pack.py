"""Check and export both palettes of an optional MT1 model and its editable projects."""

import argparse
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from check_mt1 import check_model, check_png, textures
from mt1_blockbench import make_project


ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = Path("assets/mio_icif")
MODEL = "block_molecular_transformer"
MODELS = [ASSET_DIR / "models/block/producer" / (MODEL + suffix + ".json") for suffix in ("", "_on")]
ITEM = ASSET_DIR / "models/item/producer" / (MODEL + ".json")


def write_zip(files, target):
    """Store only the overlay's assets, with reproducible ZIP headers."""
    target.parent.mkdir(parents=True, exist_ok=True)
    staged = target.with_suffix(".tmp")
    with ZipFile(staged, "w") as archive:
        for name, path in sorted(files.items()):
            entry = ZipInfo(name.as_posix(), (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, path.read_bytes(), compress_type=ZIP_DEFLATED)
    staged.replace(target)


def export(design):
    trial = ROOT / "docs/art/mt1" / design
    pack = trial / "pack"
    parts = Path("block/producer") / ("mt1_" + design)
    prefix = "mio_icif:" + parts.as_posix() + "/"
    models = {path: json.loads((pack / path).read_bytes()) for path in MODELS}
    item = json.loads((pack / ITEM).read_bytes())
    if item.get("parent") != "mio_icif:block/producer/" + MODEL or set(item) - {"display", "parent"}:
        raise SystemExit("The inventory item must inherit the idle block model.")

    texture_a = pack / ASSET_DIR / "textures"
    problems = []
    refs = set()
    shapes = []
    for path, model in models.items():
        errors, shape = check_model(pack / path, False, texture_dir=texture_a / parts,
                                    texture_prefix=prefix, max_elements=23, max_faces=94)
        problems.extend(errors)
        shapes.append(shape)
        refs.update(ref[len(prefix):] + ".png" for ref in textures(model).values() if ref.startswith(prefix))
    if shapes[0] != shapes[1]:
        problems.append("Idle and working part names or positions differ.")

    palettes = (
        ("cyan", texture_a, pack, ROOT / "tools/models" / ("mt1_" + design)),
        ("violet", trial / "variant_b", trial / "variant_b", ROOT / "build/art/mt1" / design / "models/violet"),
    )
    for color, texture_root, _, _ in palettes:
        part_dir = texture_root / parts
        if {p.name for p in part_dir.glob("*.png")} != refs:
            problems.append(f"{color}: the PNG set differs from the model textures.")
        for path in sorted(part_dir.glob("*.png")):
            problems.extend(check_png(path))
    if problems:
        raise SystemExit("\n".join(problems))

    for color, texture_root, metadata_root, project_dir in palettes:
        files = {path: pack / path for path in [*MODELS, ITEM]}
        files[Path("pack.mcmeta")] = metadata_root / "pack.mcmeta"
        for name in refs:
            files[ASSET_DIR / "textures" / parts / name] = texture_root / parts / name
        write_zip(files, trial / "downloads" / f"MT1-{design}-{color}.zip")
        project_dir.mkdir(parents=True, exist_ok=True)
        for path, model in models.items():
            name = path.stem.replace(MODEL, "molecular_transformer_" + design)
            project = make_project(model, name, texture_root)
            (project_dir / (name + ".bbmodel")).write_text(
                json.dumps(project, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
    print(f"{design.capitalize()} packs and projects OK")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("design", choices=("beamline", "quadrupole"))
    export(parser.parse_args().design)
