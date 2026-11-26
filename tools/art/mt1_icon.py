"""Draw the two native 16×16 Molecular Transformer inventory icons with Pillow."""

from pathlib import Path

from PIL import Image, ImageColor


ROOT = Path(__file__).resolve().parents[2]
ICON = Path("item/producer/item_molecular_transformer.png")

# Front elevation with a little of the top and right face visible. The bright
# edges belong to the opposing heads. Three dark rows between them stay open.
# Each pair gives the first occupied pixel's x coordinate and the scanline.
ROWS = [
    (4, "#########"),
    (2, "##WWSSMMMLL#"),
    (1, "#WWLLLWWLMMSS#"),
    (1, "#WLLLLLLMMSSs#"),
    (1, "#MSMssssSSSsH#"),
    (2, "#SPACvhCBaS#"),
    (2, "#SshhhhhvHs#"),
    (2, "#HvhvvvvhHs#"),
    (2, "#svHhhhhhHS#"),
    (2, "#SaBCvhAACS#"),
    (1, "#WWLMMMMMLMSs#"),
    (1, "#WLLLLLLMMSSs#"),
    (1, "#SSMMMMHCassH#"),
    (2, "############"),
]

HOUSING = {
    "#": "#1A1A1B", "W": "#FBFBFB", "L": "#ECEAEB", "M": "#D0D0D0",
    "S": "#9F9E9E", "s": "#606063", "H": "#464648", "h": "#2A2A2C",
    "v": "#39393E",
}

CYAN = {
    "P": "#B4FFF3", "A": "#2ADED3", "C": "#02D1F3",
    "B": "#3DBBE3", "a": "#23859B",
}
VIOLET = {
    "P": "#FFC7F0", "A": "#D673EF", "C": "#F084C6",
    "B": "#BC68D9", "a": "#9651AB",
}


def draw_icon(accents):
    palette = {key: ImageColor.getrgb(value) for key, value in (HOUSING | accents).items()}
    image = Image.new("RGBA", (16, 16))
    for y, (start_x, row) in enumerate(ROWS, start=1):
        for x, symbol in enumerate(row, start=start_x):
            image.putpixel((x, y), (*palette[symbol], 255))
    return image


if __name__ == "__main__":
    for base, accents in (
        (ROOT / "src/main/resources/assets/mio_icif/textures", CYAN),
        (ROOT / "docs/art/mt1/variant_b", VIOLET),
    ):
        destination = base / ICON
        destination.parent.mkdir(parents=True, exist_ok=True)
        draw_icon(accents).save(destination)
