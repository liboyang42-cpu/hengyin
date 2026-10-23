package com.chengyinhub.business.community;

import com.chengyinhub.business.mapper.CommunityPostMapper;
import com.chengyinhub.business.service.CommunityTrustService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.ICommunityMediaReceiptService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.impl.CommunityPostServiceImpl;
import com.chengyinhub.business.service.support.CommunityPostLifecyclePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-24:关联对象「可选项」与「发帖校验」必须同一份白名单。
 * 此前可选项收 MEMBER、不收 CLUB,发帖校验正相反 —— 选出来的成员发帖必 400,俱乐部又选不出来。
 */
class CommunityReferenceOptionsWhitelistTest {

    private CommunityPostMapper mapper;
    private CommunityPostServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(CommunityPostMapper.class);
        service = new CommunityPostServiceImpl(mapper, new CommunityPostLifecyclePolicy(), mock(IContentSafetyGate.class),
                mock(IAuditTaskService.class), mock(CommunityTrustService.class),
                mock(ICommunityMediaReceiptService.class));
    }

    @Test
    void memberIsNotAReferenceOptionBecausePublishRejectsIt() {
        ServiceException e = assertThrows(ServiceException.class, () -> service.referenceOptions(7L, "member", 10));
        assertEquals(400, e.getCode());
        verify(mapper, never()).selectReferenceOptions(anyString(), anyLong(), anyInt());
    }

    @Test
    void everyPublishableTypeIsAnOption() {
        for (String type : new String[] {"ACTIVITY", "TOPIC", "ROUTE", "CLUB", "POI"}) {
            service.referenceOptions(7L, type.toLowerCase(), 10);
            verify(mapper).selectReferenceOptions(eq(type), eq(7L), eq(10));
        }
    }

    @Test
    void clubOptionsQueryOnlyListsOwnOrJoinedLiveClubs() throws Exception {
        String xml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mapper/business/CommunityPostMapper.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = xml.indexOf("<select id=\"selectReferenceOptions\"");
        String select = xml.substring(start, xml.indexOf("</select>", start)).replaceAll("\\s+", " ");
        int club = select.indexOf("<when test=\"referenceType == 'CLUB'\">");
        assertTrue(club >= 0, "CLUB 必须有真实的可选项查询,不能落到空的 otherwise");
        String branch = select.substring(club, select.indexOf("</when>", club));
        assertTrue(branch.contains("FROM club c"), branch);
        assertTrue(branch.contains("c.status=1") && branch.contains("COALESCE(c.del_flag,0)=0"), "与快照同口径:只列在架俱乐部");
        assertTrue(branch.contains("c.member_id=#{memberId}") && branch.contains("cm.member_id=#{memberId}")
                && branch.contains("cm.status=1"), "只列本人创建或已通过审批的俱乐部");
        assertFalse(select.contains("referenceType == 'MEMBER'"), "MEMBER 可选项分支已移除");
    }
}
