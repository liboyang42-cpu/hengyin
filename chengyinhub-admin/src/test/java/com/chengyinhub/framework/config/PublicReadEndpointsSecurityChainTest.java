package com.chengyinhub.framework.config;

import com.chengyinhub.business.domain.vo.CatalogPlaceVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IMerchantReviewService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.config.properties.PermitAllUrlProperties;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.security.handle.AuthenticationEntryPointImpl;
import com.chengyinhub.framework.security.handle.LogoutSuccessHandlerImpl;
import com.chengyinhub.framework.web.service.AppTokenService;
import com.chengyinhub.framework.web.service.TokenService;
import com.chengyinhub.web.controller.api.ApiCityNodeController;
import com.chengyinhub.web.controller.api.ApiMerchantReviewController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import jakarta.servlet.Filter;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开读接口的放行边界:只允许精确的公开读路由,同前缀的其它接口一条都不许被顺带放行。
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@TestPropertySource(properties = {
        "token.header=Authorization",
        "token.secret=unit-test-secret-unit-test-secret-unit-test-secret",
        "token.expireTime=21600",
        "token.app.idleTime=1440",
        "token.app.absoluteTime=10080"
})
@ContextConfiguration(classes = {
        PublicReadEndpointsSecurityChainTest.TestConfiguration.class,
        SecurityConfig.class,
        PermitAllUrlProperties.class,
        JwtAuthenticationTokenFilter.class,
        AuthenticationEntryPointImpl.class
})
class PublicReadEndpointsSecurityChainTest {

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters((Filter) applicationContext.getBean("springSecurityFilterChain"))
                .build();
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void newPublicReadEndpointsAreReachableAnonymously() throws Exception {
        mockMvc.perform(post("/api/merchant/public-home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/user/public-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/analytics/events/batch"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/analytics/admin"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadEndpointsIncludingTrailingSlashRequireAuthentication() throws Exception {
        for (String route : new String[]{"/api/common/upload", "/api/common/upload/",
                "/api/common/uploads", "/api/common/uploads/",
                "/api/common/uploadOSS", "/api/common/uploadOSS/"}) {
            mockMvc.perform(post(route))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Test
    void officialEventsAreAnonymousOnlyForGet() throws Exception {
        for (String route : new String[]{"/api/official/events", "/api/official/events/123"}) {
            mockMvc.perform(get(route)).andExpect(status().isOk());
            mockMvc.perform(post(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        mockMvc.perform(post("/api/official/events/123/signup"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cityNodeListAndAllWritesRequireAuthenticationButSingleNodeGetIsPublic() throws Exception {
        // 第九轮 m4(owner 2026-11-04):只开 GET /api/city/nodes/{数字 id};列表、写和本人接口照旧 401。
        mockMvc.perform(get("/api/city/nodes/123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/api/city/nodes")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        for (String route : new String[]{"/api/city/nodes", "/api/city/nodes/123"}) {
            mockMvc.perform(post(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        for (String route : new String[]{"/api/city/nodes/favorites", "/api/city/nodes/merchant/5",
                "/api/city/nodes/123/complete", "/api/city/nodes/123/favorite", "/api/city/nodes/12a"}) {
            mockMvc.perform(get(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
            mockMvc.perform(post(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Test
    void catalogPlaceGetIsAnonymousAndReturnsOnlyPublicFields() throws Exception {
        RoamMapper mapper = mock(RoamMapper.class);
        CatalogPlaceVO place = new CatalogPlaceVO();
        place.setPoiId(900L);
        place.setName("目录公园");
        place.setLat(new java.math.BigDecimal("31.27"));
        place.setLng(new java.math.BigDecimal("121.48"));
        place.setCoverImg("cover.jpg");
        place.setGalleryJson("[\"photo.jpg\"]");
        place.setQuotesJson("[{\"text\":\"引述\"}]");
        place.setPhotoCredit("CC BY");
        place.setBadgeCode("PARK");
        when(mapper.selectCatalogPlaceById(900L)).thenReturn(place);

        catalogPlaceMockMvc(mapper).perform(get("/api/city/places/900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.poiId").value(900))
                .andExpect(jsonPath("$.data.lat").value(31.27))
                .andExpect(jsonPath("$.data.lng").value(121.48))
                .andExpect(jsonPath("$.data.coverImg").value("cover.jpg"))
                .andExpect(jsonPath("$.data.galleryJson").value("[\"photo.jpg\"]"))
                .andExpect(jsonPath("$.data.quotesJson").value("[{\"text\":\"引述\"}]"))
                .andExpect(jsonPath("$.data.photoCredit").value("CC BY"))
                .andExpect(jsonPath("$.data.badgeCode").value("PARK"))
                .andExpect(jsonPath("$.data.keys()", org.hamcrest.Matchers.containsInAnyOrder(
                        "poiId", "name", "address", "description", "lat", "lng", "radiusM",
                        "cityCode", "nodeLevel", "tags", "coverImg", "galleryJson", "quotesJson",
                        "photoCredit", "badgeCode")));
        verify(mapper).selectCatalogPlaceById(900L);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void unavailableCatalogPlacesReturnGoneAndOtherMethodsAndPathsRequireAuthentication() throws Exception {
        RoamMapper mapper = mock(RoamMapper.class);
        MockMvc catalogMvc = catalogPlaceMockMvc(mapper);
        // Mapper returns null for non-catalog/offline/missing rows; real SQL coverage is in ApiCityNodeSearchTest.
        for (long id : new long[]{901, 902, 999}) {
            catalogMvc.perform(get("/api/city/places/" + id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(410))
                    .andExpect(jsonPath("$.data").doesNotExist());
            verify(mapper).selectCatalogPlaceById(id);
        }
        for (org.springframework.http.HttpMethod method : new org.springframework.http.HttpMethod[]{
                org.springframework.http.HttpMethod.POST, org.springframework.http.HttpMethod.PUT,
                org.springframework.http.HttpMethod.PATCH, org.springframework.http.HttpMethod.DELETE,
                org.springframework.http.HttpMethod.HEAD, org.springframework.http.HttpMethod.OPTIONS}) {
            catalogMvc.perform(request(method, "/api/city/places/900"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        for (String route : new String[]{"/api/city/places", "/api/city/places/12a",
                "/api/city/places/-1", "/api/city/places/900/extra", "/api/city/places/900/",
                "/api/city/places/900/complete", "/api/city/places/900/favorite"}) {
            catalogMvc.perform(get(route)).andExpect(status().isUnauthorized());
            catalogMvc.perform(post(route)).andExpect(status().isUnauthorized());
        }
        verifyNoMoreInteractions(mapper);
    }

    private MockMvc catalogPlaceMockMvc(RoamMapper mapper) {
        ApiCityNodeController controller = new ApiCityNodeController();
        ReflectionTestUtils.setField(controller, "roamMapper", mapper);
        return MockMvcBuilders.standaloneSetup(controller)
                .addFilters((Filter) applicationContext.getBean("springSecurityFilterChain"))
                .build();
    }

    @Test
    void officialRouteListAndInfoAreAnonymousOnlyForGetAndSiblingsAreNot() throws Exception {
        for (String route : new String[]{"/api/preset/route/list", "/api/preset/route/info"}) {
            mockMvc.perform(get(route)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
            mockMvc.perform(post(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        for (String route : new String[]{"/api/preset/style/list", "/api/preset/route/info/extra",
                "/api/preset/route/listing", "/api/preset/route"}) {
            mockMvc.perform(get(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Test
    void merchantReviewPublicListIsAnonymousButManagementSiblingIsNot() throws Exception {
        mockMvc.perform(post("/api/merchant/reviews/public")
                        .param("merchantRowId", "900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/merchant/reviews/manage"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void publicTemplateShelfReadEndpointsAreReachableAnonymously() throws Exception {
        mockMvc.perform(post("/api/template/homeData"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/template/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/template/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/template/topic-template/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/template/topic-template/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/category/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void templateMutationsAndPrivateReadsStillRequireAuthentication() throws Exception {
        // 负控:一旦误写成 /api/template/**,以下至少一条会从 401 变成 200。
        mockMvc.perform(post("/api/template/publish"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/draft"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/my-list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/myinfo"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/groupByCategory"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/topic-template/use"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/template/info/extra"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/category/update"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roamShareSnapshotReadIsAnonymousButWritesAndRunSessionsAreNot() throws Exception {
        // 第二轮拍板 17:接收方未登录也能看足迹快照;只放行精确 GET 读取这一条。
        mockMvc.perform(get("/api/roam/share/snapshot").param("token", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 负控:同路径 POST 是发布快照,必须登录;写成 /api/roam/share/** 或不限方法时会变 200。
        mockMvc.perform(post("/api/roam/share/snapshot"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/roam/share/revoke"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/play/run-session/list"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mapWeatherIsAnonymousOnlyForExactGetAndMapSiblingsAreNot() throws Exception {
        mockMvc.perform(get("/api/map/weather").param("lat", "30.27").param("lng", "120.15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // 负控:写成 /api/map/** 或不限方法时以下会变 200。
        mockMvc.perform(post("/api/map/weather")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/map/weather/extra")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/map/reverse-geocode")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/map/nearby")).andExpect(status().isUnauthorized());
    }

    @Test
    void legacyUserInfoAndSiblingsStillRequireAuthentication() throws Exception {
        // 负控:放行必须精确到路径。写成 /api/user/** 或 /api/merchant/** 时这几条会变 200。
        mockMvc.perform(post("/api/user/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/user/update"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/merchant/info"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/merchant/decor/save"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        // 前缀相近但不是放行项,不能被通配吃进去
        mockMvc.perform(post("/api/user/public-info/extra"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/merchant/public-home/extra"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void guestBrowseReadsAreAnonymousButTheirWriteAndPersonalSiblingsAreNot() throws Exception {
        // owner 2026-10-24「游客可浏览，写入时弹窗登录」:四条公开读走 @Anonymous(同 GuestBrowseProbeController),
        // 真实控制器上的注解范围由 GuestBrowseAnonymousReadTest 逐方法钉住。
        for (String route : new String[]{"/api/topic/info-to-user", "/api/club/detail", "/api/club/list",
                "/api/club/topics", "/api/club/post/list", "/api/club/members", "/api/club/leaderboard",
                "/api/creativesquare/info"}) {
            mockMvc.perform(post(route)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        }
        // 负控:写兄弟与本人数据接口照旧 401;放行不能顺着前缀吃进去。
        for (String route : new String[]{"/api/club/join", "/api/club/quit", "/api/club/home",
                "/api/club/post/create", "/api/club/post/like", "/api/topic/like", "/api/topic/info-to-merchant",
                "/api/creativesquare/like", "/api/creativesquare/action", "/api/comment/add",
                "/api/registration/create", "/api/club/detail/extra", "/api/topic/info-to-user/extra",
                "/api/club/post/comment/list", "/api/club/post/update", "/api/club/post/delete",
                "/api/club/member/remove", "/api/club/topic-registrations",
                "/api/club/members/extra", "/api/club/topics/extra", "/api/club/post/list/extra",
                "/api/club/leaderboard/extra"}) {
            mockMvc.perform(post(route)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class TestConfiguration {

        @Bean
        GuestBrowseProbeController guestBrowseProbeController() {
            return new GuestBrowseProbeController();
        }

        @Bean
        RedisCache redisCache() {
            return mock(RedisCache.class);
        }

        @Bean
        RedisTemplate<Object, Object> redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @Bean
        AppTokenService appTokenService(RedisCache redisCache) {
            AppTokenService service = new AppTokenService();
            ReflectionTestUtils.setField(service, "redisCache", redisCache);
            ReflectionTestUtils.setField(service, "header", "Authorization");
            ReflectionTestUtils.setField(service, "secret",
                    "unit-test-secret-unit-test-secret-unit-test-secret");
            ReflectionTestUtils.setField(service, "idleTime", 24 * 60);
            ReflectionTestUtils.setField(service, "absoluteTime", 7 * 24 * 60);
            return service;
        }

        @Bean
        IAppSessionRegistryService appSessionRegistryService() {
            IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
            when(registry.currentOrCreateEpoch(anyLong())).thenReturn(1L);
            when(registry.isCurrentEpoch(anyLong(), anyLong())).thenReturn(true);
            return registry;
        }

        @Bean
        TokenService tokenService() {
            return mock(TokenService.class);
        }

        @Bean
        com.chengyinhub.system.mapper.SysUserMapper sysUserMapper() {
            return mock(com.chengyinhub.system.mapper.SysUserMapper.class);
        }

        @Bean
        com.chengyinhub.framework.web.service.SysPermissionService sysPermissionService() {
            return mock(com.chengyinhub.framework.web.service.SysPermissionService.class);
        }

        @Bean
        com.chengyinhub.system.service.ISysRoleService sysRoleService() {
            return mock(com.chengyinhub.system.service.ISysRoleService.class);
        }

        @Bean
        com.chengyinhub.system.service.ISysMenuService sysMenuService() {
            return mock(com.chengyinhub.system.service.ISysMenuService.class);
        }

        @Bean
        UserDetailsService userDetailsService() {
            return username -> null;
        }

        @Bean
        LogoutSuccessHandlerImpl logoutSuccessHandler() {
            return mock(LogoutSuccessHandlerImpl.class);
        }

        @Bean
        CorsFilter corsFilter() {
            return new CorsFilter(new UrlBasedCorsConfigurationSource());
        }

        @Bean
        RouteProbeController routeProbeController() {
            return new RouteProbeController();
        }

        @Bean
        IMerchantReviewService merchantReviewService() {
            return mock(IMerchantReviewService.class);
        }

        @Bean
        ApiMerchantReviewController apiMerchantReviewController() {
            return new ApiMerchantReviewController();
        }
    }

    /** 游客浏览四条公开读 + 它们的写/本人兄弟:注解形态与真实控制器一致(方法级 @Anonymous)。 */
    @RestController
    static class GuestBrowseProbeController {
        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/topic/info-to-user")
        AjaxResult topicInfo() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/detail")
        AjaxResult clubDetail() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/list")
        AjaxResult clubList() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/creativesquare/info")
        AjaxResult squareInfo() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/topics")
        AjaxResult clubTopics() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/post/list")
        AjaxResult clubPosts() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/members")
        AjaxResult clubMembers() { return AjaxResult.success(); }

        @com.chengyinhub.common.annotation.Anonymous
        @PostMapping("/api/club/leaderboard")
        AjaxResult clubLeaderboard() { return AjaxResult.success(); }

        @PostMapping({"/api/club/join", "/api/club/quit", "/api/club/home",
                "/api/club/post/create", "/api/club/post/like", "/api/topic/like", "/api/topic/info-to-merchant",
                "/api/creativesquare/like", "/api/creativesquare/action", "/api/comment/add",
                "/api/registration/create", "/api/club/detail/extra", "/api/topic/info-to-user/extra",
                "/api/club/post/comment/list", "/api/club/post/update", "/api/club/post/delete",
                "/api/club/member/remove", "/api/club/topic-registrations",
                "/api/club/members/extra", "/api/club/topics/extra", "/api/club/post/list/extra",
                "/api/club/leaderboard/extra"})
        AjaxResult siblings() { return AjaxResult.success(); }
    }

    /** 只探针路由放行与否,不跑真实业务逻辑。 */
    @RestController
    static class RouteProbeController {
        @PostMapping({"/api/merchant/public-home", "/api/user/public-info",
                "/api/user/info", "/api/user/update", "/api/merchant/info",
                "/api/merchant/decor/save", "/api/user/public-info/extra",
                "/api/merchant/public-home/extra", "/api/template/homeData",
                "/api/template/list", "/api/template/info", "/api/category/list",
                "/api/template/topic-template/list", "/api/template/topic-template/info",
                "/api/template/publish", "/api/template/draft", "/api/template/my-list",
                "/api/template/myinfo", "/api/template/groupByCategory",
                "/api/template/topic-template/use",
                "/api/template/info/extra", "/api/category/update",
                "/api/analytics/events/batch", "/api/analytics/admin"})
        AjaxResult probe() {
            return AjaxResult.success();
        }

        @org.springframework.web.bind.annotation.RequestMapping({"/api/roam/share/snapshot",
                "/api/roam/share/revoke", "/api/play/run-session/list",
                "/api/official/events", "/api/official/events/123", "/api/official/events/123/signup",
                "/api/city/nodes", "/api/city/nodes/123",
                "/api/city/nodes/favorites", "/api/city/nodes/merchant/5", "/api/city/nodes/123/complete",
                "/api/city/nodes/123/favorite", "/api/city/nodes/12a",
                "/api/preset/route/list", "/api/preset/route/info", "/api/preset/style/list",
                "/api/preset/route/info/extra", "/api/preset/route/listing", "/api/preset/route",
                "/api/map/weather", "/api/map/weather/extra", "/api/map/reverse-geocode", "/api/map/nearby"})
        AjaxResult probeAnyMethod() {
            return AjaxResult.success();
        }
    }
}
