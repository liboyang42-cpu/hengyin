package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.Date;

import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.OfficialEventV2PublishCheck;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.system.service.ISysConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O-05 #5:官方 V2 发布检查的 WARN 档 —— 只提示不阻断,与 BLOCK 分列;开关默认关,关时响应与旧版一致。 */
class OfficialEventPublishCheckWarnTierTest {

    private static final String SWITCH = "official_event.publish_check_warn_enabled";

    private OfficialEventV2ServiceImpl service(OfficialEventMapper eventMapper, OfficialEventV2Mapper v2Mapper,
                                               String switchValue) {
        OfficialEventV2ServiceImpl service = new OfficialEventV2ServiceImpl();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "v2Mapper", v2Mapper);
        ReflectionTestUtils.setField(service, "signupMapper", mock(EventSignupMapper.class));
        ReflectionTestUtils.setField(service, "roamMapper", mock(RoamMapper.class));
        ReflectionTestUtils.setField(service, "merchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "clubMapper", mock(ClubMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mock(CmsTopicMapper.class));
        ISysConfigService configService = mock(ISysConfigService.class);
        when(configService.selectConfigByKey(SWITCH)).thenReturn(switchValue);
        ReflectionTestUtils.setField(service, "configService", configService);
        IUmsMemberService umsMemberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setStatus(1L);
        member.setDelFlag(0);
        when(umsMemberService.selectUmsMemberById(org.mockito.ArgumentMatchers.anyLong())).thenReturn(member);
        ReflectionTestUtils.setField(service, "umsMemberService", umsMemberService);
        return service;
    }

    /** 时间线合法但报名期/距开场都很短,且无封面无介绍 —— 只该产出 WARN。 */
    private OfficialEvent thinEvent() {
        long now = System.currentTimeMillis();
        OfficialEvent event = new OfficialEvent();
        event.setId(7L);
        event.setContractVersion(2);
        event.setCreatorIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        event.setFulfillmentPolicy("SELF_RUN");
        event.setSignupStart(new Date(now + 60_000));
        event.setActivityStart(new Date(now + 120_000));
        event.setActivityEnd(new Date(now + 180_000));
        event.setSettleTime(new Date(now + 240_000));
        return event;
    }

    private OfficialEventV2PublishCheck check(String switchValue) {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        OfficialEventV2Mapper v2Mapper = mock(OfficialEventV2Mapper.class);
        when(eventMapper.selectOfficialEventById(7L)).thenReturn(thinEvent());
        when(v2Mapper.selectBindingsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectMissionsByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectPartiesByEvent(7L)).thenReturn(Collections.emptyList());
        when(v2Mapper.selectRequirementsByEvent(7L)).thenReturn(Collections.emptyList());
        return (OfficialEventV2PublishCheck) ReflectionTestUtils.invokeMethod(
                service(eventMapper, v2Mapper, switchValue), "checkPublishableInternal", 7L);
    }

    @Test
    void 开关关闭_不产出WARN且序列化不含新字段() throws Exception {
        OfficialEventV2PublishCheck check = check(null);

        assertNull(check.getWarnings());
        assertNull(check.getResults());
        JsonNode json = new ObjectMapper().valueToTree(check);
        assertFalse(json.has("warnings"));
        assertFalse(json.has("results"));
        assertTrue(json.has("blockers"));
    }

    @Test
    void 开关打开_BLOCK与WARN分列_WARN不影响是否放行() {
        OfficialEventV2PublishCheck check = check("true");

        assertFalse(check.isPass(), "缺任务/缺主办方仍是 BLOCK");
        assertEquals(4, check.getWarnings().size());
        int blocks = 0;
        int warns = 0;
        boolean sawWarn = false;
        for (OfficialEventV2PublishCheck.Result result : check.getResults()) {
            if (OfficialEventV2PublishCheck.LEVEL_BLOCK.equals(result.getLevel())) {
                assertFalse(sawWarn, "BLOCK 必须排在 WARN 前");
                blocks++;
            } else {
                assertEquals(OfficialEventV2PublishCheck.LEVEL_WARN, result.getLevel());
                sawWarn = true;
                warns++;
            }
        }
        assertEquals(check.getBlockers().size(), blocks);
        assertEquals(4, warns);
    }

    @Test
    void WARN单独存在时仍放行() {
        OfficialEventV2PublishCheck check = new OfficialEventV2PublishCheck();
        check.enableTiers();
        OfficialEventV2ServiceImpl.collectPublishWarnings(thinEvent(), check, new Date());

        assertTrue(check.isPass());
        assertTrue(check.getBlockers().isEmpty());
        assertTrue(check.getWarnings().contains("未设置封面图，活动卡片将使用默认图"));
        assertTrue(check.getWarnings().contains("距活动开始不足 24 小时，平台审核可能来不及完成"));
    }

    @Test
    void 内容齐全且提前量充足_无WARN() {
        long now = System.currentTimeMillis();
        OfficialEvent event = thinEvent();
        event.setCoverImg("/profile/upload/cover.png");
        event.setStory("城市漫游打卡，按任务完成度计奖");
        event.setSignupStart(new Date(now + 3600_000L));
        event.setActivityStart(new Date(now + 3L * 24 * 3600_000L));
        OfficialEventV2PublishCheck check = new OfficialEventV2PublishCheck();
        check.enableTiers();
        OfficialEventV2ServiceImpl.collectPublishWarnings(event, check, new Date(now));

        assertTrue(check.getWarnings().isEmpty());
    }

    @Test
    void 可选承接缺口按WARN输出() {
        OfficialEventV2PublishCheck check = new OfficialEventV2PublishCheck();
        check.enableTiers();
        check.risk("关键履约缺口：X");

        assertTrue(check.isPass());
        assertEquals(1, check.getResults().size());
        assertEquals(OfficialEventV2PublishCheck.LEVEL_WARN, check.getResults().get(0).getLevel());
    }
}
