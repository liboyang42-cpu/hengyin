package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.ai.AiMerchantPlanReq;
import com.chengyinhub.business.domain.vo.MerchantCandidateVO;
import com.chengyinhub.business.domain.vo.ai.AiMerchantPlanResp;
import com.chengyinhub.business.mapper.AiMerchantMatchSnapshotMapper;
import com.chengyinhub.business.mapper.ApiAiMapper;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;

class AiTopicPlanServiceImplTest
{
    private static final Long MEMBER_ID = 1001L;

    private AiTopicPlanServiceImpl service;
    private ApiAiMapper apiAiMapper;
    private AiGatewayService gateway;

    @BeforeEach
    void setUp()
    {
        service = new AiTopicPlanServiceImpl();
        apiAiMapper = mock(ApiAiMapper.class);
        gateway = mock(AiGatewayService.class);
        ReflectionTestUtils.setField(service, "apiAiMapper", apiAiMapper);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        ReflectionTestUtils.setField(service, "snapshotMapper", mock(AiMerchantMatchSnapshotMapper.class));

        IUmsMemberService memberService = mock(IUmsMemberService.class);
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        when(memberService.selectUmsMemberById(MEMBER_ID)).thenReturn(member);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);

        IRoleService roleService = mock(IRoleService.class);
        when(roleService.resolveRole(member)).thenReturn("club");
        ReflectionTestUtils.setField(service, "roleService", roleService);
    }

    @Test
    void generateCapsWeightedCandidatesAndRestoresFactsFromDatabase()
    {
        List<MerchantCandidateVO> candidates = new ArrayList<>();
        for (int i = 1; i <= 16; i++)
        {
            candidates.add(candidate((long) i, 1500D, 1D, 0L));
        }
        MerchantCandidateVO strongest = candidates.get(15);
        strongest.setDistance(100D);
        strongest.setRating(5D);
        strongest.setCouponStock(100L);
        when(apiAiMapper.selectNearbyMerchantCandidates(anyDouble(), anyDouble(), anyDouble(), eq(60)))
            .thenReturn(candidates);

        when(gateway.generate(eq(MEMBER_ID), eq("club"), eq("merchant_plan"), any(), any()))
            .thenReturn(new AiGatewayResult("trace-real", twoPlansJson(16L), "deepseek", 0));

        AiMerchantPlanResp response = service.generate(request(), MEMBER_ID);

        assertEquals(2, response.getPlans().size());
        assertEquals(Integer.valueOf(15), response.getCandidateCount());
        assertEquals("真实商家16", response.getPlans().get(0).getNodes().get(0).getMerchantName(),
            "candidateId 命中后仍须用数据库事实覆盖模型编造的商家名");
        assertEquals("地址16", response.getPlans().get(0).getNodes().get(0).getAddress());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(eq(MEMBER_ID), eq("club"), eq("merchant_plan"), any(), prompt.capture());
        JSONArray selected = JSON.parseObject(prompt.getValue()).getJSONArray("candidates");
        assertEquals(15, selected.size(), "送入模型的候选集必须硬封顶 Top-15");
        assertTrue(containsCandidate(selected, 16L), "加权最优商家必须进入 Top-15");
        assertFalse(containsCandidate(selected, 15L), "同分尾部候选必须被 Top-15 截断");
    }

    @Test
    void generateRejectsMalformedAiInsteadOfReturningEmptyPlans()
    {
        when(apiAiMapper.selectNearbyMerchantCandidates(anyDouble(), anyDouble(), anyDouble(), eq(60)))
            .thenReturn(java.util.Collections.singletonList(candidate(9L, 100D, 5D, 10L)));
        when(gateway.generate(eq(MEMBER_ID), eq("club"), eq("merchant_plan"), any(), any()))
            .thenReturn(new AiGatewayResult("trace-bad", "{not-json", "deepseek", 0));

        AiServiceException error = assertThrows(AiServiceException.class,
            () -> service.generate(request(), MEMBER_ID));

        assertEquals("AI 响应格式异常,请重试", error.getMessage(),
            "解析闸必须给出可诊断失败，不能伪装成 AI 没想出方案");
    }

    @Test
    void generateRejectsMockProviderEvenWhenMockPayloadLooksValid()
    {
        when(apiAiMapper.selectNearbyMerchantCandidates(anyDouble(), anyDouble(), anyDouble(), eq(60)))
            .thenReturn(java.util.Collections.singletonList(candidate(9L, 100D, 5D, 10L)));
        when(gateway.generate(eq(MEMBER_ID), eq("club"), eq("merchant_plan"), any(), any()))
            .thenReturn(new AiGatewayResult("trace-mock", twoPlansJson(9L), "mock", 0));

        AiServiceException error = assertThrows(AiServiceException.class,
            () -> service.generate(request(), MEMBER_ID));

        assertEquals("AI 服务暂未就绪,请联系管理员", error.getMessage(),
            "provider 闸必须独立指认，不能由相邻的 JSON/方案数量闸代为接住");
    }

    @Test
    void generateRejectsMissingCoordinatesInsteadOfUsingFakeDefault()
    {
        AiMerchantPlanReq req = request();
        req.setLat(null);

        AiServiceException error = assertThrows(AiServiceException.class,
            () -> service.generate(req, MEMBER_ID));

        assertEquals("请先在地图上选择方案所在区域", error.getMessage(),
            "坐标闸必须显式失败，不能静默落到虚假的默认城市");
        verify(gateway, never()).generate(any(), any(), any(), any(), any());
    }

    @Test
    void generateRejectsEmptyCandidatePoolBeforeCallingModel()
    {
        when(apiAiMapper.selectNearbyMerchantCandidates(anyDouble(), anyDouble(), anyDouble(), eq(60)))
            .thenReturn(java.util.Collections.emptyList());

        AiServiceException error = assertThrows(AiServiceException.class,
            () -> service.generate(request(), MEMBER_ID));

        assertEquals("附近暂无可用商家节点,请调整地图位置或范围", error.getMessage(),
            "候选池闸必须说明真实数据为空，不能返回空方案冒充 AI 成功");
        verify(gateway, never()).generate(any(), any(), any(), any(), any());
    }

    private AiMerchantPlanReq request()
    {
        AiMerchantPlanReq req = new AiMerchantPlanReq();
        req.setIdea("周末咖啡城市漫游");
        req.setLat(31.23D);
        req.setLng(121.47D);
        req.setRadiusM(2000);
        return req;
    }

    private MerchantCandidateVO candidate(Long id, Double distance, Double rating, Long stock)
    {
        MerchantCandidateVO c = new MerchantCandidateVO();
        c.setCandidateId(id);
        c.setMerchantId(10000L + id);
        c.setMerchantName("真实商家" + id);
        c.setAddress("地址" + id);
        c.setLongitude("121.47");
        c.setLatitude("31.23");
        c.setBusinessTime("10:00-22:00");
        c.setDistance(distance);
        c.setRating(rating);
        c.setCouponStock(stock);
        return c;
    }

    private String twoPlansJson(Long candidateId)
    {
        JSONObject root = new JSONObject();
        JSONArray plans = new JSONArray();
        for (int i = 1; i <= 2; i++)
        {
            JSONObject plan = new JSONObject();
            plan.put("title", "方案" + i);
            plan.put("storyline", "故事" + i);
            JSONArray nodes = new JSONArray();
            JSONObject node = new JSONObject();
            node.put("candidateId", candidateId);
            node.put("merchantName", "模型编造名");
            node.put("address", "模型编造地址");
            node.put("task", "完成现场观察");
            nodes.add(node);
            plan.put("nodes", nodes);
            plans.add(plan);
        }
        root.put("plans", plans);
        return root.toJSONString();
    }

    private boolean containsCandidate(JSONArray candidates, Long id)
    {
        for (int i = 0; i < candidates.size(); i++)
        {
            if (id.equals(candidates.getJSONObject(i).getLong("candidateId"))) return true;
        }
        return false;
    }
}
