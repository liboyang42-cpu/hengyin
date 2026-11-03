import sys
from PIL import Image, ImageDraw
src, dst = sys.argv[1], sys.argv[2]
names = sys.argv[3:]
import os
for n in names:
    a=Image.open(f'{src}/{n}-before.png').convert('RGB'); b=Image.open(f'{src}/{n}-after.png').convert('RGB')
    c=Image.new('RGB',(a.width+b.width+12,a.height+28),'white'); c.paste(a,(0,28)); c.paste(b,(a.width+12,28))
    d=ImageDraw.Draw(c); d.text((8,8),'before',fill='black'); d.text((a.width+20,8),'after',fill='black')
    c=c.resize((c.width*3//4,c.height*3//4))
    out=f'{dst}/{n}.png'
    for colors in (256,128,64):
        c.quantize(colors=colors).save(out, optimize=True)
        if os.path.getsize(out) < 300*1024: break
    print(n, os.path.getsize(out))
