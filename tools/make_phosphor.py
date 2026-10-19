"""Renders the icons (white PNGs with transparency) from the Phosphor icon font (MIT).
Get the font with: npm pack @phosphor-icons/web@2.1.1 && tar xzf phosphor-icons-web-2.1.1.tgz
Usage: python3 -I make_phosphor.py package/src OUT_DIR [EM_PX]"""
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

# name used in the app -> (Phosphor name, weight: "regular" or "fill")
MAP = {
    "sunny": ("sun", "regular"), "clear_night": ("moon-stars", "regular"),
    "partly_cloudy_day": ("cloud-sun", "regular"), "partly_cloudy_night": ("cloud-moon", "regular"),
    "cloud": ("cloud", "regular"), "foggy": ("cloud-fog", "regular"), "rainy": ("cloud-rain", "regular"),
    "thunderstorm": ("cloud-lightning", "regular"), "snowflake": ("snowflake", "regular"),
    "tv": ("television-simple", "regular"), "gamepad": ("game-controller", "regular"), "hdmi": ("plug", "regular"),
    "apps": ("dots-nine", "regular"), "settings": ("gear-six", "regular"), "speaker": ("speaker-high", "regular"),
    "sparkles": ("sparkle", "regular"), "bedtime": ("moon", "regular"), "home": ("house", "regular"),
    "chevron_right": ("caret-right", "regular"), "bell": ("bell", "regular"), "sync": ("arrows-clockwise", "regular"),
    "check": ("check", "regular"), "play": ("play", "fill"), "tv_settings": ("sliders-horizontal", "regular"),
    "chevron_down": ("caret-down", "regular"), "sunrise": ("sun-horizon", "regular"), "sunset": ("sun-horizon", "regular"),
    "live_tv": ("television", "regular"), "bitcoin": ("currency-btc", "regular"),
}
FILES = {"regular": ("regular/Phosphor.ttf", "regular/style.css"), "fill": ("fill/Phosphor-Fill.ttf", "fill/style.css")}
SCALE = 4   # drawn 4x larger, then scaled down (smooth edges)


def codes(css):
    return {m.group(1): int(m.group(2), 16)
            for m in re.finditer(r'\.ph-([a-z0-9-]+):before\s*\{\s*content:\s*"\\([0-9a-f]+)"', open(css).read())}


def main():
    src, out = sys.argv[1], sys.argv[2]
    em = int(sys.argv[3]) if len(sys.argv) > 3 else 172
    fonts = {w: (ImageFont.truetype(os.path.join(src, f), em * SCALE), codes(os.path.join(src, c)))
             for w, (f, c) in FILES.items()}
    os.makedirs(out, exist_ok=True)
    for name, (icon, weight) in MAP.items():
        font, table = fonts[weight]
        big = Image.new("L", (em * SCALE * 2, em * SCALE * 2), 0)
        ImageDraw.Draw(big).text((em * SCALE // 2, em * SCALE // 2), chr(table[icon]), font=font, fill=255)
        box = big.getbbox()
        pad = 2 * SCALE
        big = big.crop((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
        img = big.resize((max(1, round(big.width / SCALE)), max(1, round(big.height / SCALE))), Image.LANCZOS)
        rgba = Image.new("RGBA", img.size, (255, 255, 255, 0))
        rgba.putalpha(img)
        rgba.save(os.path.join(out, name + ".png"), optimize=True)
    print("ok:", len(MAP), "icons in", out, file=sys.stderr)


if __name__ == "__main__":
    main()
