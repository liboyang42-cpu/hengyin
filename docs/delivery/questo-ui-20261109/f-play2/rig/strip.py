#!/usr/bin/env python3
"""f-play2: 把若干张 390x844 截图横向拼成一条帧带。用法: strip.py out.jpg a.jpg b.jpg ..."""
import sys
from PIL import Image
ims = [Image.open(n) for n in sys.argv[2:]]
s = Image.new('RGB', (sum(i.width for i in ims) + 6 * (len(ims) - 1), max(i.height for i in ims)), 'white')
x = 0
for i in ims:
    s.paste(i, (x, 0)); x += i.width + 6
s.save(sys.argv[1], quality=72)
