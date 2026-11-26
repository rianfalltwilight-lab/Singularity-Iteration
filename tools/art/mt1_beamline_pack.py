"""Check and export the optional 1.21.1 beamline resource packs and editable projects."""

import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from check_mt1 import check_model, check_png, textures
from mt1_blockbench import make_project


ROOT = Path(__file__).resolve().parents[2]
TRIAL = ROOT / "docs/art/mt1/beamline"
PACK = TRIAL / "pack"
ASSET_DIR = Path("assets/mio_icif")
PREFIX = "mio_icif:block/producer/mt1_beamline/"
PARTS = Path("block/producer/mt1_beamline")
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


def main():
    models = {path: json.loads((PACK / path).read_bytes()) for path in MODELS}
    item = json.loads((PACK / ITEM).read_bytes())
    if item.get("parent") != "mio_icif:block/producer/" + MODEL or set(item) - {"display", "parent"}:
        raise SystemExit("The inventory item must inherit the idle beamline model.")

    texture_a = PACK / ASSET_DIR / "textures"
    problems = []
    refs = set()
    shapes = []
    for path, model in models.items():
        errors, shape = check_model(PACK / path, False, texture_dir=texture_a / PARTS,
                                    texture_prefix=PREFIX, max_elements=23, max_faces=94)
        problems.extend(errors)
        shapes.append(shape)
        refs.update(ref[len(PREFIX):] + ".png" for ref in textures(model).values() if ref.startswith(PREFIX))
    if shapes[0] != shapes[1]:
        problems.append("Idle and working part names or positions differ.")

    palettes = (
        ("cyan", texture_a, PACK, ROOT / "tools/models/mt1_beamline"),
        ("violet", TRIAL / "variant_b", TRIAL / "variant_b", ROOT / "build/art/mt1/beamline/models/violet"),
    )
    for color, texture_root, _, _ in palettes:
        part_dir = texture_root / PARTS
        if {p.name for p in part_dir.glob("*.png")} != refs:
            problems.append(f"{color}: the PNG set differs from the model textures.")
        for path in sorted(part_dir.glob("*.png")):
            problems.extend(check_png(path))
    if problems:
        raise SystemExit("\n".join(problems))

    for color, texture_root, metadata_root, project_dir in palettes:
        files = {path: PACK / path for path in [*MODELS, ITEM]}
        files[Path("pack.mcmeta")] = metadata_root / "pack.mcmeta"
        for name in refs:
            files[ASSET_DIR / "textures" / PARTS / name] = texture_root / PARTS / name
        write_zip(files, TRIAL / "downloads" / f"MT1-beamline-{color}.zip")
        project_dir.mkdir(parents=True, exist_ok=True)
        for path, model in models.items():
            name = path.stem.replace("block_molecular_transformer", "molecular_transformer_beamline")
            project = make_project(model, name, texture_root)
            (project_dir / (name + ".bbmodel")).write_text(
                json.dumps(project, ensure_ascii=False, indent=2) + "\n", encoding="utf8", newline="\n")
    print("Beamline packs and projects OK")


if __name__ == "__main__":
    main()
