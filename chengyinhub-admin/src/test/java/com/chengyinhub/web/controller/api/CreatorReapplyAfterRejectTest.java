package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CreatorProfile;
import com.chengyinhub.business.domain.dto.CreatorProfileApplyDTO;
import com.chengyinhub.business.mapper.CreatorIncomeDetailMapper;
import com.chengyinhub.business.mapper.CreatorMetricDailyMapper;
import com.chengyinhub.business.mapper.CreatorProfileMapper;
import com.chengyinhub.business.service.impl.CreatorCenterServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0-86:被驳回的创作者申请必须能改资料重新提交;待审/已通过仍不重复受理。 */
class CreatorReapplyAfterRejectTest {

    private static final String XML = "chengyinhub-system/src/main/resources/mapper/business/CreatorProfileMapper.xml";

    private CreatorProfileMapper profileMapper;
    private CreatorCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        profileMapper = mock(CreatorProfileMapper.class);
        service = new CreatorCenterServiceImpl();
        ReflectionTestUtils.setField(service, "creatorProfileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "creatorMetricDailyMapper", mock(CreatorMetricDailyMapper.class));
        ReflectionTestUtils.setField(service, "creatorIncomeDetailMapper", mock(CreatorIncomeDetailMapper.class));
    }

    @Test
    void rejectedApplicantCanResubmitAndGoesBackToPending() {
        when(profileMapper.selectByMemberId(1001L)).thenReturn(profile(55L, 1001L, 2));
        when(profileMapper.resubmitRejectedCreatorProfile(any(CreatorProfile.class))).thenReturn(1);

        assertEquals(1, service.apply(1001L, request("新笔名")));

        ArgumentCaptor<CreatorProfile> captor = ArgumentCaptor.forClass(CreatorProfile.class);
        verify(profileMapper).resubmitRejectedCreatorProfile(captor.capture());
        assertEquals(55L, captor.getValue().getId());
        assertEquals(1001L, captor.getValue().getMemberId());
        assertEquals("新笔名", captor.getValue().getCreatorName());
        verify(profileMapper, never()).insertCreatorProfile(any(CreatorProfile.class));
    }

    @Test
    void pendingOrApprovedApplicantIsNotResubmitted() {
        for (int status : new int[]{0, 1}) {
            CreatorProfileMapper mapper = mock(CreatorProfileMapper.class);
            ReflectionTestUtils.setField(service, "creatorProfileMapper", mapper);
            when(mapper.selectByMemberId(1001L)).thenReturn(profile(55L, 1001L, status));

            assertEquals(0, service.apply(1001L, request("笔名")), "status=" + status);
            verify(mapper, never()).resubmitRejectedCreatorProfile(any(CreatorProfile.class));
            verify(mapper, never()).insertCreatorProfile(any(CreatorProfile.class));
        }
    }

    @Test
    void resubmitSqlIsScopedToOwnerAndRejectedState() throws Exception {
        Path path = Paths.get(XML);
        if (!Files.exists(path)) path = Paths.get("..").resolve(XML);
        String xml = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<update id=\"resubmitRejectedCreatorProfile\".*?</update>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), "resubmitRejectedCreatorProfile 必须存在");
        String sql = m.group().replaceAll("\\s+", " ");
        assertTrue(sql.contains("where id = #{id} and member_id = #{memberId} and apply_status = 2"),
                "只能改本人的、已驳回的申请: " + sql);
        assertTrue(sql.contains("apply_status = 0"), "重新提交后回到待审: " + sql);
    }

    private static CreatorProfile profile(Long id, Long memberId, int status) {
        CreatorProfile p = new CreatorProfile();
        p.setId(id);
        p.setMemberId(memberId);
        p.setApplyStatus(status);
        return p;
    }

    private static CreatorProfileApplyDTO request(String name) {
        CreatorProfileApplyDTO dto = new CreatorProfileApplyDTO();
        dto.setCreatorName(name);
        return dto;
    }
}
