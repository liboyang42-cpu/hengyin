"""RIG-DB-ONLY (qx-s1, same as qx-b4's fixture): no endpoint writes cms_topic.city_code on create, so the template's
source city is set in the rig DB to 310100 (上海市) so /info exposes locationRequirement.cityCode and the adoption sheet opens."""
import os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import ex, readback
tid = int(sys.argv[1])
ex("UPDATE cms_topic SET city_code='310100' WHERE id=%s AND is_template=1", (tid,))
readback("template city", "SELECT id,is_template,city_code FROM cms_topic WHERE id=%s", (tid,))
