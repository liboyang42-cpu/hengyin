package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.mapper.RoamHangoutMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 漫游·附近的局 mapper 契约:selectNearby 必须一条 SQL 出人数 / isMember,并排除我拉黑的局主。
 * 负控:去掉 XML 里的 im_block 子句 → 第一条红;去掉 having distance → 半径失效那条红。
 */
class RoamHangoutMapperContractTest {

    private static String select(String id) throws Exception {
        Path p = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/RoamHangoutMapper.xml");
        if (!Files.exists(p)) p = Paths.get("chengyinhub-system/src/main/resources/mapper/business/RoamHangoutMapper.xml");
        String xml = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        // <include refid> 展开:把 sql 片段原文拼进来再断言,否则子句藏在片段里看不见
        String body = xml.substring(xml.indexOf("id=\"" + id + "\""));
        body = body.substring(0, body.indexOf("</select>"));
        for (String frag : new String[]{"viewerCols", "nearbyWhere", "distanceCol"}) {
            if (body.contains("refid=\"" + frag + "\"")) {
                String f = xml.substring(xml.indexOf("<sql id=\"" + frag + "\">"));
                body += f.substring(0, f.indexOf("</sql>"));
            }
        }
        return body;
    }

    @Test
    void selectNearby一条SQL带拉黑过滤与成员数() throws Exception {
        String sel = select("selectNearby");
        assertTrue(sel.contains("im_block"), "必须排除我拉黑的人开的局");
        assertTrue(sel.contains("im_conversation_member"), "人数与 isMember 走群成员表子查询");
        assertTrue(sel.contains("6371000"), "haversine 距离");
        assertTrue(sel.contains("h.status = 0"), "只出进行中");
        assertTrue(sel.contains("having distance &lt;= #{radius}"), "半径精筛");
    }

    @Test
    void countNearby与selectNearby同一口径() throws Exception {
        String cnt = select("countNearby");
        assertTrue(cnt.contains("refid=\"nearbyWhere\"") || cnt.contains("im_block"), "探半径必须同样排除拉黑");
        assertTrue(cnt.contains("having distance &lt;= #{radius}"));
    }

    @Test
    void 活动叠加只出公开口径且无坐标不出() throws Exception {
        String sel = select("selectNearbyActivities");
        assertTrue(sel.contains("a.publish_status = 1") && sel.contains("a.cancel_time is null"));
        assertTrue(sel.contains("r.member_id = #{viewerId}") && sel.contains("r.owner_type = 2")
                && sel.contains("r.owner_id = a.id") && sel.contains("r.payment_status = 2")
                && sel.contains("r.registration_status = 2") && sel.contains("as viewerHasTicket"),
                "报名事实必须绑定当前玩家、活动及有效已支付报名，不能依赖附近队伍");
        assertTrue(sel.contains("a.latitude &lt;&gt; ''"), "无坐标不落点");
        assertTrue(sel.contains("a.start_date as startDate") && sel.contains("a.end_date as endDate"),
                "活动档期不吐出来,小程序判不出「限时活动」,粉色点位永远画不出来");
        assertTrue(sel.contains("a.topic_id as topicId"),
                "活动点位必须带 topicId,地图报名入口才能稳定进入主题详情");
        assertTrue(sel.contains("t.product_type as productType"),
                "活动点位必须带主题玩法,地图不能把自由探索误标成城市定向");
    }

    @Test
    void 附近主题_口径与公开列表一致_按第一个有坐标节点落点() throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "../chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml")), "UTF-8");
        int at = xml.indexOf("<select id=\"selectNearbyTopics\"");
        assertTrue(at >= 0, "CmsTopicMapper 缺 selectNearbyTopics,已发布主题不会出现在附近的局地图上");
        String sel = xml.substring(at, xml.indexOf("</select>", at));
        assertTrue(sel.contains("t.status = 1 and t.user_status = 1 and t.del_flag = 0 and t.is_template = 0"), "公开口径要与 selectPublicCmsTopicList 一致");
        assertTrue(sel.contains("order by y.sort_id asc"), "落点取第一个节点(sort_id 最小)");
        assertTrue(sel.contains("r.member_id = #{viewerId}") && sel.contains("r.payment_status = 2")
                && sel.contains("r.registration_status = 2") && sel.contains("r.owner_type = 1 and r.owner_id = t.id")
                && sel.contains("ticket_activity.topic_id = t.id"), "主题持票包含本人主题票及本主题活动票");
        assertTrue(sel.contains("m.role as ownerRole"), "要吐发布者身份,地图靠它区分商家/玩家");
        assertTrue(sel.contains("t.product_type as productType"), "要吐玩法(1 定向 / 2 探索)");
    }

    @Test
    void mapper接口方法齐全() throws Exception {
        for (String m : new String[]{"insert", "selectById", "selectStatusById", "selectByIdForUpdate", "selectNearby", "countNearby",
                "selectNearbyActivities", "countActiveByMember", "selectMemberBriefs", "countActiveMembers",
                "updateStatus", "updateConversationId", "selectExpired"}) {
            boolean found = false;
            for (java.lang.reflect.Method mm : RoamHangoutMapper.class.getMethods()) if (mm.getName().equals(m)) found = true;
            assertTrue(found, "缺 mapper 方法 " + m);
        }
        assertNotNull(RoamHangoutMapper.class.getMethod("selectExpired", java.util.Date.class));
    }
}
