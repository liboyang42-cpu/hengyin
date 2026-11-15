"""F11 Messages and group chat: conversations -> start DM -> send -> send-receipt -> messages -> unread -> read -> group send."""
import uuid
from harness import *


def flow(f):
    a, b = PLAYER, PLAYER2
    f.step("im/conversations", "POST", "/api/im/conversations", a, check=ok_data)
    s = f.step("im/start DM 9004->9009", "POST", "/api/im/start", a, form={"target_member_id": b},
               check=lambda j: need(j, "conversationId"))
    cid = (data_of(s) or {}).get("conversationId")
    f.step("im/start again returns same conversation", "POST", "/api/im/start", a, form={"target_member_id": b},
           check=lambda j: None if (data_of(j) or {}).get("conversationId") == cid else "conversation id changed")
    cmid = "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:8])
    f.step("im/send DM", "POST", "/api/im/send", a,
           form={"conversation_id": cid, "msg_type": 1, "content": "QA 私信 %s" % RUN_ID, "client_message_id": cmid},
           check=lambda j: None if (data_of(j) or {}).get("checkStatus") == 1 else "checkStatus != 1")
    f.step("im/send-receipt", "POST", "/api/im/send-receipt", a, form={"conversation_id": cid, "client_message_id": cmid},
           check=lambda j: None if (data_of(j) or {}).get("found") else "receipt not found")
    f.step("im/messages (receiver)", "POST", "/api/im/messages", b, form={"conversation_id": cid, "cursor_id": 0, "size": 30},
           check=lambda j: None if RUN_ID in str(data_of(j)) else "DM not visible to receiver")
    f.step("im/unread-total >= 1 (receiver)", "POST", "/api/im/unread-total", b,
           check=lambda j: None if isinstance(data_of(j), int) and data_of(j) >= 1 else "unread-total < 1")
    f.step("im/read", "POST", "/api/im/read", b, form={"conversation_id": cid})
    f.step("im/send group (club_7001 conversation 3)", "POST", "/api/im/send", b,
           form={"conversation_id": int(os.environ.get("E2E_CLUB_CONV", "3")), "msg_type": 1,
                 "content": "QA 群消息 %s" % RUN_ID, "client_message_id": "qa-%s-g" % RUN_ID}, check=ok_data)
    f.step("im/messages group", "POST", "/api/im/messages", a,
           form={"conversation_id": int(os.environ.get("E2E_CLUB_CONV", "3")), "cursor_id": 0, "size": 30},
           check=lambda j: None if "QA 群消息 %s" % RUN_ID in str(data_of(j)) else "group message not visible")


flow.FLOW = ("F11", "Messages and group chat")
if __name__ == "__main__":
    main(flow)
