package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.ClubEventSeries;
import com.chengyinhub.business.domain.vo.ClubEventOccurrenceRowVO;
import com.chengyinhub.business.mapper.ClubEventOpsMapper;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubEventScheduleMaterializer;
import com.chengyinhub.business.service.impl.ClubEventOpsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Figma E1「日期清单」的可编辑性判定。
 * ★ 核心那条:报名数算不出来(null)时 editable 必须是 false。
 *   显示成 0 只是看错;放开编辑会让人真的改掉一场已经有人报名的活动。
 */
class ClubEventOccurrenceListTest {
    private static final Long CLUB_ID = 7L;
    private static final Long SERIES_ID = 33L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private ClubEventOpsMapper mapper;
    private IClubAccessService access;
    private ClubEventOpsServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubEventOpsMapper.class);
        access = mock(IClubAccessService.class);
        when(access.require(anyLong(), anyLong(), any(), anyString())).thenReturn(
                new ClubAccessContext(1L, CLUB_ID, 1L, null,
                        new LinkedHashSet<>(Collections.singletonList(ClubRoleCode.CLUB_OWNER))));
        // 只测清单口径,其余协作者给 mock 即可;requireActivityManage 走 IClubAccessService
        service = new ClubEventOpsServiceImpl(
                access,
                mock(ClubEventScheduleMaterializer.class),
                mock(IClubSessionService.class),
                mapper,
                mock(ClubMemberMapper.class),
                mock(ClubLeadMapper.class),
                mock(IClubRoleAssignmentService.class));

        ClubEventSeries series = new ClubEventSeries();
        series.setId(SERIES_ID);
        series.setClubId(CLUB_ID);
        series.setTopicId(501L);
        series.setStatus("ACTIVE");
        series.setVersion(1);
        series.setDefaultLeadMemberId(1L);
        series.setWaitlistEnabled(Boolean.TRUE);
        series.setOfferMinutes(30);
        when(mapper.selectActiveSeriesByIdAndClub(SERIES_ID, CLUB_ID)).thenReturn(series);
    }

    private static ClubEventOccurrenceRowVO row(long id, long offsetDays, String status,
                                                Integer signup, Integer refunded) {
        ClubEventOccurrenceRowVO vo = new ClubEventOccurrenceRowVO();
        vo.setOccurrenceId(id);
        vo.setActivityId(1000 + id);
        vo.setOccurrenceAt(new Date(System.currentTimeMillis() + offsetDays * DAY));
        vo.setStatus(status);
        vo.setVersion(1);
        vo.setSignupCount(signup);
        vo.setRefundedCount(refunded);
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> at(List<Map<String, Object>> rows, long occurrenceId) {
        for (Map<String, Object> row : rows) {
            if (Long.valueOf(occurrenceId).equals(row.get("occurrenceId"))) return row;
        }
        throw new AssertionError("没有 occurrenceId=" + occurrenceId + " 这一行");
    }

    private List<Map<String, Object>> list(List<ClubEventOccurrenceRowVO> rows) {
        when(mapper.selectSeriesOccurrenceRows(SERIES_ID, CLUB_ID)).thenReturn(rows);
        return service.listSeriesOccurrences(1L, CLUB_ID, SERIES_ID);
    }

    @Test
    void 报名数算不出来时一律不可编辑() {
        List<Map<String, Object>> out = list(Arrays.asList(
                row(1, 7, "ACTIVE", null, null)));
        Map<String, Object> only = at(out, 1);
        assertEquals(Boolean.FALSE, only.get("editable"), "报名数未知却放开了编辑");
        assertNull(only.get("signupCount"), "报名数未知不能变成 0");
        assertEquals("报名数待确认", only.get("lockReason"));
    }

    @Test
    void 未来且零报名才可编辑() {
        List<Map<String, Object>> out = list(Arrays.asList(
                row(1, 7, "ACTIVE", 0, 0),
                row(2, 7, "ACTIVE", 3, 0)));
        assertEquals(Boolean.TRUE, at(out, 1).get("editable"));
        assertEquals(Boolean.FALSE, at(out, 2).get("editable"), "已有报名却放开了编辑");
        assertEquals("不可编辑", at(out, 2).get("lockReason"));
    }

    @Test
    void 已过去的场次不可编辑() {
        List<Map<String, Object>> out = list(Arrays.asList(row(1, -1, "ACTIVE", 0, 0)));
        assertEquals(Boolean.FALSE, at(out, 1).get("editable"));
        assertEquals("已结束", at(out, 1).get("lockReason"));
    }

    @Test
    void 已取消的场次要出现在清单里而不是被过滤掉() {
        // 「本场已取消」本身就是一种要看见的状态 —— 过滤掉等于让主理人以为这天还在
        List<Map<String, Object>> out = list(Arrays.asList(row(1, 7, "CANCELLED", 0, 4)));
        assertEquals(1, out.size());
        assertEquals("本场已取消", at(out, 1).get("lockReason"));
        assertEquals(Boolean.FALSE, at(out, 1).get("editable"));
        assertEquals(4, at(out, 1).get("refundedCount"));
    }

    @Test
    void 没有活动管理权限时连清单都拿不到() {
        // 这一条不是摆设:日期清单里带着每场的报名人数,不能对任意成员开放
        when(access.require(anyLong(), anyLong(), any(), anyString()))
                .thenThrow(new com.chengyinhub.common.exception.ServiceException("权限不足"));
        when(mapper.selectSeriesOccurrenceRows(SERIES_ID, CLUB_ID))
                .thenReturn(Arrays.asList(row(1, 7, "ACTIVE", 3, 0)));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.chengyinhub.common.exception.ServiceException.class,
                () -> service.listSeriesOccurrences(1L, CLUB_ID, SERIES_ID));
    }
}
