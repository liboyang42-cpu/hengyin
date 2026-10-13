package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL 真值契约：状态枚举或父俱乐部过滤漂移时必须直接红。 */
class ClubLifecycleMapperContractTest {

    private String migrationSql() throws Exception {
        Path path = Paths.get("sql", "migration_club_join_policy_20260809.sql");
        if (!Files.exists(path)) {
            path = Paths.get("chengyinhub-admin", "sql", "migration_club_join_policy_20260809.sql");
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).toLowerCase();
    }

    private Configuration configuration(String resource, String alias, Class<?> type) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias(alias, type);
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String sql(Configuration configuration, String statement, Object parameter) {
        return configuration.getMappedStatement(statement).getBoundSql(parameter)
                .getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }

    @Test
    void dissolutionMoneyAndFulfillmentQueriesPinRealStatuses() throws Exception {
        Configuration cfg = configuration("mapper/business/ClubMapper.xml", "Club", Club.class);
        String ns = "com.chengyinhub.business.mapper.ClubMapper.";
        Object clubId = Collections.singletonMap("clubId", 73L);

        String revenue = sql(cfg, ns + "countUnsettledRevenueByClub", clubId);
        assertTrue(revenue.contains("is_settlement, 0) = 0"), revenue);
        assertTrue(revenue.contains("t.club_id = ?") && revenue.contains("t.del_flag = 0")
                        && revenue.contains("t.status = 1"),
                "未结算主题只统计本团在营主题: " + revenue);
        assertTrue(!revenue.contains("cms_activity") && !revenue.contains("end_date")
                        && !revenue.contains("cms_registration"),
                "重复开场不下钻活动或报名事实，且不能追加时间条件: " + revenue);

        String payable = sql(cfg, ns + "selectUnpaidSettlementsByClub", clubId);
        assertTrue(payable.contains("s.status = 0"), payable);
        assertTrue(payable.contains("s.del_flag = 0"), payable);
        assertTrue(payable.contains("s.topic_id") && payable.contains("t.club_id = ?"), payable);
        assertTrue(payable.contains("s.line_type = 'lead_fee'")
                && payable.contains("o.to_type = 'club'") && payable.contains("o.to_id = ?"), payable);
        assertTrue(payable.contains("when s.line_type = 'lead_fee'")
                        && payable.contains("s.payee_type = 'initiator'")
                        && payable.contains("s.payee_type in ('platform', 'club', 'merchant')"),
                "待收/待付方向必须相对当前 club 判定，不能只看 payee_type: " + payable);
        assertTrue(payable.contains("s.amount is null or s.amount > 0"),
                "NULL 金额必须 fail-closed，0/负数不阻塞: " + payable);
        assertTrue(!payable.contains("o.status =") && !payable.contains("o.del_flag ="),
                "父合作单软删/归档不能吞掉未打款事实: " + payable);
        assertTrue(!payable.contains("t.del_flag ="),
                "父主题软删不能吞掉未打款事实: " + payable);

        String cooperation = sql(cfg, ns + "countActiveCooperationsByClub", clubId);
        assertTrue(cooperation.contains("o.status = 0") && cooperation.contains("o.del_flag = 0"), cooperation);
        assertTrue(cooperation.contains("o.to_type = 'club'") && cooperation.contains("t.club_id = ?"),
                "承接方与本团主题发出的合作都必须阻塞: " + cooperation);
        assertTrue(cooperation.contains("ifnull(parent.is_settlement, 0) = 0"), cooperation);
        assertTrue(cooperation.contains("o.topic_id is null") && cooperation.contains("not exists"),
                "父主题缺失必须 fail-closed: " + cooperation);
        assertTrue(!cooperation.contains("o.status = 2"),
                "status=2 从未通电，不能当作结算真值: " + cooperation);

        String deposits = sql(cfg, ns + "selectPendingDepositsByClub", clubId);
        assertTrue(deposits.contains("deposit_status in (1, 2, 5)"), deposits);
        assertTrue(deposits.contains("i.to_type = 'club'") && deposits.contains("i.to_id = ?"), deposits);
        assertTrue(deposits.contains("t.club_id = ?"),
                "本团主题上的在途保证金也必须保留组织方处理入口: " + deposits);
        assertTrue(!deposits.contains("where i.del_flag") && !deposits.contains("and i.del_flag =")
                        && !deposits.contains("t.del_flag"),
                "软删不能作为 WHERE 条件抹掉未了结保证金事实: " + deposits);
        // d7593ff37 起无渠道确认的历史 4(type0/1)也可走 requestDepositRefund 重试,故 retryable 拆成两支
        //   两支必须整体挂在 del_flag = 0 之下,且 status=2 只在已取消/已结算时可重试 —— 按顺序整段钉住,拆散即红
        assertTrue(deposits.contains("when i.del_flag = 0 and ( (i.deposit_status = 4 and i.invite_type in (0,1))"
                        + " or (i.deposit_status = 2 and ( i.status = 3 or exists (")
                        && deposits.contains("refund_topic.is_settlement = 1"),
                "退款按钮只能标记现有重试接口真实可读且已取消/已结算的邀约: " + deposits);

        String sessions = sql(cfg, ns + "countOngoingSessionsByClub", clubId);
        assertTrue(sessions.contains("a.cancel_time is null"), sessions);
        assertTrue(sessions.contains("a.end_date is null or a.end_date > now()"), sessions);
        assertTrue(!sessions.contains("t.del_flag = 0"),
                "父主题软删不能抹掉仍进行中的子场次: " + sessions);

        String registrations = sql(cfg, ns + "countIncompleteRegistrationsByClub", clubId);
        assertTrue(registrations.contains("and case"), registrations);
        assertTrue(registrations.contains("r.registration_status = 3 and r.payment_status in (3, 4)"), registrations);
        assertTrue(registrations.contains("r.registration_status = 4 and r.payment_status = 3"), registrations);
        assertTrue(registrations.contains("r.registration_status = 2 and r.payment_status = 2"), registrations);
        assertTrue(registrations.contains("ifnull(r.verification_status, 0) = 1"), registrations);
        assertTrue(registrations.contains("r.purchase_kind is not null and r.purchase_kind != 3"), registrations);
        assertTrue(registrations.contains("entitlement.registration_id = r.id and entitlement.status = 0"),
                "探索票只核销一章仍有未消费权益，必须继续阻塞: " + registrations);
        assertTrue(!registrations.contains("t.del_flag = 0")
                        && !registrations.contains("a.del_flag = 0")
                        && !registrations.contains("t2.del_flag = 0"),
                "软删内容不能抹掉未完成报名事实: " + registrations);
        assertTrue(!registrations.contains("registration_status = 1 and r.payment_status = 2"),
                "半态不能出现在安全终态白名单: " + registrations);

        String dissolve = sql(cfg, ns + "dissolveClubById", clubId);
        assertTrue(dissolve.contains("set del_flag = 2"),
                "俱乐部软删必须遵循 0存在/2删除 的全库约定: " + dissolve);
    }

    @Test
    void migrationRollbackPhysicallyRemovesNonMembersBeforeOldCodeReturns() throws Exception {
        String sql = migrationSql();
        String delete = "delete from club_member where status in (0, 2)";
        String readback = "from club_member where status <> 1 group by status";
        String alter = "alter table club_member";
        assertTrue(sql.contains(delete), "旧 Mapper 不看 del_flag，回滚必须物理删除待审/拒绝行");
        assertTrue(!sql.contains("update club_member set del_flag = 1 where status in (0, 2)"),
                "软删无法阻止旧 Mapper 把待审/拒绝者当成员");
        assertTrue(sql.indexOf(delete) < sql.indexOf(readback)
                        && sql.indexOf(readback) < sql.lastIndexOf(alter),
                "回滚必须先删除、回读只剩 status=1，再改列并回滚旧 jar");
    }

    @Test
    void pendingMembershipCannotPassAnyActiveMembershipRead() throws Exception {
        Configuration cfg = configuration("mapper/business/ClubMemberMapper.xml", "ClubMember", ClubMember.class);
        String ns = "com.chengyinhub.business.mapper.ClubMemberMapper.";
        Object ids = new java.util.HashMap<String, Object>();
        ((java.util.Map<String, Object>) ids).put("clubId", 73L);
        ((java.util.Map<String, Object>) ids).put("memberId", 42L);

        String active = sql(cfg, ns + "selectByClubAndMember", ids);
        assertTrue(active.contains("cm.status = 1 and cm.del_flag = 0"), active);
        assertTrue(active.contains("join club c on c.id = cm.club_id and c.del_flag = 0"), active);

        String joinedIds = sql(cfg, ns + "selectClubIdsByMember", Collections.singletonMap("memberId", 42L));
        assertTrue(joinedIds.contains("cm.status = 1 and cm.del_flag = 0"), joinedIds);

        String count = sql(cfg, ns + "countByClub", Collections.singletonMap("clubId", 73L));
        assertTrue(count.contains("status = 1 and del_flag = 0"), count);

        ClubMember listProbe = new ClubMember();
        listProbe.setClubId(73L);
        listProbe.setStatus(1);
        String memberList = sql(cfg, ns + "selectClubMemberList", listProbe);
        assertTrue(memberList.contains("join club c on c.id = cm.club_id and c.del_flag = 0"), memberList);
    }

    @Test
    void dissolvedClubContentIsAbsentFromPublicTopicAndActivityQueries() throws Exception {
        Configuration topicCfg = configuration("mapper/business/CmsTopicMapper.xml", "CmsTopic", CmsTopic.class);
        String topicSql = sql(topicCfg,
                "com.chengyinhub.business.mapper.CmsTopicMapper.selectPublicCmsTopicList", new CmsTopic());
        assertTrue(topicSql.contains("active_club.id = cms_topic.club_id and active_club.del_flag = 0"), topicSql);
        java.util.Map<String, Object> detailParams = new java.util.HashMap<String, Object>();
        detailParams.put("id", 7L);
        detailParams.put("viewerMemberId", 43L);
        String topicDetailSql = sql(topicCfg,
                "com.chengyinhub.business.mapper.CmsTopicMapper.selectCmsTopicDetailById", detailParams);
        assertTrue(topicDetailSql.contains("active_club.id = cms_topic.club_id and active_club.del_flag = 0"),
                topicDetailSql);
        String merchantTopicSql = sql(topicCfg,
                "com.chengyinhub.business.mapper.CmsTopicMapper.selectMerchantVisibleCmsTopicById", 7L);
        assertTrue(merchantTopicSql.contains("active_club.id = cms_topic.club_id and active_club.del_flag = 0"),
                merchantTopicSql);

        Configuration activityCfg = configuration("mapper/business/CmsActivityMapper.xml", "CmsActivity", CmsActivity.class);
        String activitySql = sql(activityCfg,
                "com.chengyinhub.business.mapper.CmsActivityMapper.selectPublicCmsActivityList", new CmsActivity());
        assertTrue(activitySql.contains("active_club.id = cms_activity.club_id and active_club.del_flag = 0"), activitySql);
        assertTrue(activitySql.contains("parent_topic.id = cms_activity.topic_id"), activitySql);
        assertTrue(activitySql.contains("parent_club.id is null"), activitySql);
        String activityDetailSql = sql(activityCfg,
                "com.chengyinhub.business.mapper.CmsActivityMapper.selectCmsActivityDetailById", detailParams);
        assertTrue(activityDetailSql.contains("active_club.id = cms_activity.club_id and active_club.del_flag = 0"),
                activityDetailSql);
        assertTrue(activityDetailSql.contains("parent_topic.id = cms_activity.topic_id")
                && activityDetailSql.contains("parent_club.id is null"), activityDetailSql);
    }

    @Test
    void closedGroupCannotBeResurrectedByUnreadFanout() throws Exception {
        Configuration conversationCfg = configuration(
                "mapper/business/ImConversationMapper.xml", "ImConversation", ImConversation.class);
        String lockSql = sql(conversationCfg,
                "com.chengyinhub.business.mapper.ImConversationMapper.selectImConversationByIdForUpdate", 8L);
        assertTrue(lockSql.endsWith("for update"), lockSql);

        Configuration memberCfg = configuration(
                "mapper/business/ImConversationMemberMapper.xml", "ImConversationMember", ImConversationMember.class);
        java.util.Map<String, Object> params = new java.util.HashMap<String, Object>();
        params.put("conversationId", 8L);
        params.put("memberId", 42L);
        params.put("delta", 1);
        String unreadSql = sql(memberCfg,
                "com.chengyinhub.business.mapper.ImConversationMemberMapper.addUnread", params);
        assertTrue(unreadSql.contains("join im_conversation c") && unreadSql.contains("c.status = 0"), unreadSql);
        String sumSql = sql(memberCfg,
                "com.chengyinhub.business.mapper.ImConversationMemberMapper.sumUnread", params);
        assertTrue(sumSql.contains("join im_conversation c") && sumSql.contains("c.status = 0"), sumSql);
    }
}
