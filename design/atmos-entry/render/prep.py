#!/usr/bin/env python3
"""Builds the render textures from NASA imagery (see ../CREDITS.md).

Inputs in .cache/nasa/ (downloaded by run.cjs):
  bmng21600.jpg       Blue Marble Next Generation, Dec 2004, topo+bathy, 21600x10800
  clouds8192.png      Blue Marble cloud composite, 8192x4096 grey
  bm2016_3km_gray.jpg Black Marble 2016, 3 km grey, 13500x6750
  bsc5.json           Yale Bright Star Catalogue (v5), RA/Dec, V mag, colour temperature
Outputs in .cache/textures/:
  world_day.png   8192x4096 RGB: BMNG colour
  world_aux.png   8192x4096 RGB: R = clouds, G = city lights, B = water mask
  china_day.png   BMNG at full resolution (3960x2400) over lon 72..138, lat 14..54
  china_aux.png   same window, R = clouds, G = lights, B = water mask
(No alpha channels, so browser premultiplication can't damage colour.)
  stars.json      [x, y, z, V, K] for stars with V <= 6.0
"""
import json, math, os, re, sys
from PIL import Image, ImageFilter
import numpy as np

Image.MAX_IMAGE_PIXELS = None
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '.cache', 'nasa')
OUT = os.path.join(HERE, '.cache', 'textures')
os.makedirs(OUT, exist_ok=True)
W, H = 8192, 4096
WIN = dict(lon0=72, lon1=138, lat0=14, lat1=54)


def water_mask(rgb):
    """BMNG topo+bathy has no mask, so classify by colour: water is blue-dominant
    (deep navy through cyan shallows); land, ice and desert are not."""
    a = rgb.astype(np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    m = (b > r * 1.35 + 4) & (b > g * 0.92)
    # shallow cyan shelves
    m |= (b > r * 1.6) & (g > r * 1.3) & (b > 60)
    img = Image.fromarray((m * 255).astype(np.uint8))
    img = img.filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.GaussianBlur(1.2))
    return img


def crop_window(img, full_w, full_h):
    x0 = round((WIN['lon0'] + 180) / 360 * full_w)
    x1 = round((WIN['lon1'] + 180) / 360 * full_w)
    y0 = round((90 - WIN['lat1']) / 180 * full_h)
    y1 = round((90 - WIN['lat0']) / 180 * full_h)
    return img.crop((x0, y0, x1, y1))


def main():
    print('day')
    day = Image.open(os.path.join(SRC, 'bmng21600.jpg')).convert('RGB')
    china = crop_window(day, day.width, day.height)
    world = day.resize((W, H), Image.LANCZOS)
    del day
    masks = {}
    for name, im in (('world', world), ('china', china)):
        masks[name] = water_mask(np.asarray(im))
        im.save(os.path.join(OUT, name + '_day.png'), compress_level=3)
        print(name, im.size)
    cw, ch = china.size

    print('clouds, lights')
    clouds = Image.open(os.path.join(SRC, 'clouds8192.png')).convert('L')
    lights = Image.open(os.path.join(SRC, 'bm2016_3km_gray.jpg')).convert('L')
    lw = lights.resize((W, H), Image.LANCZOS)
    cl = clouds.resize((W, H), Image.LANCZOS) if clouds.size != (W, H) else clouds
    Image.merge('RGB', (cl, lw, masks['world'])).save(os.path.join(OUT, 'world_aux.png'), compress_level=3)
    cc = crop_window(clouds, clouds.width, clouds.height).resize((cw, ch), Image.BICUBIC)
    lc = crop_window(lights, lights.width, lights.height).resize((cw, ch), Image.LANCZOS)
    Image.merge('RGB', (cc, lc, masks['china'])).save(os.path.join(OUT, 'china_aux.png'), compress_level=3)

    print('stars')
    def ang(s, unit):
        n = [float(x) for x in re.findall(r'[\d.]+', s)]
        v = n[0] + n[1] / 60 + n[2] / 3600
        return (-v if s.strip().startswith('-') else v) * unit
    stars = []
    for s in json.load(open(os.path.join(SRC, 'bsc5.json'))):
        if 'RA' not in s or 'V' not in s:
            continue
        V = float(s['V'])
        if V > 6.0:
            continue
        ra = math.radians(ang(s['RA'], 15)); de = math.radians(ang(s['Dec'], 1))
        K = float(s.get('K', 6000) or 6000)
        stars.append([round(math.cos(de) * math.cos(ra), 5), round(math.sin(de), 5), round(math.cos(de) * math.sin(ra), 5), V, K])
    json.dump(stars, open(os.path.join(OUT, 'stars.json'), 'w'))
    print(len(stars), 'stars')


if __name__ == '__main__':
    main()
