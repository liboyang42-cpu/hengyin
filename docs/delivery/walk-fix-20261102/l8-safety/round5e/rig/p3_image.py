"""Part 3: known-bad image refused at upload, including a near copy. Images are generated noise/gradients
(made up), registered as 'known bad' through the real admin endpoint (fingerprint only)."""
import sys, io, uuid, random, urllib.request
from proof_common import *
from chain_lib import BASE
from PIL import Image, ImageEnhance

def jpeg(img, q=90):
    b = io.BytesIO(); img.save(b, "JPEG", quality=q); return b.getvalue()

random.seed(7)
base = Image.new("RGB", (320, 240))
px = base.load()
for x in range(320):
    for y in range(240):
        px[x, y] = ((x * 7 + y * 3) % 256, (x * y // 50) % 256, (255 - x) % 256)
bad = jpeg(base)
near = jpeg(ImageEnhance.Brightness(base.resize((300, 225))).enhance(1.06), 70)   # resized, brighter, recompressed
other = Image.new("RGB", (320, 240))
po = other.load()
for x in range(320):
    for y in range(240):
        po[x, y] = (random.randrange(256), random.randrange(256), random.randrange(256))
clean = jpeg(other)

def multipart(path, fields, filename, data, tok):
    bnd = "rig" + uuid.uuid4().hex
    parts = []
    for k, v in fields.items():
        parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (bnd, k, v)).encode())
    parts.append(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Type: image/jpeg\r\n\r\n" % (bnd, filename)).encode() + data + b"\r\n")
    parts.append(("--%s--\r\n" % bnd).encode())
    req = urllib.request.Request(BASE + path, data=b"".join(parts), method="POST",
                                 headers={"Content-Type": "multipart/form-data; boundary=" + bnd, "Authorization": "Bearer " + tok})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")

IMG_MSG = "图片未通过内容安全检查，请更换后重试"  # ISensitiveContentLibrary.IMAGE_VIOLATION_MESSAGE
atok = admin_token()
s, r = multipart("/business/sensitive/images", {"category": "OTHER", "note": "rig made-up"}, "bad.jpg", bad, atok)
rec("admin registers known-bad image (fingerprint only)", s == 200 and r.get("code") == 200, resp=r)
rec("db stores sha256 + dhash, no image bytes", q("SELECT LENGTH(sha256), dhash IS NOT NULL FROM content_sensitive_image") == ((64, 1),),
    cols=[c[0] for c in q("SHOW COLUMNS FROM content_sensitive_image")])
p4 = player(4)
h0 = q("SELECT COUNT(*) FROM content_sensitive_hit WHERE source='IMAGE'")[0][0]
for label, data in (("exact copy", bad), ("near copy (resized, +6% brightness, q70)", near)):
    s, r = multipart("/api/common/uploadOSS", {}, "x.jpg", data, p4)
    rec("player upload of %s refused by library" % label, r.get("code") == 400 and r.get("msg") == IMG_MSG, http=s, resp=r)
s, r = multipart("/api/common/uploadOSS", {}, "c.jpg", clean, p4)
rec("control: unrelated image is not stopped by the library (reaches WeChat img_sec_check, which the rig cannot reach)",
    r.get("msg") != IMG_MSG, http=s, resp=r)
h1 = q("SELECT COUNT(*) FROM content_sensitive_hit WHERE source='IMAGE'")[0][0]
rec("image hits recorded", h1 - h0 == 2, before=h0, after=h1)
dump(sys.argv[1] if len(sys.argv) > 1 else "p3.json")
