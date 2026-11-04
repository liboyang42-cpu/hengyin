"""FIXTURE: write the look string the editor's look sheet produced (formData.look, read from the page) into cms_topic.look_json.
Saving through the editor needed the 主题详情 gaps (category, cover) filled first; that is the p3 lane's ground. Throwaway DB only."""
from common import *
tid=int(sys.argv[1]); look=sys.argv[2]
cl.ex("UPDATE cms_topic SET look_json=%s WHERE id=%s", (look, tid))
print(cl.q("SELECT id,look_json FROM cms_topic WHERE id=%s", (tid,)))
