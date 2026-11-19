#!/usr/bin/env python3
"""Génère le favicon et les icônes de la PWA à partir de branding/logo.jpeg.

Usage : python3 scripts/generate-icons.py      (nécessite Pillow : pip install pillow)

Le logo est un tracé fin sur fond noir : pour rester lisible aux petites tailles, les traits sont épaissis avant
la réduction (plus la taille est petite, plus on épaissit). Résultat dans dashboard/public/.
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter, ImageOps

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "branding" / "logo.jpeg"
OUT = ROOT / "dashboard" / "public"
ICONS = OUT / "icons"
BLACK = (0, 0, 0)


def load_logo() -> Image.Image:
    """Logo recadré au plus près, fond noir nettoyé (le JPEG laisse un léger bruit)."""
    im = Image.open(SOURCE).convert("RGB")
    luminance = im.convert("L")
    bbox = luminance.point(lambda v: 255 if v > 40 else 0).getbbox()
    im = im.crop(bbox)
    return im.point(lambda v: 0 if v < 14 else v)


def square(logo: Image.Image, size: int, height_ratio: float, bold: int) -> Image.Image:
    """Logo centré sur un carré noir ; il occupe `height_ratio` de la hauteur.

    `bold` (impair, en pixels du logo source) épaissit les traits clairs. Une marge noire est ajoutée avant, sinon
    la dilatation serait rognée au bord du cadre (tête, pointe de l'éclair).
    """
    pad = bold // 2 + 2
    padded = ImageOps.expand(logo, border=pad, fill=BLACK)
    if bold > 1:
        padded = padded.filter(ImageFilter.MaxFilter(bold))
    scale = round(size * height_ratio) / logo.height
    resized = padded.resize(
        (max(1, round(padded.width * scale)), max(1, round(padded.height * scale))), Image.LANCZOS
    )
    canvas = Image.new("RGB", (size, size), BLACK)
    canvas.paste(resized, ((size - resized.width) // 2, (size - resized.height) // 2))
    return canvas


def silhouette(logo: Image.Image, size: int, height_ratio: float, bold: int) -> Image.Image:
    """Blanc sur transparent (badge de notification Android : seul le canal alpha compte)."""
    mask = square(logo, size, height_ratio, bold).convert("L").point(lambda v: min(255, v * 3))
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(mask)
    return out


def transparent(logo: Image.Image, height: int) -> Image.Image:
    """Logo sur fond transparent (écran de connexion, clair ou sombre) : le noir devient transparence.

    L'opacité vient de la clarté du pixel et la couleur est « dépremultipliée » : pas de liseré sombre autour des
    traits quand le fond n'est pas noir.
    """
    scale = height / logo.height
    small = logo.resize((round(logo.width * scale), height), Image.LANCZOS)
    r, g, b = small.split()
    # Opacité renforcée (x1,7) : les bleus sombres du logo restent pleins au lieu de devenir pastel sur fond clair.
    alpha = ImageChops.lighter(ImageChops.lighter(r, g), b).point(lambda v: min(255, round(v * 1.7)))

    def unpremultiply(channel: Image.Image) -> Image.Image:
        values = zip(channel.get_flattened_data(), alpha.get_flattened_data())
        return Image.frombytes(
            "L", channel.size, bytes(min(255, c * 255 // a) if a else 0 for c, a in values)
        )

    out = Image.merge("RGB", tuple(unpremultiply(c) for c in (r, g, b)))
    out.putalpha(alpha)
    return out


def main() -> None:
    ICONS.mkdir(parents=True, exist_ok=True)
    logo = load_logo()

    square(logo, 192, 0.78, 5).save(ICONS / "icon-192.png")
    square(logo, 512, 0.78, 5).save(ICONS / "icon-512.png")
    # maskable : tout doit tenir dans le cercle central (80 % de la largeur), le reste peut être rogné
    square(logo, 512, 0.58, 5).save(ICONS / "icon-maskable-512.png")
    # iOS arrondit lui-même les coins et refuse la transparence
    square(logo, 180, 0.78, 5).save(ICONS / "apple-touch-icon.png")
    silhouette(logo, 96, 0.9, 25).save(ICONS / "badge-96.png")

    square(logo, 32, 0.94, 25).save(ICONS / "favicon-32.png")
    sizes = [16, 32, 48]
    icons = [square(logo, s, 0.94, 27 if s <= 16 else 25) for s in sizes]
    icons[-1].save(OUT / "favicon.ico", sizes=[(s, s) for s in sizes], append_images=icons[:-1])
    transparent(logo, 384).save(ICONS / "logo.png", optimize=True)
    print("Icônes générées dans", OUT)


if __name__ == "__main__":
    main()
