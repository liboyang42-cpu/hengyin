import sys
from PIL import Image
out=sys.argv[1]; files=sys.argv[2:]; cols=min(6,len(files))
ims=[Image.open(f).resize((260,563)) for f in files]
rows=(len(ims)+cols-1)//cols
S=Image.new('RGB',(cols*260,rows*563),'white')
for i,im in enumerate(ims): S.paste(im,((i%cols)*260,(i//cols)*563))
S.save(out,quality=80)
