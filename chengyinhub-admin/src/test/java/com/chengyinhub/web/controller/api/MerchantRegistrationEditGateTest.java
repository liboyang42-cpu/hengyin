package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.chengyinhub.business.mapper.CmsRegistrationMerchantMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.impl.MerchantRegistrationEditServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商家改自己那条报名的三道闸。
 *
 * 背景:原来报名只能创建和取消 —— 被驳回的商家想补一张现场图只能取消重报,
 * 记录丢失、重新排队。开这个口子的同时必须钉死三件事:
 *   ① 只能改自己的         ② 主题一开跑就冻结      ③ 已中标不许改(审后再改 = 绕过审核)
 * 外加白名单:承接标的、审核结论、钱,一律不接受客户端改。
 *
 * ⚠️ 负控已实测:逐条注释掉对应闸 ⇒ 对应用例转红(见 §每个用例的 doc)。
 */
class MerchantRegistrationEditGateTest {

    private static final Long ME = 88L;
    private static final Long OTHER = 89L;
    private static final Long REG = 900L;
    private static final Long TOPIC = 300L;

    private ICmsRegistrationMerchantService regSvc;
    private ICmsTopicService topicSvc;
    private MerchantRegistrationEditServiceImpl service;

    @BeforeEach
    void setUp() {
        regSvc = mock(ICmsRegistrationMerchantService.class);
        topicSvc = mock(ICmsTopicService.class);
        service = new MerchantRegistrationEditServiceImpl(regSvc, topicSvc);
        when(regSvc.updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean())).thenReturn(1);
    }

    private void given(Long ownerId, Long status, int topicStartsInDays) {
        given(ownerId, status, status != null && status == 1L ? 1L : 0L, topicStartsInDays);
    }

    private void given(Long ownerId, Long status, Long auditStatus, int topicStartsInDays) {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setId(REG);
        r.setMemberId(ownerId);
        r.setTopicId(TOPIC);
        r.setStatus(status);
        r.setAuditStatus(auditStatus);
        r.setDelFlag(0);
        when(regSvc.selectCmsRegistrationMerchantById(REG)).thenReturn(r);

        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setStartDate(new Date(System.currentTimeMillis() + topicStartsInDays * 86400000L));
        when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(t);
    }

    private RegistrationMerchantDTO dto() {
        RegistrationMerchantDTO d = new RegistrationMerchantDTO();
        d.setId(REG);
        d.setPicUrl("new.jpg");
        d.setAddressName("幸会咖啡(外滩源店)");
        return d;
    }

    private CmsRegistrationMerchant captureSaved() {
        ArgumentCaptor<CmsRegistrationMerchant> cap = ArgumentCaptor.forClass(CmsRegistrationMerchant.class);
        verify(regSvc).updateEditableFieldsIfStateMatches(cap.capture(), any(), any(), any(), anyBoolean());
        return cap.getValue();
    }

    /** 审核中 + 主题未开始 = 可改,这是最常见的正常路径。 */
    @Test
    @Timeout(5)
    void pendingBeforeStart_canEdit() {
        given(ME, 0L, 5);
        assertTrue(service.updateByMerchant(REG, ME, dto(), "u"));
        assertEquals("new.jpg", captureSaved().getPicUrl());
    }

    /** 营业时段就是这张表单的可编辑现场信息，不能在 update 白名单里丢失。 */
    @Test
    @Timeout(5)
    void cooperateDate_isEditableWhitelistedField() {
        given(ME, 0L, 5);
        RegistrationMerchantDTO request = dto();
        request.setCooperateDate("10:00-18:00");

        assertTrue(service.updateByMerchant(REG, ME, request, "u"));

        assertEquals("10:00-18:00", captureSaved().getCooperateDate());
    }

    /** 驳回后重新提交 = 重新进入审核,且旧驳回原因必须清掉。 */
    @Test
    @Timeout(5)
    void rejectedResubmit_goesBackToPendingAndClearsReason() {
        given(ME, 3L, 2L, 5);
        assertTrue(service.updateByMerchant(REG, ME, dto(), "u"));

        // 重置 status/audit_status/reason 是 mapper 的 <if test="rejected"> 干的,不经由 patch。
        // 所以这里只能断言 rejected=true 真的传到了 mapper;重置本身由
        // editMapper_clearsLimitAndCarriesAtomicGuards 直接对生成的 SQL 断言。
        // 断言 patch 上那三个字段等于 0 是假绿 —— SQL 根本不读它们。
        verify(regSvc).updateEditableFieldsIfStateMatches(any(), eq(ME), eq(3L), eq(2L), eq(true));
    }

    /** 审核中提交不动 status —— 别把「保存」当成「重新提交」去改状态。 */
    @Test
    @Timeout(5)
    void pendingEdit_doesNotTouchStatus() {
        given(ME, 0L, 5);
        service.updateByMerchant(REG, ME, dto(), "u");
        assertNull(captureSaved().getStatus());
    }

    /** 闸①:只能改自己的。 */
    @Test
    @Timeout(5)
    void othersRegistration_rejected() {
        given(OTHER, 0L, 5);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.updateByMerchant(REG, ME, dto(), "u"));
        assertTrue(e.getMessage().contains("无权"), "错误文案要指明是归属闸拦的:" + e.getMessage());
        verify(regSvc, never()).updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean());
    }

    /** 闸②:主题一开跑就冻结 —— 玩家已经在按这份内容到店了。 */
    @Test
    @Timeout(5)
    void topicAlreadyStarted_rejected() {
        given(ME, 0L, -1);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.updateByMerchant(REG, ME, dto(), "u"));
        assertTrue(e.getMessage().contains("主题已开始"), "错误文案要指明是开始闸拦的:" + e.getMessage());
        verify(regSvc, never()).updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean());
    }

    /** 闸②边界:开始当天 00:00 起就算已开始,不是「当天还能改」。 */
    @Test
    @Timeout(5)
    void topicStartsToday_rejected() {
        given(ME, 0L, 0);
        assertThrows(RuntimeException.class, () -> service.updateByMerchant(REG, ME, dto(), "u"));
    }

    /**
     * 中国 23:30 时，明天 00:00 才开始的主题仍可修改。
     * 在 Los Angeles 两个时刻落在同一自然日，若偷用 JVM 默认时区会提前冻结。
     */
    @Test
    @Timeout(5)
    void chinaNaturalDay_doesNotDependOnJvmTimezone() {
        TimeZone original = TimeZone.getDefault();
        Instant now = Instant.parse("2026-08-23T15:30:00Z"); // 中国 2026-08-23 23:30
        Instant starts = Instant.parse("2026-08-23T16:00:00Z"); // 中国 2026-08-24 00:00
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            service = new MerchantRegistrationEditServiceImpl(regSvc, topicSvc) {
                @Override
                protected Instant currentInstant() {
                    return now;
                }
            };
            given(ME, 0L, 0L, 5);
            CmsTopic topic = new CmsTopic();
            topic.setId(TOPIC);
            topic.setStartDate(Date.from(starts));
            when(topicSvc.selectCmsTopicById(TOPIC)).thenReturn(topic);

            ZoneId hostZone = ZoneId.systemDefault();
            assertEquals(now.atZone(hostZone).toLocalDate(), starts.atZone(hostZone).toLocalDate(),
                    "负控前提:JVM 日历会把两个时刻看成同一天");
            assertTrue(LocalDate.of(2026, 8, 23).isBefore(LocalDate.of(2026, 8, 24)));
            assertTrue(service.updateByMerchant(REG, ME, dto(), "u"));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /** 闸③:已中标不许改。主办方是看着提交内容审过的,审后再改 = 绕过审核。 */
    @Test
    @Timeout(5)
    void approved_rejected() {
        given(ME, 1L, 5);
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.updateByMerchant(REG, ME, dto(), "u"));
        assertTrue(e.getMessage().contains("已通过审核"), "错误文案要指明是审核态闸拦的:" + e.getMessage());
        verify(regSvc, never()).updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean());
    }

    /**
     * 白名单:客户端塞承接标的 / 审核结论 / 钱,一律不得落库。
     * 这是这个新写口子最大的风险面 —— 改 nodeId 就是偷换承接标的,
     * 改 sharingRate 就是自己给自己加分润。
     */
    @Test
    @Timeout(5)
    void payloadCannotOverrideStakeAuditOrMoney() {
        given(ME, 0L, 5);

        RegistrationMerchantDTO evil = dto();
        evil.setTopicId(999L);
        evil.setNodeId(999L);
        evil.setTemplateId(999L);

        service.updateByMerchant(REG, ME, evil, "u");

        CmsRegistrationMerchant saved = captureSaved();
        assertNull(saved.getTopicId(), "承接的是哪条主题,不接受客户端改");
        assertNull(saved.getNodeId(), "承接的是哪个节点,不接受客户端改");
        assertNull(saved.getTemplateId(), "玩法模板跟着节点走,不接受客户端改");
        assertNull(saved.getAuditStatus(), "中标结论不接受客户端改");
        assertNull(saved.getSharingRate(), "分润比例不接受客户端改");
        assertNull(saved.getSharingAmount(), "分润金额不接受客户端改");
        assertNull(saved.getDelFlag(), "删除标记不走这个口");
    }

    /** 已取消的报名不该还能改回来。 */
    @Test
    @Timeout(5)
    void cancelledRegistration_rejected() {
        CmsRegistrationMerchant r = new CmsRegistrationMerchant();
        r.setId(REG);
        r.setMemberId(ME);
        r.setTopicId(TOPIC);
        r.setStatus(0L);
        r.setDelFlag(2);
        when(regSvc.selectCmsRegistrationMerchantById(REG)).thenReturn(r);

        assertThrows(RuntimeException.class, () -> service.updateByMerchant(REG, ME, dto(), "u"));
        verify(regSvc, never()).updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean());
    }

    /** 审核方在先读后写窗口改了状态时，CAS 必须让商家保存转红，不能覆盖审核结果。 */
    @Test
    @Timeout(5)
    void stateChangedAfterRead_casRejectsStaleEdit() {
        given(ME, 0L, 0L, 5);
        when(regSvc.updateEditableFieldsIfStateMatches(any(), any(), any(), any(), anyBoolean())).thenReturn(0);

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> service.updateByMerchant(REG, ME, dto(), "u"));

        assertTrue(error.getMessage().contains("状态已变化"));
        verify(regSvc).updateEditableFieldsIfStateMatches(any(), eq(ME), eq(0L), eq(0L), eq(false));
    }

    /** 真解析 Mapper：NULL 清空与 owner/del/status/audit 四轴 CAS 必须落在同一条 UPDATE。 */
    @Test
    @Timeout(5)
    void editMapper_clearsLimitAndCarriesAtomicGuards() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsRegistrationMerchant", CmsRegistrationMerchant.class);
        String resource = "mapper/business/CmsRegistrationMerchantMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        CmsRegistrationMerchant patch = new CmsRegistrationMerchant();
        patch.setId(REG);
        patch.setLimitNum(null);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("patch", patch);
        params.put("memberId", ME);
        params.put("expectedStatus", 0L);
        params.put("expectedAuditStatus", 0L);
        params.put("rejected", false);
        String sql = configuration.getMappedStatement(CmsRegistrationMerchantMapper.class.getName()
                        + ".updateEditableFieldsIfStateMatches")
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("limit_num = ?"), "空限定人数也必须进入 SET，实际 SQL:" + sql);
        assertTrue(sql.contains("member_id = ?") && sql.contains("coalesce(del_flag, 0) = 0")
                        && sql.contains("coalesce(status, 0) = ?")
                        && sql.contains("coalesce(audit_status, 0) = ?"),
                "编辑 UPDATE 必须在同一语句绑定归属与双状态 CAS，实际 SQL:" + sql);
        // status 列可空(base_schema `status` int DEFAULT NULL)，而闸口按 null→0 归一后放行。
        // 三个 CAS 轴必须一致 coalesce：漏掉任一个，该行的 `X = NULL` 恒不成立 ⇒ 0 rows ⇒
        // 商家永远卡在「报名状态已变化,请刷新后重试」且无从解开。这条断言就是它的负控。
        assertFalse(sql.matches(".*\\band status = \\?.*"),
                "status 必须 coalesce 后再比，裸比会把 NULL 状态的行永久锁死，实际 SQL:" + sql);

        params.put("rejected", true);
        String rejectedSql = configuration.getMappedStatement(CmsRegistrationMerchantMapper.class.getName()
                        + ".updateEditableFieldsIfStateMatches")
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(rejectedSql.contains("set limit_num = ?, status = 0, audit_status = 0, reason = ''"),
                "驳回重提必须原子清双轴与原因，实际 SQL:" + rejectedSql);
    }
}
