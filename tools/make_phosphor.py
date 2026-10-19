"""Renders the icons (white PNGs with transparency) from the Phosphor icon font (MIT), filled weight. Phosphor has no
HDMI icon, so that one is drawn here: the port's outline with its contact strip.
Get the font with: npm pack @phosphor-icons/web@2.1.1 && tar xzf phosphor-icons-web-2.1.1.tgz
Usage: python3 -I make_phosphor.py package/src OUT_DIR [EM_PX]"""
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

# name used in the app -> (Phosphor name, weight: "regular" or "fill")
MAP = {
    "sunny": ("sun", "fill"), "clear_night": ("moon-stars", "fill"),
    "partly_cloudy_day": ("cloud-sun", "fill"), "partly_cloudy_night": ("cloud-moon", "fill"),
    "cloud": ("cloud", "fill"), "foggy": ("cloud-fog", "fill"), "rainy": ("cloud-rain", "fill"),
    "thunderstorm": ("cloud-lightning", "fill"), "snowflake": ("snowflake", "fill"),
    "gamepad": ("game-controller", "fill"),     "apps": ("squares-four", "fill"), "settings": ("gear-six", "fill"), "speaker": ("speaker-high", "fill"),
    "sparkles": ("sparkle", "fill"), "bedtime": ("moon", "fill"), "home": ("house", "fill"),
    "chevron_right": ("caret-right", "fill"), "bell": ("bell", "fill"), "sync": ("arrows-clockwise", "fill"),
    "check": ("check", "fill"), "play": ("play", "fill"), "tv_settings": ("sliders-horizontal", "fill"),
    "chevron_down": ("caret-down", "fill"), "sunrise": ("sun-horizon", "fill"), "sunset": ("sun-horizon", "fill"),
    "live_tv": ("television", "fill"), "tv": ("television-simple", "fill"),
    "compass": ("compass", "fill"), "microphone": ("microphone", "fill"),
    "cur_usd": ("currency-dollar", "fill"), "cur_eur": ("currency-eur", "fill"),
    "cur_gbp": ("currency-gbp", "fill"), "cur_cny": ("currency-cny", "fill"),
}
FILES = {"regular": ("regular/Phosphor.ttf", "regular/style.css"), "fill": ("fill/Phosphor-Fill.ttf", "fill/style.css")}
SCALE = 4   # drawn 4x larger, then scaled down (smooth edges)


def codes(css):
    return {m.group(1): int(m.group(2), 16)
            for m in re.finditer(r'\.ph-([a-z0-9-]+):before\s*\{\s*content:\s*"\\([0-9a-f]+)"', open(css).read())}


def hdmi(em, out):
    """The HDMI port's silhouette, filled (same visual weight as the other filled icons)."""
    s = em * SCALE / 64

    def port(draw, x, y, w, h, fill):
        c = h * 0.42   # the chamfered lower corners
        draw.polygon([((x) * s, y * s), ((x + w) * s, y * s), ((x + w) * s, (y + h - c) * s),
                      ((x + w - c * 1.1) * s, (y + h) * s), ((x + c * 1.1) * s, (y + h) * s), (x * s, (y + h - c) * s)],
                     fill=fill)
    big = Image.new("L", (round(64 * s), round(64 * s)), 0)
    draw = ImageDraw.Draw(big)
    port(draw, 6, 20, 52, 22, 255)
    box = big.getbbox()
    pad = 2 * SCALE
    big = big.crop((box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
    img = big.resize((max(1, round(big.width / SCALE)), max(1, round(big.height / SCALE))), Image.LANCZOS)
    rgba = Image.new("RGBA", img.size, (255, 255, 255, 0))
    rgba.putalpha(img)
    rgba.save(os.path.join(out, "hdmi.png"), optimize=True)


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
    hdmi(em, out)
    print("ok:", len(MAP), "icons in", out, file=sys.stderr)


if __name__ == "__main__":
    main()
