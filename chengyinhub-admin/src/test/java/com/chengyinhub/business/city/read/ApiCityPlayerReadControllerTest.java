package com.chengyinhub.business.city.read;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.web.controller.api.ApiCityPlayerReadController;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.chengyinhub.business.city.read.CityPlayerReadServiceTest.*;

class ApiCityPlayerReadControllerTest {
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    static LoginAppUser login() {
        AppUser nested = new AppUser(); nested.setUserId(41L);
        LoginAppUser login = new LoginAppUser(41L, nested); login.setSessionEpoch(2L); return login;
    }
    static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }
    static ApiCityPlayerReadController controller(CityPlayerReadRepository repository) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        if (repository != null) beans.registerSingleton("cityReadRepository", repository);
        return new ApiCityPlayerReadController(beans.getBeanProvider(CityPlayerReadRepository.class));
    }
    static MultiValueMap<String, String> currentQuery() {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>(); result.add("regionId", "region-1"); return result;
    }
    static MultiValueMap<String, String> scopedQuery() {
        MultiValueMap<String, String> result = currentQuery(); result.add("boardId", "board-1");
        result.add("seasonId", "season-1"); result.add("rulesReleaseId", "release-1"); result.add("revision", "7"); return result;
    }
    static void status(int expected, ResponseEntity<AjaxResult> response) {
        assertEquals(expected, response.getStatusCodeValue()); assertEquals("no-store", response.getHeaders().getCacheControl());
    }
    @Test void guestAnonymousAdminAndUnauthenticatedPrincipalNeverDispatch() {
        AtomicInteger calls = new AtomicInteger(); ApiCityPlayerReadController c = controller((a,e,r)-> { calls.incrementAndGet(); return ready(); });
        status(401, c.current(currentQuery()));
        authenticate("anonymousUser"); status(401, c.current(currentQuery()));
        authenticate(new LoginUser()); status(401, c.current(currentQuery()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login(), null));
        status(401, c.current(currentQuery())); assertEquals(0, calls.get());
    }
    @Test void incompleteAndMismatchedPrincipalNeverDispatch() {
        AtomicInteger calls = new AtomicInteger(); ApiCityPlayerReadController c = controller((a,e,r)-> { calls.incrementAndGet(); return ready(); });
        authenticate(new LoginAppUser()); status(401, c.current(currentQuery()));
        LoginAppUser mismatch = login(); mismatch.setUserId(42L); authenticate(mismatch); status(401, c.current(currentQuery()));
        LoginAppUser missingEpoch = login(); missingEpoch.setSessionEpoch(null); authenticate(missingEpoch); status(401, c.current(currentQuery()));
        LoginAppUser invalidEpoch = login(); invalidEpoch.setSessionEpoch(-1L); authenticate(invalidEpoch); status(401, c.current(currentQuery()));
        assertEquals(0, calls.get());
    }
    @Test void exactPrincipalMemberAndEpochReachProvider() {
        authenticate(login()); AtomicInteger calls = new AtomicInteger();
        ApiCityPlayerReadController c = controller((actor, epoch, region)-> {
            assertEquals(41L, actor); assertEquals(2L, epoch); assertEquals("region-1", region); calls.incrementAndGet(); return ready(); });
        status(200, c.current(currentQuery())); status(200, c.participation(scopedQuery())); status(200, c.points(scopedQuery()));
        assertEquals(3, calls.get());
    }
    @Test void unknownDuplicateAndMalformedQueriesNeverDispatch() {
        authenticate(login()); AtomicInteger calls = new AtomicInteger();
        ApiCityPlayerReadController c = controller((a,e,r)-> { calls.incrementAndGet(); return ready(); });
        for (String field : Arrays.asList("memberId", "actorId", "ownerId", "participation", "scope", "topicId", "approved")) {
            MultiValueMap<String, String> q = currentQuery(); q.add(field, "41"); status(400, c.current(q));
        }
        MultiValueMap<String, String> duplicate = currentQuery(); duplicate.add("regionId", "region-2"); status(400, c.current(duplicate));
        for (String value : Arrays.asList("-1", "07", "9007199254740992", "1.0", "1e2", "99999999999999999999")) {
            MultiValueMap<String, String> q = scopedQuery(); q.set("revision", value); status(400, c.points(q));
        }
        MultiValueMap<String, String> missing = scopedQuery(); missing.remove("seasonId"); status(400, c.points(missing));
        assertEquals(0, calls.get());
    }
    @Test void missingProviderAndVerifiedEmptyAreDifferent() {
        authenticate(login()); ResponseEntity<AjaxResult> unavailable = controller(null).current(currentQuery());
        status(503, unavailable); assertEquals("CITY_READ_UNAVAILABLE", unavailable.getBody().get("errorCode"));
        CityPlayerReadModel.Snapshot empty = new CityPlayerReadModel.Snapshot(41,2,"region-1",CityPlayerReadModel.Current.NO_CURRENT_BOARD,
                null,CityPlayerReadModel.Membership.UNAVAILABLE,null,CityPlayerReadModel.Points.UNAVAILABLE,Collections.emptyList());
        ResponseEntity<AjaxResult> verified = controller((a,e,r)->empty).current(currentQuery()); status(200, verified);
        assertEquals("NO_CURRENT_BOARD", ((Map<?,?>)verified.getBody().get("data")).get("status"));
    }
    @Test void providerErrorsNeverExposePrivateExceptionMessages() {
        authenticate(login()); ResponseEntity<AjaxResult> result = controller((a,e,r)-> { throw new IllegalStateException("payer-secret"); }).current(currentQuery());
        status(503,result); assertFalse(result.getBody().toString().contains("payer-secret"));
    }
    @Test void signoutDuringReadSuppressesResponse() {
        authenticate(login()); status(401,controller((a,e,r)-> { SecurityContextHolder.clearContext(); return ready(); }).current(currentQuery()));
    }
    @Test void epochAndMemberChangesDuringReadSuppressResponse() {
        LoginAppUser principal=login(); authenticate(principal);
        status(401,controller((a,e,r)-> { principal.setSessionEpoch(3L); return ready(); }).current(currentQuery()));
        LoginAppUser second=login(); authenticate(second);
        status(401,controller((a,e,r)-> { second.getAppUser().setUserId(42L); return ready(); }).current(currentQuery()));
    }
    @Test void replacementAuthenticationSuppressesEvenSameActorResponse() {
        authenticate(login()); status(401,controller((a,e,r)-> { authenticate(login()); return ready(); }).current(currentQuery()));
    }
    @Test void changedSnapshotReturnsConflict() {
        authenticate(login()); MultiValueMap<String,String> q=scopedQuery(); q.set("seasonId","old-season");
        ResponseEntity<AjaxResult> result=controller((a,e,r)->ready()).points(q);status(409,result);
        assertEquals("CITY_SNAPSHOT_CHANGED",result.getBody().get("errorCode"));
    }
    @Test void mvcHasOnlyReadRoutesAndRejectsOwnerInjection() throws Exception {
        authenticate(login()); AtomicInteger calls=new AtomicInteger();
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller((a,e,r)-> {calls.incrementAndGet();return ready();})).build();
        mvc.perform(get("/api/city-game/current").param("regionId","region-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/city-game/current").param("regionId","region-1").param("ownerId","42"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(post("/api/city-game/current").param("regionId","region-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
        mvc.perform(get("/api/city-game/accept"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertEquals(1,calls.get());
    }
    @Test void routeRequiresBothExplicitProfileAndOptInAndMissingProviderRemainsUnavailable() {
        for (boolean profile : new boolean[]{false,true}) for (boolean enabled : new boolean[]{false,true}) {
            try (AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
                if(profile)context.getEnvironment().setActiveProfiles("city-player-read-contract");
                Map<String,Object> properties=new HashMap<>(); properties.put("questify.city-player-read.enabled",Boolean.toString(enabled));
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",properties));
                context.register(ApiCityPlayerReadController.class);context.refresh();
                assertEquals(profile&&enabled?1:0,context.getBeansOfType(ApiCityPlayerReadController.class).size());
                assertEquals(0,context.getBeansOfType(CityPlayerReadRepository.class).size());
                if(profile&&enabled){authenticate(login());status(503,context.getBean(ApiCityPlayerReadController.class).current(currentQuery()));}
            }
        }
    }
}
