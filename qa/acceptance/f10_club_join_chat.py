"""F10 Club join and chat: list -> detail -> join (as non-member 9006) -> leader approves -> members -> chat -> send -> list.
Every club call is JSON, as the native client sends it (ClubService.post, ClubActionService, ClubManagementService).
fixtures.py takes 9006 out of club 7001 first, so the join is fresh every run."""
import uuid
from harness import *


def flow(f):
    joiner, leader = int(os.environ.get("E2E_CLUB_JOINER", "9006")), CLUB_LEADER
    f.step("club/list (JSON)", "POST", "/api/club/list", joiner, js={},
           check=lambda j: None if str(CLUB) in str(data_of(j)) else "club 7001 not listed")
    f.step("club/detail", "POST", "/api/club/detail", joiner, js={"id": CLUB}, check=ok_data)
    j = f.step("club/join", "POST", "/api/club/join", joiner, js={"id": CLUB, "joinMessage": "QA 验收入会"},
               expect_code=(200,))
    f.step("club/join-requests (leader)", "POST", "/api/club/join-requests", leader, js={"clubId": CLUB}, check=ok_data)
    f.step("club/join-request/approve (leader)", "POST", "/api/club/join-request/approve", leader,
           js={"clubId": CLUB, "memberId": joiner}, expect_code=(200, 400),  # 400 "入会申请不存在或已处理": joined directly
           check=lambda j: None if j.get("code") == 200 or "已处理" in (j.get("msg") or "") else "msg=%s" % j.get("msg"))
    f.step("club/members lists joiner", "POST", "/api/club/members", leader, js={"clubId": CLUB},
           check=lambda j: None if str(joiner) in str(data_of(j)) else "joiner not in members")
    c = f.step("club/chat opens group conversation", "POST", "/api/club/chat", joiner, js={"id": CLUB},
               check=lambda j: need(j, "conversationId"))
    cid = (data_of(c) or {}).get("conversationId")
    f.step("im/send to club chat", "POST", "/api/im/send", joiner,
           form={"conversation_id": cid, "msg_type": 1, "content": "QA 验收群聊 %s" % RUN_ID,
                 "client_message_id": "qa-%s-%s" % (RUN_ID, uuid.uuid4().hex[:8])}, check=ok_data)
    f.step("im/messages in club chat", "POST", "/api/im/messages", joiner,
           form={"conversation_id": cid, "cursor_id": 0, "size": 30},
           check=lambda j: None if RUN_ID in str(data_of(j)) else "sent message not in history")


flow.FLOW = ("F10", "Club join and chat")
if __name__ == "__main__":
    main(flow)
