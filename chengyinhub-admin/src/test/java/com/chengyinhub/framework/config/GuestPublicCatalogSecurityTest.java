package com.chengyinhub.framework.config;

import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.vo.*;
import com.chengyinhub.business.domain.dto.LeaderboardQueryDTO;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.platformpass.PlatformPassService;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.AppMediaReferenceGuard;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.web.controller.api.*;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Owner 2026-11-14: anonymous public reads, no personal projection, exact GET only. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@TestPropertySource(properties = {"token.header=Authorization", "token.secret=unit-test-secret-unit-test-secret-unit-test-secret",
        "token.expireTime=21600", "token.app.idleTime=1440", "token.app.absoluteTime=10080"})
@ContextConfiguration(classes = {PublicReadEndpointsSecurityChainTest.TestConfiguration.class, SecurityConfig.class,
        PermitAllUrlProperties.class, JwtAuthenticationTokenFilter.class, AuthenticationEntryPointImpl.class})
class GuestPublicCatalogSecurityTest {
    @Autowired WebApplicationContext context;
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    private MockMvc mvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters((Filter) context.getBean("springSecurityFilterChain")).build();
    }

    @Test void nearbyActivitiesUseGuestQueryAndRemovePersonalColumns() throws Exception {
        RoamHangoutMapper mapper = mock(RoamHangoutMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        Map<String,Object> row = new HashMap<>(Map.of("id", 7L, "name", "公开活动", "viewerHasTicket", 1,
                "joined", true, "ownerPhone", "private", "progress", 99));
        when(mapper.selectNearbyActivities(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyInt(),eq(0L)))
                .thenAnswer(invocation -> new ArrayList<>(List.of(new HashMap<>(row))));
        RoamHangoutServiceImpl service = new RoamHangoutServiceImpl();
        ReflectionTestUtils.setField(service,"roamHangoutMapper",mapper);
        ReflectionTestUtils.setField(service,"cmsTopicMapper",topics);
        ApiRoamController entry = new ApiRoamController();
        ReflectionTestUtils.setField(entry,"roamHangoutService",service);
        ApiRoamHangoutController nearby = new ApiRoamHangoutController();
        ReflectionTestUtils.setField(nearby,"roamHangoutService",service);
        for (Object controller : List.of(entry,nearby)) {
            String route = controller == entry ? "/api/roam/entry/nearby" : "/api/roam/hangout/nearby";
            mvc(controller).perform(get(route).param("lat","31.2").param("lng","121.4"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.items[0].name").value("公开活动"))
                    .andExpect(jsonPath("$.data.items[0].keys()",org.hamcrest.Matchers.containsInAnyOrder("id","name","kind")));
        }
        verify(mapper,times(2)).selectNearbyActivities(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyInt(),eq(0L));
    }

    @Test void publicTeamsDropViewerStateAndStillUsePublishedTeamQuery() throws Exception {
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        PlayTeamNearbyVO team = new PlayTeamNearbyVO();
        team.setTeamId(8L); team.setViewerStatus("JOINED"); team.setViewerHasTicket(true); team.setPendingCount(4);
        team.setApplyExpireTime(new Date());
        when(topics.selectNearbyPublicTeams(eq(0L),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyInt(),any(),anyInt()))
                .thenReturn(List.of(team));
        PlayTeamServiceImpl service = new PlayTeamServiceImpl();
        ReflectionTestUtils.setField(service,"cmsTopicMapper",topics);
        ApiPlayTeamController controller = new ApiPlayTeamController();
        ReflectionTestUtils.setField(controller,"playTeamService",service);
        mvc(controller).perform(get("/api/team/nearby").param("lat","31.2").param("lng","121.4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].teamId").value(8))
                .andExpect(jsonPath("$.data[0].viewerStatus").doesNotExist())
                .andExpect(jsonPath("$.data[0].viewerHasTicket").doesNotExist())
                .andExpect(jsonPath("$.data[0].applyExpireTime").doesNotExist())
                .andExpect(jsonPath("$.data[0].pendingCount").doesNotExist());
        verify(topics).selectNearbyPublicTeams(eq(0L),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyInt(),any(),anyInt());
        assertThrows(IllegalArgumentException.class, () -> service.nearby(0L,Double.NaN,121,1000));
    }

    @Test void poiAndMerchantCardsKeepPublicContentWithoutProgressOrEntitlements() throws Exception {
        RoamMapper mapper = mock(RoamMapper.class);
        RoamPoi poi = new RoamPoi(); poi.setId(9L); poi.setName("公开公园"); poi.setFound(true);
        when(mapper.selectPoisNear(anyDouble(),anyDouble(),anyDouble(),anyDouble(),eq(0L),anyInt())).thenReturn(List.of(poi));
        ApiRoamController roam = new ApiRoamController(); ReflectionTestUtils.setField(roam,"roamMapper",mapper);
        mvc(roam).perform(get("/api/roam/pois").param("lat","31.2").param("lng","121.4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("公开公园"))
                .andExpect(jsonPath("$.data[0].found").doesNotExist())
                .andExpect(jsonPath("$.data[0].pendingRedeem").doesNotExist())
                .andExpect(jsonPath("$.data[0].couponTemplateId").doesNotExist());
        CityNodeVO node = new CityNodeVO(); node.setPoiId(10L); node.setNeedRedeem(true); node.setCompleted(true); node.setFavorited(true);
        when(mapper.selectMerchantNodesNear(anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),anyDouble(),any(),any(),any(),any(),anyInt()))
                .thenReturn(List.of(node));
        IMerchantNodeDiscoveryService discovery = mock(IMerchantNodeDiscoveryService.class);
        when(discovery.findActiveNode(2L)).thenReturn(node);
        ApiCityNodeController city = new ApiCityNodeController();
        ReflectionTestUtils.setField(city,"roamMapper",mapper); ReflectionTestUtils.setField(city,"merchantNodeDiscoveryService",discovery);
        mvc(city).perform(get("/api/city/nodes").param("lat","31.2").param("lng","121.4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].poiId").value(10))
                .andExpect(jsonPath("$.data[0].needRedeem").doesNotExist()).andExpect(jsonPath("$.data[0].completed").doesNotExist());
        mvc(city).perform(get("/api/city/nodes/merchant/2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.needRedeem").doesNotExist()).andExpect(jsonPath("$.data.completed").doesNotExist());
    }

    @Test void publicLeaderboardNeverQueriesMyRankOrSerializesMemberIds() throws Exception {
        MemberLeaderboardMapper mapper = mock(MemberLeaderboardMapper.class);
        LeaderboardItemVO row = new LeaderboardItemVO(); row.setMemberId(123L); row.setNickname("探索者"); row.setScore(50L);
        when(mapper.selectPointTotalTop(anyInt())).thenReturn(List.of(row));
        GrowthServiceImpl service = new GrowthServiceImpl(); ReflectionTestUtils.setField(service,"memberLeaderboardMapper",mapper);
        ApiGrowthController controller = new ApiGrowthController(); ReflectionTestUtils.setField(controller,"growthService",service);
        mvc(controller).perform(get("/api/growth/leaderboard"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.list[0].rank").value(1))
                .andExpect(jsonPath("$.data.list[0].nickname").value("探索者"))
                .andExpect(jsonPath("$.data.list[0].memberId").doesNotExist())
                .andExpect(jsonPath("$.data.me").doesNotExist());
        verify(mapper).selectPointTotalTop(anyInt()); verifyNoMoreInteractions(mapper);
    }

    @Test void remainingPublicCatalogControllersAcceptNoSession() throws Exception {
        IMarketingProductCatalogService catalog = mock(IMarketingProductCatalogService.class);
        ApiMarketingProductController products = new ApiMarketingProductController(catalog,null,null);
        com.chengyinhub.business.domain.PassBundleView product = new com.chengyinhub.business.domain.PassBundleView();
        product.setProductId(3L); product.setProductName("通票");
        when(catalog.listSellable(null,null,20)).thenReturn(List.of(product));
        when(catalog.detail(3L)).thenReturn(product);
        for (String route : List.of("/api/marketing/product/list","/api/marketing/product/3"))
            mvc(products).perform(get(route)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        PlatformPassService passes = mock(PlatformPassService.class);
        when(passes.overview(null)).thenReturn(Map.of("enabled",true,"plans",List.of()));
        mvc(new ApiPlatformPassController(passes)).perform(get("/api/platform-pass"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.holding").doesNotExist());
        verify(passes).overview(null);
        IOfficialEventService events = mock(IOfficialEventService.class);
        when(events.activeForRoam(null,0L)).thenReturn(List.of(Map.of("id",7L,"title","官方漫游")));
        ApiOfficialEventController official = new ApiOfficialEventController(); ReflectionTestUtils.setField(official,"officialEventService",events);
        mvc(official).perform(get("/api/official/active-for-roam"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data[0].signed").doesNotExist());
        ApiAiNpcController npc = new ApiAiNpcController(); NpcProfileService profiles = mock(NpcProfileService.class);
        ReflectionTestUtils.setField(npc,"npcProfileService",profiles);
        when(profiles.resolveGlobal()).thenReturn(null);
        mvc(npc).perform(get("/api/ai/npc/profile").param("scope","global"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        ApiMediaAssetController media = new ApiMediaAssetController(null); AppMediaReferenceGuard refs = mock(AppMediaReferenceGuard.class);
        ReflectionTestUtils.setField(media,"references",refs); when(refs.publicAttribution("public.jpg")).thenReturn(Map.of("credit","作者"));
        mvc(media).perform(get("/api/media/asset/attribution").param("reference","public.jpg"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        CommunityTrustService trust = mock(CommunityTrustService.class); ApiCommunityTrustController safety = new ApiCommunityTrustController();
        ReflectionTestUtils.setField(safety,"communityTrustService",trust);
        mvc(safety).perform(get("/api/community-trust/safety/merchants/2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        ApiRoamController roam = new ApiRoamController(); IRoamExploreDayCandidateService explore = mock(IRoamExploreDayCandidateService.class);
        ReflectionTestUtils.setField(roam,"roamExploreDayCandidateService",explore);
        mvc(roam).perform(get("/api/roam/nearby-exploreday").param("lat","31.2").param("lng","121.4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test void guestSearchUsesPublicProjectionAndHonorsFeatureFlagAndValidation() throws Exception {
        ContentSearchService service = mock(ContentSearchService.class);
        ContentSearchItemVO row = new ContentSearchItemVO();
        row.setId(7L); row.setType("post"); row.setTitle("公开内容");
        when(service.search("城市", "topic,activity,post", null, 0L)).thenReturn(List.of(row));
        ApiContentSearchController controller = new ApiContentSearchController();
        ReflectionTestUtils.setField(controller, "contentSearchService", service);
        ReflectionTestUtils.setField(controller, "unifiedSearchEnabled", true);
        mvc(controller).perform(get("/api/search/unified").param("keyword", "城市").param("types", "topic,activity,post"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.rows[0].title").value("公开内容"))
                .andExpect(jsonPath("$.data.rows[0].memberId").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].joined").doesNotExist());
        verify(service).search("城市", "topic,activity,post", null, 0L);
        mvc(controller).perform(get("/api/search/unified").param("keyword", "城市").param("types", "private"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(400));
        ReflectionTestUtils.setField(controller, "unifiedSearchEnabled", false);
        mvc(controller).perform(get("/api/search/unified").param("keyword", "城市"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(503));
    }

    @Test void everyNewRouteRejectsOtherMethodsAndPrivateAndWriteSiblings() throws Exception {
        MockMvc security = mvc(new ApiGrowthController());
        List<String> publicRoutes = List.of("/api/roam/entry/nearby","/api/roam/hangout/nearby","/api/team/nearby",
                "/api/roam/pois","/api/roam/nearby-exploreday","/api/city/nodes","/api/city/nodes/merchant/2",
                "/api/marketing/product/list","/api/marketing/product/3","/api/platform-pass","/api/ai/npc/profile",
                "/api/media/asset/attribution","/api/community-trust/safety/merchants/2","/api/official/active-for-roam","/api/growth/leaderboard","/api/search/unified");
        for (String route : publicRoutes) {
            for (org.springframework.http.HttpMethod method : List.of(org.springframework.http.HttpMethod.POST,
                    org.springframework.http.HttpMethod.PUT,org.springframework.http.HttpMethod.PATCH,org.springframework.http.HttpMethod.DELETE))
                security.perform(request(method,route)).andExpect(status().isUnauthorized());
            security.perform(get(route+"/extra")).andExpect(status().isUnauthorized());
        }
        for (String route : List.of("/api/city/nodes/favorites","/api/marketing/product/orders","/api/platform-pass/orders",
                "/api/official/my-events","/api/roam/summary","/api/media/asset/list","/api/ai/npc/event",
                "/api/team/join","/api/roam/finish","/api/roam/reveal","/api/city/nodes/2/favorite",
                "/api/marketing/product/quote","/api/marketing/product/order","/api/growth/mission/complete")) {
            security.perform(get(route)).andExpect(status().isUnauthorized());
            security.perform(post(route)).andExpect(status().isUnauthorized());
        }
    }
}
