package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.RoamStamp;
import com.chengyinhub.business.domain.RoamStampExchange;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.RoamStampMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.Collections;

import com.alibaba.fastjson2.JSON;

import static com.chengyinhub.web.controller.api.ApiRoamStampController.clampPageSize;
import static com.chengyinhub.web.controller.api.ApiRoamStampController.normalizeCaption;
import static com.chengyinhub.web.controller.api.ApiRoamStampController.safeOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 集邮接口:纯逻辑(caption 归一 / 分页夹取)+ create 入口的行为测试(幂等与机审接线)。
 *
 * ⚠️ 「置 2 后 list 不可见」那条闸活在 mapper XML 的 `check_state != 2` 里,本仓库无 DB 测试基建
 * ⇒ 本文件测不到它,不要在这里写「断言 XML 文本里有那串字符」的替身,那是假绿。
 * 该闸(连同 uk_member_idem 与 markCheckPassed 的 check_state=0 条件)已在生产库
 * TEMPORARY TABLE 上带负控实测,结论记在 sql/migration_p6_roam_stamp.sql 文件头。
 */
class RoamStampControllerTest {

    /** 覆写 uid() 注入身份,好让 create 的真入口被直调 —— 静态纯函数测不到闸。 */
    private static class StubController extends ApiRoamStampController {
        @Override Long uid() { return 9L; }
    }

    private StubController controller(RoamStampMapper mapper, IWxContentSecurityService sec) {
        StubController c = new StubController();
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember m = new UmsMember();
        m.setWxOpenId("openid-9");
        when(memberService.selectUmsMemberById(9L)).thenReturn(m);
        ReflectionTestUtils.setField(c, "roamStampMapper", mapper);
        ReflectionTestUtils.setField(c, "wxContentSecurityService", sec);
        ReflectionTestUtils.setField(c, "umsMemberService", memberService);
        return c;
    }

    private static RoamStamp stamp(long id) {
        RoamStamp s = new RoamStamp();
        s.setId(id);
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(AjaxResult r) {
        return (Map<String, Object>) r.get("data");
    }

    @Test
    void create_submitsMediaCheckAndWritesBackTraceId() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        when(mapper.insertStamp(any())).thenAnswer(inv -> {
            inv.getArgument(0, RoamStamp.class).setId(7L);
            return 1;
        });
        when(sec.submitMediaCheckOne(eq(IWxContentSecurityService.BIZ_TYPE_ROAM_STAMP), eq(7L), eq("https://o/p.jpg"), eq("openid-9")))
                .thenReturn("trace-7");

        AjaxResult r = controller(mapper, sec).create("https://o/p.jpg", null, "k-1");

        assertEquals(200, r.get("code"));
        verify(mapper).updateCheckTrace(7L, "trace-7");
    }

    @Test
    void create_checkDisabledLeavesStateZeroAndStillSucceeds() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        when(mapper.insertStamp(any())).thenAnswer(inv -> {
            inv.getArgument(0, RoamStamp.class).setId(7L);
            return 1;
        });
        // 机审关闭/送检失败 ⇒ submitMediaCheckOne 返 null(既有实现的语义)
        when(sec.submitMediaCheckOne(anyInt(), anyLong(), anyString(), any())).thenReturn(null);

        AjaxResult r = controller(mapper, sec).create("https://o/p.jpg", null, null);

        assertEquals(200, r.get("code"), "机审关闭不阻断入册");
        // 拍板:留在 0=未送检,不谎报「通过」。没有 trace 就不该回写 trace。
        verify(mapper, never()).updateCheckTrace(anyLong(), anyString());
    }

    @Test
    void create_mediaCheckExplosionDoesNotTurnAStoredStampInto500() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        when(mapper.insertStamp(any())).thenAnswer(inv -> {
            inv.getArgument(0, RoamStamp.class).setId(7L);
            return 1;
        });
        when(sec.submitMediaCheckOne(anyInt(), anyLong(), anyString(), any())).thenThrow(new RuntimeException("wx down"));

        AjaxResult r = controller(mapper, sec).create("https://o/p.jpg", null, null);

        // 票已经在库里了。这时返 500 会让用户以为没存上而重拍,反倒制造重复。
        assertEquals(200, r.get("code"));
    }

    @Test
    void create_sameIdemKeyReturnsTheOriginalStampAndDoesNotSubmitCheckTwice() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        when(mapper.insertStamp(any())).thenThrow(new DuplicateKeyException("uk_member_idem"));
        when(mapper.selectByMemberAndIdemKey(9L, "k-dup")).thenReturn(stamp(42L));

        AjaxResult r = controller(mapper, sec).create("https://o/p.jpg", null, "k-dup");

        assertEquals(200, r.get("code"));
        assertEquals(42L, dataOf(r).get("id"), "同键重放必须返回原票,不是新票");
        assertEquals(Boolean.TRUE, dataOf(r).get("idempotent"));
        verify(sec, never()).submitMediaCheckOne(anyInt(), anyLong(), anyString(), any());
    }

    @Test
    void create_duplicateFromAnotherUkIsNotSwallowedAsAnIdempotentHit() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        // 表上还有 uk_member_geohash(P1 盖销一格一枚),它撞的是同一个 DuplicateKeyException
        when(mapper.insertStamp(any())).thenThrow(new DuplicateKeyException("uk_member_geohash"));
        when(mapper.selectByMemberAndIdemKey(anyLong(), anyString())).thenReturn(null);

        ApiRoamStampController c = controller(mapper, sec);

        // 🔴 负控:只 catch DuplicateKeyException 就当幂等命中的话,这里会静默返 success,
        // 把「这一格你已盖过」吞成「幂等命中」。回查为空必须原样抛,让上层看见真相。
        assertThrows(DuplicateKeyException.class, () -> c.create("https://o/p.jpg", null, "k-x"));
    }

    @Test
    void create_overlongIdemKeyIsRejectedRatherThanTruncated() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) sb.append('k');

        AjaxResult r = controller(mapper, sec).create("https://o/p.jpg", null, sb.toString());

        assertEquals(500, r.get("code"));
        assertTrue(String.valueOf(r.get("msg")).contains("幂等键"), "错误文案要指明是幂等键这道闸,不能只知道失败了");
        // 截断会让两个前缀相同的不同键撞成同一张票 = 用户静默丢邮票
        verify(mapper, never()).insertStamp(any());
    }

    @Test
    void normalizeIdemKey_blankBecomesNullSoItStaysOutOfTheUk() {
        assertNull(ApiRoamStampController.normalizeIdemKey(null));
        assertNull(ApiRoamStampController.normalizeIdemKey("   "), "全空白视为没送键:idem_key=NULL 不参与 uk");
        assertEquals("k1", ApiRoamStampController.normalizeIdemKey("  k1  "));
    }

    @Test
    void normalizeCaption_nullAndBlankBecomeNull() {
        assertNull(normalizeCaption(null));
        assertNull(normalizeCaption("   "), "全空白等于没写");
    }

    @Test
    void normalizeCaption_trimsAndTruncatesTo140() {
        assertEquals("hi", normalizeCaption("  hi  "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('a');
        assertEquals(140, normalizeCaption(sb.toString()).length(), "超长截到 140,不能让 DB 报 1406");
    }

    @Test
    void clampPageSize_defaultsAndClamps() {
        assertEquals(20, clampPageSize(null), "没传用默认 20");
        assertEquals(20, clampPageSize(0), "0 不合法,回默认");
        assertEquals(1, clampPageSize(1));
        assertEquals(50, clampPageSize(999), "夹到上限 50,防一次拉数百张");
    }

    @Test
    void safeOffset_hugePageNumDoesNotOverflowToNegative() {
        assertTrue(safeOffset(Integer.MAX_VALUE, 50) >= 0, "pageNum 极大时 int 乘法溢出成负数,负 offset 传给 MySQL LIMIT 会 500");
        assertEquals(0, safeOffset(1, 50));
        assertEquals(40, safeOffset(3, 20));
    }

    @Test
    void listDoesNotSerializeInternalCheckTraceButKeepsMiniProgramFields() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        IWxContentSecurityService sec = mock(IWxContentSecurityService.class);
        RoamStamp row = new RoamStamp();
        row.setId(7L);
        row.setPicUrl("https://o/p.jpg");
        row.setCaption("caption");
        row.setCheckTrace("trace-secret");
        when(mapper.selectByMember(9L, 0, 20)).thenReturn(Collections.singletonList(row));
        when(mapper.countByMember(9L)).thenReturn(1);

        AjaxResult result = controller(mapper, sec).list(1, 20);
        String json = JSON.toJSONString(result);

        assertTrue(json.contains("https://o/p.jpg"));
        assertTrue(json.contains("\"id\":7"));
        assertFalse(json.contains("checkTrace"));
        assertFalse(json.contains("trace-secret"));
    }

    @Test
    void normalizeCaption_doesNotSplitSurrogatePair() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 139; i++) sb.append('a');
        sb.append("😀"); // 😀,高代理+低代理共 2 char,总长 141
        String r = normalizeCaption(sb.toString());
        assertFalse(Character.isHighSurrogate(r.charAt(r.length() - 1)), "截断点落在代理对中间,不能留落单高代理项(JDBC 写库会被替换成 ?)");
        assertTrue(r.length() <= 140);
    }

    // ══ 投一张换一张 ══════════════════════════════════════════════════════════

    private static RoamStamp stamp(long id, long owner, int checkState) {
        RoamStamp s = new RoamStamp();
        s.setId(id);
        s.setMemberId(owner);
        s.setCheckState(checkState);
        s.setDelFlag(0);
        s.setPicUrl("https://x/" + id + ".jpg");
        s.setCaption("第 " + id + " 句");
        return s;
    }

    private static RoamStampExchange exchange(long given, long received) {
        RoamStampExchange e = new RoamStampExchange();
        e.setMemberId(9L);
        e.setGivenStampId(given);
        e.setReceivedStampId(received);
        return e;
    }

    /** 代价必须是自己的票。拿别人的票 id 当代价 = 白嫖一张,这是本玩法唯一的资损面。 */
    @Test
    void 换票的代价必须是本人还在册的票() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));

        when(mapper.selectById(50L)).thenReturn(stamp(50L, 999L, 1));   // 别人的票
        assertEquals(500, ((Integer) c.exchange(50L).get("code")).intValue());

        RoamStamp deleted = stamp(51L, 9L, 1);
        deleted.setDelFlag(1);
        when(mapper.selectById(51L)).thenReturn(deleted);               // 自己的但已删
        assertEquals(500, ((Integer) c.exchange(51L).get("code")).intValue());

        when(mapper.selectById(52L)).thenReturn(null);                  // 压根不存在
        assertEquals(500, ((Integer) c.exchange(52L).get("code")).intValue());

        // 三种都没走到候选查询,更没插过记录
        verify(mapper, never()).selectExchangeCandidates(anyLong(), anyInt());
        verify(mapper, never()).insertExchange(any(RoamStampExchange.class));
    }

    /** 一张投出的票只兑换一次:重试 / 双击 / 弱网重发拿回同一枚,不会一次投票换走两张。 */
    @Test
    void 同一张投出的票重复兑换只返回同一枚回礼() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(mapper.selectExchangeByGiven(9L, 10L)).thenReturn(exchange(10L, 77L));
        when(mapper.selectById(77L)).thenReturn(stamp(77L, 999L, 1));

        Map<String, Object> data = dataOf(c.exchange(10L));
        assertEquals(Boolean.TRUE, data.get("exchanged"));
        assertEquals(Boolean.TRUE, data.get("idempotent"));
        verify(mapper, never()).insertExchange(any(RoamStampExchange.class));
        verify(mapper, never()).selectExchangeCandidates(anyLong(), anyInt());
    }

    /** 没有可换的就如实说没有,不编一张出来,也不把自己那张退回来充数。 */
    @Test
    void 没有候选时如实返回没换成() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(mapper.selectExchangeCandidates(eq(9L), anyInt())).thenReturn(Collections.<Long>emptyList());

        Map<String, Object> data = dataOf(c.exchange(10L));
        assertEquals(Boolean.FALSE, data.get("exchanged"));
        assertNotNull(data.get("reason"));
        assertNull(data.get("stamp"));
        verify(mapper, never()).insertExchange(any(RoamStampExchange.class));
    }

    /** 换成了:记录里 given 与 received 都要落对,回给前端的票不带作者身份。 */
    @Test
    void 换成之后落一条交换记录且回礼匿名() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(mapper.selectExchangeCandidates(eq(9L), anyInt())).thenReturn(Arrays.asList(88L, 89L));
        when(mapper.selectById(88L)).thenReturn(stamp(88L, 999L, 1));

        Map<String, Object> data = dataOf(c.exchange(10L));
        assertEquals(Boolean.TRUE, data.get("exchanged"));
        assertEquals(Boolean.FALSE, data.get("idempotent"));

        ArgumentCaptor<RoamStampExchange> captor = ArgumentCaptor.forClass(RoamStampExchange.class);
        verify(mapper).insertExchange(captor.capture());
        assertEquals(9L, captor.getValue().getMemberId().longValue());
        assertEquals(10L, captor.getValue().getGivenStampId().longValue());
        assertEquals(88L, captor.getValue().getReceivedStampId().longValue());

        // 回礼是 RoamStampListVO —— 序列化后不得出现 memberId。
        // 「TA 那句」是一句话不是一个人,露出作者就变成社交了。
        assertFalse(JSON.toJSONString(data.get("stamp")).contains("memberId"));
    }

    /**
     * 撞 uk_member_received(这张我早收过)要顺位换下一张;
     * 撞 uk_member_given(同一个人并发换两次)要回查返回同一枚。
     * 两道 uk 抛的是同一个 DuplicateKeyException —— 只 catch 不回查就会把前者当幂等吞掉。
     */
    @Test
    void 撞uk要分清是哪一道() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(mapper.selectExchangeCandidates(eq(9L), anyInt())).thenReturn(Arrays.asList(88L, 89L));
        when(mapper.selectById(89L)).thenReturn(stamp(89L, 999L, 1));

        // 第一张撞车、回查查不到 ⇒ 撞的是 received,顺位试第二张
        when(mapper.insertExchange(any(RoamStampExchange.class)))
                .thenThrow(new DuplicateKeyException("uk_member_received"))
                .thenReturn(1);
        when(mapper.selectExchangeByGiven(9L, 10L)).thenReturn(null);

        Map<String, Object> data = dataOf(c.exchange(10L));
        assertEquals(Boolean.TRUE, data.get("exchanged"));
        assertEquals(Boolean.FALSE, data.get("idempotent"));
        verify(mapper, org.mockito.Mockito.times(2)).insertExchange(any(RoamStampExchange.class));

        // 反过来:回查查得到 ⇒ 撞的是 given(并发),返回同一枚且不再顺位
        RoamStampMapper m2 = mock(RoamStampMapper.class);
        StubController c2 = controller(m2, mock(IWxContentSecurityService.class));
        when(m2.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(m2.selectExchangeCandidates(eq(9L), anyInt())).thenReturn(Arrays.asList(88L, 89L));
        when(m2.selectById(88L)).thenReturn(stamp(88L, 999L, 1));
        when(m2.insertExchange(any(RoamStampExchange.class)))
                .thenThrow(new DuplicateKeyException("uk_member_given"));
        when(m2.selectExchangeByGiven(9L, 10L)).thenReturn(null, exchange(10L, 88L));

        Map<String, Object> d2 = dataOf(c2.exchange(10L));
        assertEquals(Boolean.TRUE, d2.get("exchanged"));
        assertEquals(Boolean.TRUE, d2.get("idempotent"));
        verify(m2, org.mockito.Mockito.times(1)).insertExchange(any(RoamStampExchange.class));
    }

    /** 换到手之后那张被判违规:如实说下架了,不返回一个没有图的空壳票。 */
    @Test
    void 回礼被判违规时不返回空壳票() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectById(10L)).thenReturn(stamp(10L, 9L, 1));
        when(mapper.selectExchangeByGiven(9L, 10L)).thenReturn(exchange(10L, 77L));
        when(mapper.selectById(77L)).thenReturn(stamp(77L, 999L, 2));   // 已判违规

        Map<String, Object> data = dataOf(c.exchange(10L));
        assertEquals(Boolean.FALSE, data.get("exchanged"));
        assertNull(data.get("stamp"));
        assertNotNull(data.get("reason"));
    }

    /** 收件箱和自己的册子是两条不同的查询,不能互相顶替。 */
    @Test
    void 换回来的票走独立的收件箱查询() {
        RoamStampMapper mapper = mock(RoamStampMapper.class);
        StubController c = controller(mapper, mock(IWxContentSecurityService.class));
        when(mapper.selectReceivedByMember(eq(9L), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(stamp(88L, 999L, 1)));
        when(mapper.countReceivedByMember(9L)).thenReturn(1);

        Map<String, Object> data = dataOf(c.received(1, 20));
        assertEquals(1, ((java.util.List<?>) data.get("list")).size());
        assertEquals(1, ((Integer) data.get("total")).intValue());
        verify(mapper, never()).selectByMember(anyLong(), anyInt(), anyInt());
        assertFalse(JSON.toJSONString(data.get("list")).contains("memberId"));
    }
}
