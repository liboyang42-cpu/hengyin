import sys
from PIL import Image
# usage: pair.py before.jpg after.jpg out.jpg
a=Image.open(sys.argv[1]).convert('RGB');b=Image.open(sys.argv[2]).convert('RGB')
c=Image.new('RGB',(a.width+b.width+12,max(a.height,b.height)),(255,255,255));c.paste(a,(0,0));c.paste(b,(a.width+12,0));c.save(sys.argv[3],quality=80)
