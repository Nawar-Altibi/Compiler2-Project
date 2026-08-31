from pathlib import Path

from PIL import Image, ImageDraw


root = Path(__file__).parent
paths = sorted(root.glob("guide-v3-page-*.png"))
thumb_width = 250
label_height = 24
columns = 5

with Image.open(paths[0]) as first:
    thumb_height = round(first.height * thumb_width / first.width)

rows = (len(paths) + columns - 1) // columns
sheet = Image.new(
    "RGB",
    (columns * thumb_width, rows * (thumb_height + label_height)),
    "white",
)
draw = ImageDraw.Draw(sheet)

for index, image_path in enumerate(paths):
    with Image.open(image_path) as source:
        thumbnail = source.convert("RGB").resize(
            (thumb_width, thumb_height),
            Image.Resampling.LANCZOS,
        )
    x = (index % columns) * thumb_width
    y = (index // columns) * (thumb_height + label_height)
    sheet.paste(thumbnail, (x, y))
    draw.rectangle(
        (x, y + thumb_height, x + thumb_width, y + thumb_height + label_height),
        fill="#e7eef1",
    )
    draw.text((x + 8, y + thumb_height + 5), str(index + 1), fill="#263740")

sheet.save(root / "guide-v3-contact-sheet.png")
