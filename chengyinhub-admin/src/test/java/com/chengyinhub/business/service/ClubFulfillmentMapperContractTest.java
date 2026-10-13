package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubFulfillmentMapperContractTest {

    @Test
    void clubFulfillmentQueriesRequireAcceptedClubCooperation() throws Exception {
        String leadXml = read("ClubLeadMapper.xml");
        String verifyXml = read("CoopGroupVerifyMapper.xml");

        String sessionQuery = query(leadXml, "selectFulfillmentSessionsByClub");
        assertAcceptedClubProjection(sessionQuery);
        assertTrue(sessionQuery.contains("cls.leader_member_id as leaderMemberId"),
                "责任领队真源必须是场次 lead_state，不得从俱乐部主理人补猜");
        assertFalse(sessionQuery.contains("coalesce(cls.leader_member_id, club.member_id)"),
                "未激活场次应显示待分配，不能把俱乐部主理人伪装成已分配领队");
        assertAcceptedClubProjection(query(leadXml, "selectFulfillmentAttendanceByClub"));
        String verificationQuery = query(verifyXml, "selectFulfillmentVerificationsByClub");
        assertAcceptedClubProjection(verificationQuery);
        assertTrue(verificationQuery.contains("verify.activity_id = ca.id"),
                "团核销必须按签名绑定的带队场次归属，不能再由主题和领队反推");

        String settlementQuery = query(verifyXml, "selectSettlementBasisByTopicAndMerchant");
        assertTrue(settlementQuery.contains("from coop_group_member_verify member_verify"),
                "团结算必须读取玩家核销的场次事实，不能只凭领队台账推断");
        assertTrue(settlementQuery.contains("group_verify.activity_id = member_verify.activity_id"),
                "商家接待与玩家核销必须以同一个 activity 精确关联");
        assertTrue(settlementQuery.contains("exists ("),
                "同商家同场次由不同管理员重复出码时，不得因 join 重复计费");
        assertFalse(settlementQuery.contains("leader_member_id = member_verify"),
                "核销人只是审计信息，不能作为独立团身份，否则同场次换管理员会漏算");
    }

    private String read(String fileName) throws Exception {
        return new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", fileName)), StandardCharsets.UTF_8);
    }

    private String query(String xml, String id) {
        int start = xml.indexOf("<select id=\"" + id + "\"");
        return xml.substring(start, xml.indexOf("</select>", start));
    }

    private void assertAcceptedClubProjection(String query) {
        assertTrue(query.contains("inner join club") && query.contains("club.del_flag = 0"),
                "软删或不存在的俱乐部不应作为履约责任主体");
        assertTrue(query.contains("exists (") && query.contains("from coop_order co"),
                "承接真源必须是生效合作单，不能只凭活动 club_id");
        assertTrue(query.contains("co.to_type = 'club'") && query.contains("co.to_id = ca.club_id"),
                "合作单必须明确指向活动所属俱乐部");
        assertTrue(query.contains("co.status = 0") && query.contains("co.del_flag = 0"),
                "仅生效且未删除的合作单证明承接成立");
    }
}
