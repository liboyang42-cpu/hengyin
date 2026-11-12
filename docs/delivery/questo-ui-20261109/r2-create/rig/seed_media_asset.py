"""RIG-DB-ONLY (r2-create): the rig's WeChat content-security stub rejects every upload
(「安全检查服务异常」, HARNESS — qx-a5d saw the same), so no upload can return an assetId here.
Insert one APPROVED app_media_asset owned by 9002 (same columns the upload path writes), print its id;
the provenance sheet then saves through the real PUT /api/media/asset/{id}/provenance."""
import os, sys, pathlib
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import ex, q, readback
ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,owner_type,create_time,update_time) "
   "VALUES (9002,'upload/r2c-cover.jpg','IMAGE','APPROVED',0,'APP',now(),now())")
aid = q("SELECT max(id) FROM app_media_asset WHERE member_id=9002 AND object_key='upload/r2c-cover.jpg'")[0][0]
readback("asset", "SELECT id,member_id,review_state,owner_type,source_type,license_note FROM app_media_asset WHERE id=%s", (aid,))
print("ASSET_ID=%s" % aid)
