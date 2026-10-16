package com.chengyinhub.business.service;

import com.chengyinhub.business.mapper.PlayerWithdrawalCooldownMapper;
import com.chengyinhub.business.service.impl.PlayerWithdrawalCooldownServiceImpl;
import com.chengyinhub.business.service.support.WithdrawalFrozenAmountResolver;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A3：起点只认 DB 明细时间、边界只在 SQL 里比、查不出来就阻断提现。 */
class PlayerWithdrawalCooldownServiceTest {
    private static final long MEMBER = 100L;
    private static final long COMPLAINT = 1L;
    private static final long DETAIL = 4242L;

    private PlayerWithdrawalCooldownMapper mapper;
    private ISysConfigService configService;
    private PlayerWithdrawalCooldownServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(PlayerWithdrawalCooldownMapper.class);
        configService = mock(ISysConfigService.class);
        when(mapper.insertForPositiveComplaintDetail(anyLong(), anyLong(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(1);
        service = new PlayerWithdrawalCooldownServiceImpl(mapper, configService);
    }

    // ── §5.3-2 起点与时长 ─────────────────────────────────────────────────
    @Test
    void registrationPassesNoJavaTimestampAtAllAndDefaultsToFortyEightHours() {
        service.registerFromPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, new BigDecimal("50"));

        // 参数里根本没有时间：created_at / cooldown_until 由 SQL 从余额明细的 create_time 派生。
        verify(mapper).insertForPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, new BigDecimal("50"),
                IPlayerWithdrawalCooldownService.CONFIG_KEY, 48);
    }

    @Test
    void aFailedRegistrationThrowsSoTheWholeAdvanceRollsBack() {
        when(mapper.insertForPositiveComplaintDetail(anyLong(), anyLong(), anyLong(), any(), anyString(), anyInt()))
                .thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.registerFromPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, new BigDecimal("50")));

        assertEquals("PLAYER_WITHDRAWAL_COOLDOWN_WRITE_FAILED", error.getMessage());
    }

    @Test
    void nonPositiveCompensationIsRefusedRatherThanRegisteredAsZeroFreeze() {
        assertThrows(IllegalArgumentException.class,
                () -> service.registerFromPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> service.registerFromPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, null));
        verify(mapper, never()).insertForPositiveComplaintDetail(anyLong(), anyLong(), anyLong(), any(),
                anyString(), anyInt());
    }

    // ── §5.3-10 配置只影响新事件 ───────────────────────────────────────────
    @Test
    void changingTheConfigOnlyAffectsNewEvents() {
        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("24");

        service.registerFromPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, new BigDecimal("50"));

        // 新事件用 24；既有行的 cooldown_until 是快照值，本服务没有任何批量改写它的语句。
        verify(mapper).insertForPositiveComplaintDetail(DETAIL, MEMBER, COMPLAINT, new BigDecimal("50"),
                IPlayerWithdrawalCooldownService.CONFIG_KEY, 24);
    }

    @Test
    void aBrokenConfigFailsClosedInsteadOfSilentlyDroppingTheCooldown() {
        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("0");
        assertThrows(IllegalStateException.class, () -> service.currentCooldownHours());

        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("abc");
        assertThrows(IllegalStateException.class, () -> service.currentCooldownHours());

        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("-1");
        assertThrows(IllegalStateException.class, () -> service.currentCooldownHours());
    }

    // ── §5.3-8 查询故障必须阻断提现 ────────────────────────────────────────
    @Test
    void aFailingLookupPropagatesInsteadOfBeingReadAsNoFreeze() {
        when(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER))
                .thenThrow(new RuntimeException("connection reset"));

        assertThrows(RuntimeException.class, () -> service.frozenAmount(MEMBER));
    }

    @Test
    void anEmptyResultIsZeroButANullMemberIsAlsoZero() {
        when(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)).thenReturn(null);

        assertEquals(BigDecimal.ZERO, service.frozenAmount(MEMBER));
        assertEquals(BigDecimal.ZERO, service.frozenAmount(null));
        verify(mapper, never()).sumUnexpiredFrozenByMemberAtDbNow(null);
    }

    // ── §5.3-9 组合冻结：两个来源相加，一次给出 ───────────────────────────
    @Test
    void theSharedResolverAddsBountyAndComplaintFreezesIntoOneNumber() {
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        when(bounty.frozenAmount(MEMBER)).thenReturn(new BigDecimal("30"));
        when(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)).thenReturn(new BigDecimal("20"));
        WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(bounty, service);

        WithdrawalFrozenAmountResolver.Frozen frozen = resolver.resolve(MEMBER);

        assertEquals(new BigDecimal("50"), frozen.total());
        assertEquals(new BigDecimal("30"), frozen.getReferralBounty());
        assertEquals(new BigDecimal("20"), frozen.getPlayerComplaintCooldown());
    }

    @Test
    void theResolverNamesWhichGateIsHoldingTheMoney() {
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        when(bounty.frozenAmount(MEMBER)).thenReturn(BigDecimal.ZERO);
        when(mapper.sumUnexpiredFrozenByMemberAtDbNow(MEMBER)).thenReturn(new BigDecimal("20"));
        WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(bounty, service);

        String reason = resolver.resolve(MEMBER).describe();

        assertTrue(reason.contains("投诉补偿冷静期"), "拒绝理由必须点名是哪道闸,实际:" + reason);
    }

    @Test
    void aFailingBountyLookupAlsoBlocksTheWholeWithdrawal() {
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        when(bounty.frozenAmount(MEMBER)).thenThrow(new RuntimeException("bounty lookup down"));
        WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(bounty, service);

        assertThrows(RuntimeException.class, () -> resolver.resolve(MEMBER));
    }

    @Test
    void aNullMemberResolvesToZeroWithoutTouchingEitherSource() {
        MerchantReferralBountyService bounty = mock(MerchantReferralBountyService.class);
        WithdrawalFrozenAmountResolver resolver = new WithdrawalFrozenAmountResolver(bounty, service);

        assertEquals(BigDecimal.ZERO, resolver.resolve(null).total());

        verify(bounty, never()).frozenAmount(any());
        verify(mapper, never()).sumUnexpiredFrozenByMemberAtDbNow(any());
    }

    /** 边界口径钉在 SQL 里：cooldown_until &gt; now ⇒ 47:59 仍冻结、48:00 整放行。 */
    @Test
    void theBoundaryComparisonLivesInSqlAndUsesTheDatabaseClock() {
        String xml = resource("mapper/business/PlayerWithdrawalCooldownMapper.xml");
        int at = xml.indexOf("<select id=\"sumUnexpiredFrozenByMemberAtDbNow\"");
        assertTrue(at >= 0);
        String statement = xml.substring(at, xml.indexOf("</select>", at));

        assertTrue(statement.contains("CURRENT_TIMESTAMP(3)"), "边界必须用数据库时钟,不许 Java 传时间");
        assertTrue(statement.contains("cooldown_until &gt; CURRENT_TIMESTAMP(3)"),
                "必须是严格大于:等于 cooldown_until 的那一刻要放行(48:00 整允许)");
        assertFalse(statement.contains("#{now"), "SQL 里不得出现 Java 传进来的时间参数");
    }

    /**
     * ★起点必须是数据库时钟，且只挂 event_type=9 的正向补偿。
     *
     * <p>此前起点取 {@code d.create_time} —— 那一列是通用插入面拿 Java 的
     * {@code DateUtils.getNowDate()} 显式写进去的，读回来仍是 JVM 时刻。
     * 起点用应用机时钟、下面的到期比较用库机时钟 ⇒ 跨时钟比较，
     * 两台机器漂移 Δ 就把 48h 窗口整体平移，冻结款可提前提走。
     */
    @Test
    void registrationDerivesBothItsStartAndItsExpiryFromTheDatabaseClock() {
        String xml = resource("mapper/business/PlayerWithdrawalCooldownMapper.xml");
        int at = xml.indexOf("<insert id=\"insertForPositiveComplaintDetail\">");
        assertTrue(at >= 0);
        String statement = xml.substring(at, xml.indexOf("</insert>", at));
        String values = statement.substring(statement.indexOf("select #{balanceDetailId}"));

        assertTrue(values.contains("CURRENT_TIMESTAMP(3), DATE_ADD(CURRENT_TIMESTAMP(3)"),
                "起点与到期都必须取库时钟，与 sumUnexpiredFrozenByMemberAtDbNow 同一个时钟");
        assertFalse(values.contains("d.create_time"),
                "起点不得回退到那一列 —— 它承载的是 Java 写进去的 JVM 时刻");
        assertFalse(values.contains("#{createdAt"), "Java 不得把起点当参数传进来");
        // 归属校验仍然要从对面读：这条明细必须真是该 member 的 event_type=9 正向补偿。
        assertTrue(statement.contains("d.event_type = 9"), "只跟踪投诉正向补偿事件");
        assertTrue(statement.contains("d.change_type = 1"), "只跟踪加钱那一侧");
        assertTrue(statement.contains("d.member_id = #{memberId}"), "不得跨 member 挂冷静期");
        assertFalse(statement.contains("event_type = 4"), "提现驳回退款不得注册冷静期");
    }

    // ── 专用改写入口（审计 2：冷却配置「不是普通编辑接口」）─────────────────
    /**
     * ★把 48 改成 1 这件事必须留下「谁、什么时候、从多少改到多少、为什么」。
     *
     * <p>此前唯一的改写路径是 RuoYi 通用参数页（{@code system:config:edit}）：它只写新值、
     * 不留前值，「理由」这一项通用参数页本来就不采集。上一轮把那条**从没被任何代码引用**的
     * 假权限删掉了（方向对，E3「宁可明确没有」），但原始要求仍然开着口 ——
     * 「带前值/后值/理由的专用改写端点」当时是一条<b>没做</b>的待办。这一条把它做上。
     */
    @Test
    void rewritingTheCooldownRecordsWhoChangedItFromWhatToWhatAndWhy() {
        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("48");
        when(mapper.insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong())).thenReturn(1);

        service.rewriteCooldownHours(24, "投诉争议期按法务意见收窄", 71L);

        // 先值 48、后值 24、理由与操作人一并落库 —— 这四样缺一，这次改动就是查不出来的。
        verify(mapper).insertConfigChangeAudit(48, 24, "投诉争议期按法务意见收窄", 71L);
        // ★走的必须是带 key 的专用出口：通用 updateConfig 拿 config_id 反查再解引用，
        //   而这里根本没有主键可传 —— 那条路第一句就 NPE，配置一个字都改不动。
        //   「对面那一行真的变了吗」由 CooldownConfigWriteThroughRealConfigServiceTest
        //   跑真的 SysConfigServiceImpl 观测，本条只钉住调的是哪个出口。
        verify(configService).updateFundsPolicyConfigByKey(
                IPlayerWithdrawalCooldownService.CONFIG_KEY, "24", "71");
        verify(configService, never()).updateConfig(any());
    }

    /** 审计写不进去就不许改配置：留不下痕的改写等于没有闸。 */
    @Test
    void aFailedAuditRowBlocksTheRewriteInsteadOfChangingTheConfigSilently() {
        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("48");
        when(mapper.insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong())).thenReturn(0);

        assertEquals("PLAYER_WITHDRAWAL_COOLDOWN_AUDIT_WRITE_FAILED", assertThrows(IllegalStateException.class,
                () -> service.rewriteCooldownHours(24, "理由", 71L)).getMessage());
        verify(configService, never()).updateFundsPolicyConfigByKey(anyString(), anyString(), anyString());
    }

    /** 0 / 负数 / 空理由 / 无操作人都不许进来：那等于把冷静期悄悄关掉且查不到是谁干的。 */
    @Test
    void anIllegalRewriteIsRefusedBeforeItTouchesAnything() {
        when(configService.selectFundsPolicyValueForUpdate(IPlayerWithdrawalCooldownService.CONFIG_KEY)).thenReturn("48");

        assertThrows(IllegalArgumentException.class, () -> service.rewriteCooldownHours(0, "理由", 71L));
        assertThrows(IllegalArgumentException.class, () -> service.rewriteCooldownHours(-1, "理由", 71L));
        assertThrows(IllegalArgumentException.class, () -> service.rewriteCooldownHours(24, "  ", 71L));
        assertThrows(IllegalArgumentException.class, () -> service.rewriteCooldownHours(24, "理由", null));

        verify(configService, never()).updateFundsPolicyConfigByKey(anyString(), anyString(), anyString());
        verify(mapper, never()).insertConfigChangeAudit(anyInt(), anyInt(), anyString(), anyLong());
    }

    private static String resource(String path) {
        try (java.io.InputStream input =
                     Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("找不到 " + path);
            java.util.Scanner scanner = new java.util.Scanner(input, "UTF-8").useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + path, e);
        }
    }
}
