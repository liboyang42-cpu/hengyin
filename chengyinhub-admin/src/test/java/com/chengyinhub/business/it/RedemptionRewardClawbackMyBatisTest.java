package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.CmsTopicCompletionCouponRewardMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.StoreVerifyGrowthDeliveryMapper;
import com.chengyinhub.business.mapper.TopicCompletionXpAwardMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.impl.RedemptionRewardClawbackService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第二轮拍板第 4 条:撤销核销勾选「同时冲回积分和经验」在真库、真表结构上的行为。
 *
 * <p>表结构取自仓库真源(base_schema + 迁移),不手写 —— 冲回写的几条 SQL 引错一个列名只有真库能报。
 * 钉住四件事:扣到 0 记差额 / 同一笔发放只冲一次 / 冲回后再核销能重新拿奖励且再冲回只冲新那笔 / 券已用不追回。
 */
class RedemptionRewardClawbackMyBatisTest {

    private static final long MEMBER = 501L;
    private static final long REGISTRATION = 9001L;
    private static final long ACTIVITY = 8001L;
    private static final long TOPIC = 700L;

    @Test
    void clawsOnceFloorsAtZeroAndLetsReVerificationEarnAgain() throws Exception {
        try (SqlSession session = openSession("redemption_clawback")) {
            Connection c = session.getConnection();
            createTables(c);
            exec(c, "INSERT INTO ums_member (id, point, del_flag) VALUES (" + MEMBER + ", 7, 0)",
                    "INSERT INTO member_growth (member_id, level_no, exp_value) VALUES (" + MEMBER + ", 1, 50)",
                    "INSERT INTO ums_member_points_detail (member_id, change_points, event_type, event_id, change_type, del_flag)"
                            + " VALUES (" + MEMBER + ", 10, 11, " + REGISTRATION + ", 1, 0),"
                            + " (" + MEMBER + ", 5, 13, " + ACTIVITY + ", 1, 0)",
                    "INSERT INTO growth_mission_progress (member_id, mission_code, biz_type, biz_id, progress_date, reward_exp)"
                            + " VALUES (" + MEMBER + ", 'STORE_VERIFY_1', 'registration', " + REGISTRATION + ", '2026-09-10', 20)",
                    "INSERT INTO store_verify_growth_delivery (registration_id, member_id, missions_json, create_time, next_attempt_at, processed_at)"
                            + " VALUES (" + REGISTRATION + ", " + MEMBER + ", '[]', NOW(), NOW(), NOW())",
                    "INSERT INTO topic_completion_xp_award (member_id, topic_id, xp_amount, granted_at) VALUES (" + MEMBER + ", " + TOPIC + ", 15, NOW())",
                    "INSERT INTO cms_topic_completion_coupon_reward_log (topic_id, member_id, coupon_id, history_id, created_at)"
                            + " VALUES (" + TOPIC + ", " + MEMBER + ", 30, 41, NOW())",
                    "INSERT INTO sms_coupon_history (id, coupon_id, member_id, coupon_code, use_status, get_time, del_flag)"
                            + " VALUES (40, 30, " + MEMBER + ", 'C40-node', 0, NOW(), 0),"
                            + " (41, 30, " + MEMBER + ", 'C41', 0, NOW(), 0),"
                            + " (42, 30, " + MEMBER + ", 'C42-nulltime', 0, NULL, 0)");
            RedemptionRewardClawbackService service = service(session);

            service.clawback(redemption(61L), registration(), true, true, "店员扫错票", 1L);

            assertEquals(0, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER), "积分不够扣到 0");
            assertEquals(0, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE member_id=" + MEMBER
                    + " AND event_type=11 AND event_id=" + REGISTRATION), "原发放行摘出判重键,award() 判重查不到它");
            assertEquals(7, number(c, "SELECT change_points FROM ums_member_points_detail WHERE change_type=2 AND event_type=11"));
            assertEquals(0, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=2 AND event_type=13"),
                    "余额已是 0:不写 0 分流水,差额只进审计");
            assertEquals(15, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER), "50-20-15");
            assertEquals(0, number(c, "SELECT COUNT(*) FROM growth_mission_progress WHERE biz_type='registration' AND biz_id=" + REGISTRATION));
            assertEquals(-20, number(c, "SELECT reward_exp FROM growth_mission_progress WHERE biz_type='registration_clawback'"));
            assertEquals(0, number(c, "SELECT COUNT(*) FROM store_verify_growth_delivery"), "到店成长待办删掉,再核销才能重新入队");
            assertEquals(0, number(c, "SELECT COUNT(*) FROM topic_completion_xp_award"));
            assertEquals(2, number(c, "SELECT use_status FROM sms_coupon_history WHERE id=41"), "未使用的通关券作废");
            assertEquals(0, number(c, "SELECT use_status FROM sms_coupon_history WHERE id=40"),
                    "同模板、同一分钟先发的节点券不是通关券,不许误作废");
            assertEquals(0, number(c, "SELECT use_status FROM sms_coupon_history WHERE id=42"), "非持久原回执,不认");
            String audit = text(c, "SELECT after_json FROM business_audit_event WHERE action='REDEMPTION_REWARD_CLAWBACK'");
            assertTrue(audit.contains("\"shortfall\": 3") || audit.contains("\"shortfall\":3"), audit);
            assertTrue(audit.contains("VOIDED"), audit);

            // 同一票另一章再撤(同样勾选):已冲过的发放一笔都不再动
            service.clawback(redemption(62L), registration(), true, true, "再撤一章", 1L);
            assertEquals(0, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER));
            assertEquals(15, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER));
            assertEquals(1, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=2"));
            assertEquals(1, number(c, "SELECT COUNT(*) FROM growth_mission_progress WHERE biz_type='registration_clawback'"));

            // 玩家真到店再核销:按发放侧同样的判重键重新发得出来(唯一键不撞)
            exec(c, "INSERT INTO ums_member_points_detail (member_id, change_points, event_type, event_id, change_type, del_flag)"
                            + " VALUES (" + MEMBER + ", 10, 11, " + REGISTRATION + ", 1, 0)",
                    "UPDATE ums_member SET point = 10 WHERE id=" + MEMBER,
                    "INSERT INTO growth_mission_progress (member_id, mission_code, biz_type, biz_id, progress_date, reward_exp)"
                            + " VALUES (" + MEMBER + ", 'STORE_VERIFY_1', 'registration', " + REGISTRATION + ", '2026-09-10', 20)",
                    "UPDATE member_growth SET exp_value = 35 WHERE member_id=" + MEMBER);
            service.clawback(redemption(63L), registration(), true, false, "又扫错", 1L);
            assertEquals(0, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER), "只冲新发的那 10 分");
            assertEquals(15, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER), "只冲新发的那 20 经验");
            assertEquals(3, number(c, "SELECT COUNT(*) FROM business_audit_event WHERE action='REDEMPTION_REWARD_CLAWBACK'"));
        }
    }

    /** PR10 复审:整单核销撤销复用同一套到店积分/经验冲回,只冲一次,不碰通关奖励。 */
    @Test
    void wholeOrderReversalClawsStoreVerifyRewardsOnceAndKeepsCompletion() throws Exception {
        try (SqlSession session = openSession("redemption_clawback_whole_order")) {
            Connection c = session.getConnection();
            createTables(c);
            exec(c, "INSERT INTO ums_member (id, point, del_flag) VALUES (" + MEMBER + ", 30, 0)",
                    "INSERT INTO member_growth (member_id, level_no, exp_value) VALUES (" + MEMBER + ", 1, 50)",
                    "INSERT INTO ums_member_points_detail (member_id, change_points, event_type, event_id, change_type, del_flag)"
                            + " VALUES (" + MEMBER + ", 10, 11, " + REGISTRATION + ", 1, 0),"
                            + " (" + MEMBER + ", 5, 13, " + ACTIVITY + ", 1, 0)",
                    "INSERT INTO growth_mission_progress (member_id, mission_code, biz_type, biz_id, progress_date, reward_exp)"
                            + " VALUES (" + MEMBER + ", 'STORE_VERIFY_1', 'registration', " + REGISTRATION + ", '2026-09-10', 20)",
                    "INSERT INTO store_verify_growth_delivery (registration_id, member_id, missions_json, create_time, next_attempt_at, processed_at)"
                            + " VALUES (" + REGISTRATION + ", " + MEMBER + ", '[]', NOW(), NOW(), NOW())",
                    "INSERT INTO topic_completion_xp_award (member_id, topic_id, xp_amount, granted_at) VALUES (" + MEMBER + ", " + TOPIC + ", 15, NOW())");
            RedemptionRewardClawbackService service = service(session);

            service.clawbackWholeOrderVerification(registration(), 6006L, "店员扫错票", 1L,
                    "registration-verify-clawback-" + REGISTRATION + "-1");

            assertEquals(20, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER), "只冲到店 10 分");
            assertEquals(30, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER), "只冲到店 20 经验");
            assertEquals(0, number(c, "SELECT COUNT(*) FROM store_verify_growth_delivery"));
            assertEquals(1, number(c, "SELECT COUNT(*) FROM topic_completion_xp_award"), "通关奖励不在整单撤销范围");
            assertEquals(1, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=1 AND event_type=13"));
            assertEquals(1, number(c, "SELECT COUNT(*) FROM business_audit_event WHERE action='REGISTRATION_VERIFY_REWARD_CLAWBACK'"
                    + " AND target_type='CMS_REGISTRATION' AND target_id=" + REGISTRATION));

            // 再撤一次(下一代幂等键):原发放已摘出判重键,一分一点都不再扣
            service.clawbackWholeOrderVerification(registration(), 6006L, "重复撤销", 1L,
                    "registration-verify-clawback-" + REGISTRATION + "-2");
            assertEquals(20, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER));
            assertEquals(30, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER));
            assertEquals(1, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=2"));
        }
    }

    @Test
    void usedCompletionCouponIsOnlyAuditedNotRecovered() throws Exception {
        try (SqlSession session = openSession("redemption_clawback_used")) {
            Connection c = session.getConnection();
            createTables(c);
            exec(c, "INSERT INTO ums_member (id, point, del_flag) VALUES (" + MEMBER + ", 0, 0)",
                    "INSERT INTO cms_topic_completion_coupon_reward_log (topic_id, member_id, coupon_id, history_id, created_at)"
                            + " VALUES (" + TOPIC + ", " + MEMBER + ", 30, 41, NOW())",
                    "INSERT INTO sms_coupon_history (id, coupon_id, member_id, coupon_code, use_status, get_time, del_flag)"
                            + " VALUES (41, 30, " + MEMBER + ", 'C41', 1, NOW(), 0)");

            service(session).clawback(redemption(61L), registration(), false, true, "店员扫错票", 1L);

            assertEquals(1, number(c, "SELECT use_status FROM sms_coupon_history WHERE id=41"), "已使用的券不追回");
            assertTrue(text(c, "SELECT after_json FROM business_audit_event").contains("USED_NOT_RECOVERED"));
            assertEquals(0, number(c, "SELECT COUNT(*) FROM growth_mission_progress WHERE biz_type='topic_completion_clawback'"),
                    "积分/XP 从没发过、券已使用没作废 ⇒ 一项都没真冲回,不写冲回标记(否则再通关会倒补)");
        }
    }

    /**
     * 总控复查补做(拍板4):通关 → 撤销冲回 → 再通关 = 通关积分/XP/通关券只剩一份;勋章不重复;重复冲回不重复扣。
     * 通关奖励服务与冲回服务都是真实现、走真表;只把与本链路无关的依赖(主题读取/成长徽章/官方活动/预算)桩掉。
     */
    @Test
    void recompletionAfterClawbackEarnsCompletionRewardsExactlyOnceMore() throws Exception {
        try (SqlSession session = openSession("redemption_clawback_recomplete")) {
            Connection c = session.getConnection();
            createTables(c);
            exec(c, "INSERT INTO ums_member (id, point, del_flag) VALUES (" + MEMBER + ", 0, 0)",
                    "INSERT INTO member_growth (member_id, level_no, exp_value) VALUES (" + MEMBER + ", 1, 0)");
            com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl awards = completionAwards(session, c);
            RedemptionRewardClawbackService clawback = service(session);

            awards.grantOnce(MEMBER, TOPIC, ACTIVITY, new java.util.Date());
            assertCompletionRewards(c, 5, 15, 1, 1);
            assertEquals(15, number(c, "SELECT SUM(exp_value-revoked_exp) FROM member_exp_earned WHERE member_id=" + MEMBER));

            clawback.clawback(redemption(61L), registration(), false, true, "店员扫错票", 1L);
            assertCompletionRewards(c, 0, 0, 0, 0);
            assertEquals(0, number(c, "SELECT SUM(exp_value-revoked_exp) FROM member_exp_earned WHERE member_id=" + MEMBER));
            assertEquals(0, number(c, "SELECT COUNT(*) FROM cms_topic_completion_coupon_reward_log"), "作废的通关券发放日志删掉");

            awards.grantOnce(MEMBER, TOPIC, ACTIVITY, new java.util.Date());   // 玩家重新通关(勋章已在)
            assertCompletionRewards(c, 5, 15, 1, 1);
            awards.grantOnce(MEMBER, TOPIC, ACTIVITY, new java.util.Date());   // 再触发一次通关判定
            assertCompletionRewards(c, 5, 15, 1, 1);
            awards.grantOnce(MEMBER, TOPIC, ACTIVITY + 1, new java.util.Date());   // 换一场再通关:冲回标记已消费,不许再领
            assertEquals(5, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER), "换场次不再发通关积分");
            assertEquals(0, number(c, "SELECT COUNT(*) FROM growth_mission_progress WHERE biz_type='topic_completion_clawback'"),
                    "补发后冲回标记已全部消费");
            assertEquals(2, number(c, "SELECT COUNT(*) FROM sms_coupon_history WHERE member_id=" + MEMBER), "只多发了一张");

            clawback.clawback(redemption(62L), registration(), false, true, "又扫错", 1L);
            assertCompletionRewards(c, 0, 0, 0, 0);
            clawback.clawback(redemption(63L), registration(), false, true, "重复撤销", 1L);
            assertCompletionRewards(c, 0, 0, 0, 0);
            assertEquals(2, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=2"), "两次真冲回,重复那次不再扣");
            assertEquals(2, number(c, "SELECT COUNT(*) FROM sms_coupon_history WHERE use_status=2"));
            assertEquals(30, number(c, "SELECT SUM(exp_value) FROM member_exp_earned"), "保留两笔真实发放历史");
            assertEquals(30, number(c, "SELECT SUM(revoked_exp) FROM member_exp_earned"), "两笔分别扣回，重复撤销不再计入");
        }
    }

    /** 积分余额、经验、生效中的通关积分发放行数、未使用的券数。 */
    private static void assertCompletionRewards(Connection c, int point, int exp, int activeGrants, int unusedCoupons) throws Exception {
        assertEquals(point, number(c, "SELECT point FROM ums_member WHERE id=" + MEMBER), "积分余额");
        assertEquals(exp, number(c, "SELECT exp_value FROM member_growth WHERE member_id=" + MEMBER), "经验");
        assertEquals(activeGrants, number(c, "SELECT COUNT(*) FROM ums_member_points_detail WHERE change_type=1 AND event_type=13 AND event_id=" + ACTIVITY), "生效的通关积分发放");
        assertEquals(unusedCoupons, number(c, "SELECT COUNT(*) FROM sms_coupon_history WHERE use_status=0 AND member_id=" + MEMBER), "未使用的通关券");
    }

    private com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl completionAwards(SqlSession session, Connection c) throws Exception {
        UmsMemberPointsDetailMapper details = session.getMapper(UmsMemberPointsDetailMapper.class);
        UmsMemberMapper members = session.getMapper(UmsMemberMapper.class);

        com.chengyinhub.business.domain.CmsPointsSetting rule = new com.chengyinhub.business.domain.CmsPointsSetting();
        rule.setPointsNum(5);
        com.chengyinhub.business.service.ICmsPointsSettingService settings = mock(com.chengyinhub.business.service.ICmsPointsSettingService.class);
        when(settings.selectCmsPointsSettingList(any())).thenReturn(java.util.Collections.singletonList(rule));
        com.chengyinhub.business.service.IUmsMemberService memberService = mock(com.chengyinhub.business.service.IUmsMemberService.class);
        when(memberService.addPoints(anyLong(), anyInt())).thenAnswer(i -> members.addPoints(i.getArgument(0), i.getArgument(1)) == 1);
        com.chengyinhub.business.service.IUmsMemberPointsDetailService detailService = mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class);
        when(detailService.selectUmsMemberPointsDetailList(any())).thenAnswer(i -> details.selectUmsMemberPointsDetailList(i.getArgument(0)));
        when(detailService.insertUmsMemberPointsDetail(any())).thenAnswer(i -> details.insertUmsMemberPointsDetail(i.getArgument(0)));
        com.chengyinhub.business.service.impl.PointsAwardServiceImpl points = new com.chengyinhub.business.service.impl.PointsAwardServiceImpl();
        ReflectionTestUtils.setField(points, "cmsPointsSettingService", settings);
        ReflectionTestUtils.setField(points, "umsMemberService", memberService);
        ReflectionTestUtils.setField(points, "umsMemberMapper", members);
        ReflectionTestUtils.setField(points, "umsMemberPointsDetailService", detailService);
        ReflectionTestUtils.setField(points, "umsMemberPointsDetailMapper", details);

        com.chengyinhub.business.service.ISmsCouponHistoryService couponHistory = mock(com.chengyinhub.business.service.ISmsCouponHistoryService.class);
        java.util.concurrent.atomic.AtomicLong nextHistoryId = new java.util.concurrent.atomic.AtomicLong(100);
        when(couponHistory.grantCouponWithReceipt(anyLong(), any())).thenAnswer(i -> {
            long id = nextHistoryId.incrementAndGet();
            exec(c, "INSERT INTO sms_coupon_history (id, coupon_id, member_id, coupon_code, use_status, get_time, del_flag)"
                    + " VALUES (" + id + ", " + ((com.chengyinhub.business.domain.SmsCoupon)i.getArgument(1)).getId() + ", " + i.getArgument(0) + ", 'C" + id + "', 0, NOW(), 0)");
            return id;
        });
        com.chengyinhub.business.service.impl.TopicCompletionCouponRewardServiceImpl coupons =
                new com.chengyinhub.business.service.impl.TopicCompletionCouponRewardServiceImpl();
        ReflectionTestUtils.setField(coupons, "rewardMapper", session.getMapper(CmsTopicCompletionCouponRewardMapper.class));
        com.chengyinhub.business.mapper.SmsCouponMapper couponTemplates = mock(com.chengyinhub.business.mapper.SmsCouponMapper.class);
        com.chengyinhub.business.domain.SmsCoupon template = new com.chengyinhub.business.domain.SmsCoupon(); template.setId(30L);
        when(couponTemplates.selectSmsCouponById(30L)).thenReturn(template);
        ReflectionTestUtils.setField(coupons, "smsCouponMapper", couponTemplates);
        ReflectionTestUtils.setField(coupons, "smsCouponHistoryService", couponHistory);

        com.chengyinhub.business.domain.CmsTopic topic = new com.chengyinhub.business.domain.CmsTopic();
        topic.setId(TOPIC);
        topic.setName("探店日");
        topic.setCompleteRewardCouponId(30L);
        com.chengyinhub.business.service.ICmsTopicService topics = mock(com.chengyinhub.business.service.ICmsTopicService.class);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        com.chengyinhub.business.mapper.PlayerBadgeMapper badges = mock(com.chengyinhub.business.mapper.PlayerBadgeMapper.class);
        when(badges.insertPlayerBadge(any())).thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("勋章已在"));
        when(badges.selectOne(anyLong(), anyString(), anyLong())).thenReturn(new com.chengyinhub.business.domain.PlayerBadge());
        com.chengyinhub.business.service.IPlayerProgressionService progression = mock(com.chengyinhub.business.service.IPlayerProgressionService.class);
        when(progression.onTopicComplete(anyLong(), anyLong())).thenReturn(java.util.Collections.emptyList());
        com.chengyinhub.business.service.IXpBudgetService budget = mock(com.chengyinhub.business.service.IXpBudgetService.class);
        when(budget.computeBudget(TOPIC)).thenReturn(java.util.Collections.<String, Object>singletonMap("remain", 15));
        com.chengyinhub.business.service.impl.GrowthServiceImpl growth =
                new com.chengyinhub.business.service.impl.GrowthServiceImpl();
        ReflectionTestUtils.setField(growth, "memberGrowthMapper", session.getMapper(MemberGrowthMapper.class));

        com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl awards =
                new com.chengyinhub.business.service.impl.TopicCompletionAwardServiceImpl();
        ReflectionTestUtils.setField(awards, "topicService", topics);
        ReflectionTestUtils.setField(awards, "playerBadgeMapper", badges);
        ReflectionTestUtils.setField(awards, "pointsAwardService", points);
        ReflectionTestUtils.setField(awards, "playerProgressionService", progression);
        ReflectionTestUtils.setField(awards, "couponRewardService", coupons);
        ReflectionTestUtils.setField(awards, "officialEventV2Service", mock(com.chengyinhub.business.service.IOfficialEventV2Service.class));
        ReflectionTestUtils.setField(awards, "xpBudgetService", budget);
        ReflectionTestUtils.setField(awards, "growthService", growth);
        ReflectionTestUtils.setField(awards, "xpAwardMapper", session.getMapper(TopicCompletionXpAwardMapper.class));
        ReflectionTestUtils.setField(awards, "growthMissionProgressMapper", session.getMapper(GrowthMissionProgressMapper.class));
        com.chengyinhub.business.service.IUmsMemberService locker = mock(com.chengyinhub.business.service.IUmsMemberService.class);
        when(locker.lockMemberById(anyLong())).thenAnswer(i -> members.lockMemberById(i.getArgument(0)));
        ReflectionTestUtils.setField(awards, "umsMemberService", locker);
        return awards;
    }

    private static Redemption redemption(long id) {
        Redemption r = new Redemption();
        r.setId(id);
        r.setRegistrationId(REGISTRATION);
        r.setTopicId(TOPIC);
        r.setMerchantId(6006L);
        r.setChapterId(3L);
        return r;
    }

    private static CmsRegistration registration() {
        CmsRegistration reg = new CmsRegistration();
        reg.setId(REGISTRATION);
        reg.setMemberId(MEMBER);
        reg.setOwnerId(ACTIVITY);
        return reg;
    }

    private RedemptionRewardClawbackService service(SqlSession session) {
        RedemptionRewardClawbackService service = new RedemptionRewardClawbackService();
        ReflectionTestUtils.setField(service, "pointsDetailMapper", session.getMapper(UmsMemberPointsDetailMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberMapper", session.getMapper(UmsMemberMapper.class));
        ReflectionTestUtils.setField(service, "growthMissionProgressMapper", session.getMapper(GrowthMissionProgressMapper.class));
        ReflectionTestUtils.setField(service, "memberGrowthMapper", session.getMapper(MemberGrowthMapper.class));
        ReflectionTestUtils.setField(service, "storeVerifyGrowthDeliveryMapper", session.getMapper(StoreVerifyGrowthDeliveryMapper.class));
        ReflectionTestUtils.setField(service, "topicCompletionXpAwardMapper", session.getMapper(TopicCompletionXpAwardMapper.class));
        ReflectionTestUtils.setField(service, "completionCouponRewardMapper", session.getMapper(CmsTopicCompletionCouponRewardMapper.class));
        ReflectionTestUtils.setField(service, "smsCouponHistoryMapper", session.getMapper(SmsCouponHistoryMapper.class));
        ReflectionTestUtils.setField(service, "businessAuditEventMapper", session.getMapper(BusinessAuditEventMapper.class));
        return service;
    }

    private SqlSession openSession(String db) throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("it", new JdbcTransactionFactory(), new PooledDataSource(
                "com.mysql.cj.jdbc.Driver", MybatisItDatabase.url(db), MybatisItDatabase.user(), MybatisItDatabase.password())));
        cfg.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        for (Class<?> mapper : new Class<?>[] {UmsMemberPointsDetailMapper.class, UmsMemberMapper.class,
                GrowthMissionProgressMapper.class, MemberGrowthMapper.class, StoreVerifyGrowthDeliveryMapper.class,
                TopicCompletionXpAwardMapper.class, CmsTopicCompletionCouponRewardMapper.class,
                SmsCouponHistoryMapper.class, BusinessAuditEventMapper.class}) {
            String resource = "mapper/business/" + mapper.getSimpleName() + ".xml";
            cfg.addMapper(mapper);
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, cfg, resource, cfg.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(cfg).openSession(true);
    }

    private static void createTables(Connection c) throws Exception {
        String base = read("sql/base_schema_20260818.sql");
        for (String table : new String[] {"ums_member", "ums_member_points_detail", "growth_mission_progress",
                "member_growth", "sms_coupon_history", "cms_topic_completion_coupon_reward_log"}) {
            exec(c, ddl(base, table));
        }
        exec(c, "ALTER TABLE cms_topic_completion_coupon_reward_log ADD COLUMN history_id BIGINT NULL");
        // base_schema 快照晚于 migration_app_open_id.sql 之前导出;UmsMemberMapper 的通用查询要这一列
        exec(c, "ALTER TABLE ums_member ADD COLUMN app_open_id varchar(64) NULL AFTER wx_open_id");
        exec(c, ddl(read("chengyinhub-admin/sql/migration_store_verify_growth_delivery_20260913.sql"), "store_verify_growth_delivery"),
                ddl(read("chengyinhub-admin/sql/migration_20260817_tandianri_r6_product_contracts.sql"), "topic_completion_xp_award"),
                ddl(read("chengyinhub-admin/sql/migration_business_audit_event_20260823.sql"), "business_audit_event"),
                ddl(read("chengyinhub-admin/sql/migration_member_exp_earned_20261011.sql"), "member_exp_earned"),
                "ALTER TABLE member_exp_earned ADD COLUMN revoked_exp INT NOT NULL DEFAULT 0",
                "ALTER TABLE topic_completion_xp_award ADD COLUMN earned_exp_id BIGINT NULL",
                "ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER owner_member_id,"
                        + " MODIFY COLUMN request_id VARCHAR(64) NOT NULL");
    }

    private static String ddl(String sql, String table) {
        Matcher m = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?`?" + table + "`? \\(.*?\\) ENGINE=[^;]*;",
                Pattern.DOTALL).matcher(sql);
        if (!m.find()) throw new IllegalStateException("仓库里找不到 " + table + " 的建表语句");
        return m.group().replaceAll(";$", "");
    }

    private static String read(String relative) throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("sql/base_schema_20260818.sql"))) dir = dir.getParent();
        if (dir == null) throw new IllegalStateException("找不到仓库根");
        return new String(Files.readAllBytes(dir.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static void exec(Connection c, String... sql) throws Exception {
        try (Statement st = c.createStatement()) {
            for (String s : sql) st.execute(s);
        }
    }

    private static int number(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("无结果: " + sql);
            return rs.getInt(1);
        }
    }

    private static String text(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) throw new IllegalStateException("无结果: " + sql);
            return rs.getString(1);
        }
    }
}
