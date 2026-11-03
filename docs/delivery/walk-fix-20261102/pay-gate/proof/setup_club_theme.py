"""Round 3D setup: club 7002's 主理人 creates a classic (ORIENTEERING) theme with a self-play pass and a session
ticket through the real create + audit endpoints. Prints TOPIC_ID."""
import datetime, sys
from rig_env import http, q, admin_token, app_token, step, show, readback, j, CLUB_ID, OWNER_PHONE

BASE = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda off: (BASE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")


def main():
    atok = admin_token()
    otok, _ = app_token(OWNER_PHONE)
    step("主理人实名(真实入口)")
    st = http("POST", "/api/publisher/identity/status", token=otok, json_body={})
    if not (st.get("data") or {}).get("registered"):
        st = http("POST", "/api/publisher/identity", token=otok, json_body={
            "realName": "测试执行主理人", "idCard": "110101199001010031", "consent": True, "source": "topic_publish"})
    show("identity", j(st, 160))
    step("主理人建一个打卡玩法模板(真实入口 /api/template/draft)")
    tr = http("POST", "/api/template/draft", token=otok, json_body={"title": "R3D 到点打卡", "validationMethod": 1, "isSync": 0})
    show("template", j(tr, 200))
    template_id = tr.get("data")
    if not template_id:
        return 1
    step("建期:俱乐部 %d 自有经典定向主题(自玩通行证 + 场次票)" % CLUB_ID)
    chapters = [{"name": "第%d章 %s" % (i + 1, n), "description": "R3D 证明用章节 %s" % n, "schemaVersion": 1,
                 "required": 1, "blocks": [{"type": "text", "content": "到点打卡"}, {"type": "node", "nodeIndex": 0}],
                 "nodes": [{"name": "%s点" % n, "address": "上海市黄浦区%s" % n,
                            "longitude": "%.6f" % (121.47 + i * 0.01), "latitude": "%.6f" % (31.23 + i * 0.01),
                            "nodeTime": 30, "templateId": template_id}]} for i, n in enumerate(["外滩", "豫园"])]
    dto = {"name": "R3D 俱乐部自玩主题", "subtitle": "pay-gate", "description": "Round 3D 资金证明",
           "startDate": d(-1), "endDate": d(30), "saleEndAt": d(29), "categoryIds": "1", "imgUrl": "",
           "productType": 1, "publishMode": "normal", "clubId": CLUB_ID,
           "selfPlay": 1, "selfPlayPrice": "29.99", "selfPlayQuota": 50,
           "chapters": chapters,
           "tickets": [{"name": "场次票", "price": "39.99", "totalStock": 50, "mode": 1,
                        "refundRule": "开场前24小时可退", "refundSupported": True,
                        "startTime": d(-1), "endTime": d(30), "saleStartTime": d(-1), "saleEndTime": d(29)}]}
    r = http("POST", "/api/topic/v2/create", token=otok, json_body=dto)
    show("create", j(r, 400))
    data = r.get("data")
    tid = data if isinstance(data, int) else ((data or {}).get("topicId") or (data or {}).get("id"))
    if not tid:
        return 1
    for task_id, biz in q("SELECT id,biz_type FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,)):
        rr = http("POST", "/business/audit/review", token=atok,
                  json_body={"taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "R3D 放行"})
        show("review %s %s" % (task_id, biz), (rr.get("msg") or "")[:60])
    readback("topic", "SELECT id,club_id,member_id,product_type,lifecycle,status,user_status,fee_platform_rate,"
             "self_play,self_play_price FROM cms_topic WHERE id=%s", (tid,))
    readback("tickets", "SELECT id,owner_type,owner_id,name,price,refund_rule FROM oms_ticket WHERE owner_id=%s "
             "AND del_flag=0", (tid,))
    readback("content slot", "SELECT slot,entity_type,entity_id,status FROM product_role WHERE topic_id=%s", (tid,))
    print("\nTOPIC_ID=%s" % tid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
