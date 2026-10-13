package com.chengyinhub.business.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClubLeadWorkspaceMapperContractTest {

    @Test
    void leadSessionProjectionRequiresAcceptedClubCooperationButNotLazyLeadState() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "ClubLeadMapper.xml")), StandardCharsets.UTF_8);

        String leadSessionQuery = xml.substring(xml.indexOf("<select id=\"selectLeadSessionsByTopic\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectLeadSessionsByTopic\"")));

        assertTrue(leadSessionQuery.contains("from cms_activity ca"),
                "已由俱乐部承接但尚未触发带队 API 的活动也必须进入主题工作台");
        assertTrue(leadSessionQuery.contains("left join club_lead_state cls"),
                "带队状态是懒建事实，不能作为工作台场次的主表");
        assertTrue(leadSessionQuery.contains("exists ("),
                "承接资格必须由有效合作单判定，避免一对多合作单造成场次重复");
        assertTrue(leadSessionQuery.contains("from coop_order co"),
                "承接资格的单一真源是 coop_order");
        assertTrue(leadSessionQuery.contains("co.to_type = 'club'"),
                "仅俱乐部承接单能进入带队履约投影");
        assertTrue(leadSessionQuery.contains("co.to_id = ca.club_id"),
                "合作单必须对应活动实际归属的俱乐部");
        assertTrue(leadSessionQuery.contains("co.status = 0") && leadSessionQuery.contains("co.del_flag = 0"),
                "仅生效且未删除的合作单证明俱乐部已承接");
        assertTrue(leadSessionQuery.contains("inner join club on club.id = ca.club_id and club.del_flag = 0"),
                "已软删或不存在的俱乐部不能作为带队场次的责任主体");
    }


    @Test
    void leadAttendanceProjectionUsesTheSameEffectiveClubContract() throws Exception {
        String xml = new String(Files.readAllBytes(Paths.get("..", "chengyinhub-system", "src", "main",
                "resources", "mapper", "business", "ClubLeadMapper.xml")), StandardCharsets.UTF_8);
        String attendanceQuery = xml.substring(xml.indexOf("<select id=\"selectLeadAttendanceByTopic\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"selectLeadAttendanceByTopic\"")));

        assertTrue(attendanceQuery.contains("inner join club on club.id = ca.club_id and club.del_flag = 0"),
                "已删除俱乐部的签到不得抬高主题履约到场数");
        assertTrue(attendanceQuery.contains("exists (")
                        && attendanceQuery.contains("from coop_order co")
                        && attendanceQuery.contains("co.to_type = 'club'")
                        && attendanceQuery.contains("co.to_id = ca.club_id")
                        && attendanceQuery.contains("co.status = 0")
                        && attendanceQuery.contains("co.del_flag = 0"),
                "到场计数必须与场次投影一样仅认生效俱乐部合作单");
    }
}
