#!/usr/bin/env python3
"""为主题写 canonical15 人工事实草稿 → 提开售审。
机器可推的部分(tickets/chapters/offers/stores/perks/organizers)由 TopicSaleCanonicalBuilder.rebuild
从库里权威读回覆盖,所以草稿只写【人工事实】。形状照 TopicSaleCanonicalTestFixture(仓库自带正控)。"""
import sys, json, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import http, q, admin_token, show, readback

TID = int(sys.argv[1]) if len(sys.argv) > 1 else 990014
atok = admin_token()
chapters = [r[0] for r in q("SELECT id FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (TID,))]
# 机器可推的部分照样要填(codec.writeDraft 也校验形状),用【库里的真行】填,不用占位
ch_rows = [{"id": c, "required": 1, "termsMode": "PERK", "description": "体验%d" % i, "blocksJson": "[{}]"}
           for i, c in enumerate(chapters)]
of_rows = [{"id": r[0], "chapterId": r[1], "merchantId": r[2], "perkId": r[3], "termsMode": r[4], "perHeadFee": None}
           for r in q("SELECT id,chapter_id,merchant_id,perk_id,terms_mode FROM merchant_chapter_offer WHERE topic_id=%s ORDER BY id", (TID,))]
st_rows = [{"nodeId": r[0], "chapterId": r[1], "merchantMemberId": r[2], "address": r[3],
            "longitude": str(r[4]), "latitude": str(r[5])}
           for r in q("SELECT id,chapter_id,merchant_member_id,address,longitude,latitude FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY id", (TID,))]
pk_rows = [{"id": r[0], "name": r[1], "perkType": 0, "retailValue": "20", "redeemRule": "到店核销"}
           for r in q("SELECT id,name FROM coop_perk WHERE id IN (SELECT perk_id FROM merchant_chapter_offer WHERE topic_id=%s)", (TID,))]
tk_rows = [{"id": r[0], "price": str(r[1]), "totalInventory": r[2], "startTime": 1000, "endTime": 2000,
            "signupDeadline": 900, "meetingPoint": "中心广场", "gatherLng": "121.1", "gatherLat": "31.1",
            "refundRule": "开场前可退", "subType": r[3] or "explore"}
           for r in q("SELECT id,price,total_inventory,sub_type FROM oms_ticket WHERE owner_type=1 AND owner_id=%s ORDER BY id", (TID,))]

canonical = {
    "storeCount": 4,
    "salesTargetTotal": 120,
    "ticketPricingPolicy": {"pointsAllowed": False, "discountMode": "NONE", "tickets": tk_rows},
    "sessionWindows": {"startDate": 1000, "endDate": 2000, "saleEndAt": 900, "tickets": tk_rows},
    "participationUnitPolicy": {"adultCount": 1, "petMax": 1, "childrenPolicy": "WITH_GUARDIAN",
                                 "extraAdultPolicy": "NOT_ALLOWED", "companionPolicy": "SAME_TICKET",
                                 "maxTicketPerAccount": 1},
    "sessionVenues": {"adultCapacity": 120, "petCapacity": 120, "authorization": "asset://venue-auth",
                       "rainPlan": "INDOOR", "evacuationPlan": "asset://evacuation",
                       "responsibleParty": "club:7001", "insuranceScope": "asset://policy", "tickets": tk_rows},
    "merchantCashSettlementMode": {"merchantDepositRequired": False, "chapters": ch_rows, "offers": of_rows},
    "storeCluster": st_rows,
    "microExperienceCatalog": {"chapters": ch_rows, "offers": of_rows, "perks": pk_rows},
    "merchantWorkPackage": {"chapters": ch_rows, "workItems": [
        {"chapterId": c, "roleNeed": "店员", "effortStaffMinutes": 30, "effortVenue": "柜台",
         "effortMaterials": "礼包", "effortAvailableSlots": "10:00-18:00", "effortQualifications": "在岗"}
        for c in chapters]},
    "clubWorkPackage": {"opening": "开场", "patrol": "巡场", "photoWrap": "摄影收口", "closing": "闭场",
                         "community": "自愿入群", "HPlan": "一期固定", "contractRef": "asset://club-contract",
                         "organizers": []},
    "contentRightsPackage": {"assetRef": "asset://rights", "owner": "merchant-alliance",
                              "licensedEntity": "chengyin", "validFrom": 1000, "validTo": 2000,
                              "editingRight": True, "humanPortraitConsent": True, "petPortraitConsent": True,
                              "channels": ["mini-program"]},
    "completionPolicy": {"evidenceRule": "FOUR_STORE_REDEEM", "requiredChapterIds": chapters},
    "relationshipPolicy": {"platformTransaction": True, "clubCommunityOptIn": True, "forcedFollow": False,
                            "forcedGroupJoin": False, "merchantContactDisclosure": False},
    "refundReschedulePolicy": {"rescheduleDeadline": 800, "rescheduleLimit": 1,
                                "cancellationPolicy": "REFUND_AND_REBUY", "merchantAbsencePolicy": "FULL_REFUND",
                                "noShowPolicy": "NO_REFUND", "crossEditionTransferAllowed": False,
                                "ticketPolicies": tk_rows},
}
cur = http("GET", "/business/audit/topic/%d/sale-canonical-draft" % TID, token=atok)
ver = ((cur.get("data") or {}) or {}).get("parameterVersion") if isinstance(cur.get("data"), dict) else None
r = http("PUT", "/business/audit/topic/%d/sale-canonical-draft" % TID, token=atok,
         json_body={"schemaVersion": "CANONICAL_15_V1", "expectedParameterVersion": ver or 0,
                    "canonical": canonical})
show("PUT sale-canonical-draft", json.dumps(r, ensure_ascii=False)[:200])
readback("草稿行", "SELECT topic_id,schema_version,parameter_version FROM topic_sale_canonical_draft WHERE topic_id=%s", (TID,))
