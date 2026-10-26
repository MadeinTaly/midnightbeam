"""Builds the app's icons, TV banner, store feature graphic and README header from the two sources in design/:
arcade-icon.png (the saucer painting the moon) and arcade-scene.png (the cinema duel). Needs Pillow.

    python design/build_assets.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
P = lambda *a: os.path.join(ROOT, *a)

PAPER = (24, 8, 11)        # #18080B
GOLD = (237, 194, 62)      # #EDC23E
RED = (215, 51, 55)        # #D73337
INK = (243, 238, 228)      # #F3EEE4
MUTED = (179, 161, 146)    # #B3A192
BUNGEE = P('app', 'assets', 'fonts', 'Bungee-Regular.ttf')
CHIVO = P('app', 'assets', 'fonts', 'Chivo-Bold.ttf')


def content_box(im, bg, tol=40):
    """Bounding box of the pixels that differ from the background colour."""
    mask = Image.eval(im.convert('L'), lambda v: 0)
    px, mp = im.load(), mask.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b = px[x, y][:3]
            if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > tol:
                mp[x, y] = 255
    return mask.getbbox()


def icon_square(size, fill=0.74, rounded=True):
    """The icon drawing centred on the ground, its longer side `fill` of the square; rounded corners stay transparent."""
    src = Image.open(P('design', 'arcade-icon.png')).convert('RGB')
    art = src.crop(content_box(src, src.getpixel((4, 4))))
    k = size * 4  # draw large, scale down once
    canvas = Image.new('RGBA', (k, k), PAPER + (255,))
    scale = fill * k / max(art.size)
    art = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    # the drawing's own ground is a slightly different black: key it out against the canvas
    a = art.convert('RGBA')
    bg = src.getpixel((4, 4))
    a.putdata([(r, g, b, 0 if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) < 24 else 255) for r, g, b, _ in a.get_flattened_data()])
    canvas.alpha_composite(a, ((k - a.width) // 2, (k - a.height) // 2))
    if rounded:
        m = Image.new('L', (k, k), 0)
        ImageDraw.Draw(m).rounded_rectangle((0, 0, k - 1, k - 1), radius=round(k * 0.22), fill=255)
        canvas.putalpha(m)
    return canvas.resize((size, size), Image.LANCZOS)


def palette(im):
    """256-colour PNG (as the previous artwork): a fraction of the size in the APK, no visible loss on flat art."""
    return im.quantize(256, method=Image.FASTOCTREE) if im.mode == 'RGBA' else im.quantize(256)


def split_text(draw, xy, text, font, fill=INK):
    """Bungee wordmark with the Arcade RGB split: a hard red edge one way, gold the other, no blur."""
    x, y = xy
    off = max(2, font.size // 22)
    draw.text((x - off, y), text, font=font, fill=RED)
    draw.text((x + off, y), text, font=font, fill=GOLD)
    draw.text((x, y), text, font=font, fill=fill)


def scene_cover(w, h, focus_y=0.42):
    """The scene scaled to cover w x h, cropped around a vertical focus point."""
    s = Image.open(P('design', 'arcade-scene.png')).convert('RGB')
    k = max(w / s.width, h / s.height)
    s = s.resize((round(s.width * k), round(s.height * k)), Image.LANCZOS)
    top = min(max(0, round(s.height * focus_y - h / 2)), s.height - h)
    left = (s.width - w) // 2
    return s.crop((left, top, left + w, top + h))


def main():
    for d, n in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        palette(icon_square(n)).save(P('app', 'res', 'drawable-' + d, 'ic_launcher.png'), optimize=True)
    palette(icon_square(192)).save(P('app', 'assets', 'icon-192.png'), optimize=True)
    palette(icon_square(512)).save(P('app', 'assets', 'icon-512.png'), optimize=True)
    icon_square(512).save(P('fastlane', 'metadata', 'android', 'en-US', 'images', 'icon.png'), optimize=True)
    palette(icon_square(180, rounded=False).convert('RGB')).save(P('app', 'assets', 'apple-touch-icon.png'), optimize=True)

    # TV banner 320x180 (xhdpi): the icon, then the name in two Bungee lines
    b = Image.new('RGBA', (320, 180), PAPER + (255,))
    b.alpha_composite(icon_square(140, fill=0.84, rounded=False), (8, 20))
    d = ImageDraw.Draw(b)
    size = 40
    while ImageFont.truetype(BUNGEE, size).getlength('MIDNIGHT') > 158:  # fit the longer line in the room left
        size -= 1
    f = ImageFont.truetype(BUNGEE, size)
    split_text(d, (152, 90 - size - 2), 'MIDNIGHT', f)
    split_text(d, (152, 92), 'BEAM', f)
    palette(b.convert('RGB')).save(P('app', 'res', 'drawable-xhdpi', 'banner.png'), optimize=True)

    # store feature graphic 1024x500: the scene alone
    scene_cover(1024, 500, 0.45).save(P('fastlane', 'metadata', 'android', 'en-US', 'images', 'featureGraphic.png'), optimize=True)

    # README header 1600x500: name and line on the ground, a gold bar, the scene on the right
    h = Image.new('RGB', (1600, 500), PAPER)
    h.paste(scene_cover(900, 500, 0.45), (700, 0))
    d = ImageDraw.Draw(h)
    d.rectangle((694, 0, 699, 500), fill=GOLD)
    split_text(d, (64, 120), 'MIDNIGHT', ImageFont.truetype(BUNGEE, 92))
    split_text(d, (64, 222), 'BEAM', ImageFont.truetype(BUNGEE, 92))
    fc = ImageFont.truetype(CHIVO, 30)
    d.text((66, 352), 'Less blue light.', font=fc, fill=INK)
    d.text((66, 392), 'More comfortable movie nights.', font=fc, fill=MUTED)
    h.save(P('docs', 'header.jpg'), quality=88, optimize=True, progressive=True)


if __name__ == '__main__':
    main()
