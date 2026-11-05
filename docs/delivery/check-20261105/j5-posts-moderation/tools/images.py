"""Made-up images: a generated gradient registered as 'known bad' (fingerprint only) through the real admin endpoint,
its near copy, and an unrelated clean image. Writes data URLs for the browser camera seam."""
import io, json, base64, uuid, urllib.request
from common import *
from PIL import Image, ImageEnhance
def jpeg(img, q=90):
    b = io.BytesIO(); img.save(b, "JPEG", quality=q); return b.getvalue()
base = Image.new("RGB", (320, 240)); px = base.load()
for x in range(320):
    for y in range(240):
        px[x, y] = ((x * 7 + y * 3) % 256, (x * y // 50) % 256, (255 - x) % 256)
bad = jpeg(base)
near = jpeg(ImageEnhance.Brightness(base.resize((300, 225))).enhance(1.06), 70)
clean = Image.new("RGB", (320, 240)); pc = clean.load()
for x in range(320):
    for y in range(240):
        pc[x, y] = (40 + x // 4, 90 + y // 4, 160)
clean = jpeg(clean)
out = {k: "data:image/jpeg;base64," + base64.b64encode(v).decode() for k, v in (("bad", bad), ("near", near), ("clean", clean))}
json.dump(out, open("/tmp/claude-0/j5-images.json", "w"))
bnd = "rig" + uuid.uuid4().hex
body = b"".join([("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (bnd, k, v)).encode() for k, v in {"category": "OTHER", "note": "j5 made-up"}.items()]
                + [("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"bad.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n" % bnd).encode() + bad + b"\r\n", ("--%s--\r\n" % bnd).encode()])
req = urllib.request.Request("http://127.0.0.1:8099/business/sensitive/images", data=body, method="POST",
                             headers={"Content-Type": "multipart/form-data; boundary=" + bnd, "Authorization": "Bearer " + admin_token()})
print(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req).read().decode()[:200])
print(q("SELECT id,LENGTH(sha256),dhash IS NOT NULL FROM content_sensitive_image"))
