package com.chengyinhub.upgrade;

import com.chengyinhub.framework.security.matcher.LegacyAntRequestMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contracts inherited from the Security 5 case-sensitive antMatchers whitelist. */
class LegacyAntRequestMatcherTest {

    @ParameterizedTest
    @CsvSource({
            "/*.html, /index.html",
            "/**/*.html, /index.html",
            "/**/*.html, /docs/index.html",
            "/**/*.html, /docs/nested/index.html",
            "/**/*.css, /styles.css",
            "/**/*.css, /assets/theme/styles.css",
            "/**/*.js, /app.js",
            "/**/*.js, /assets/chunks/app.js",
            "/profile/**, /profile",
            "/profile/**, /profile/",
            "/profile/**, /profile/images/avatar.png",
            "/api/official/events/*, /api/official/events/42",
            "/*/api-docs, /v2/api-docs"
    })
    void preservesAntPatternsIncludingMidPatternDoubleStar(String pattern, String path) {
        assertTrue(new LegacyAntRequestMatcher(pattern, HttpMethod.GET).matches(request("GET", path)));
    }

    @ParameterizedTest
    @CsvSource({
            "/*.html, /docs/index.html",
            "/**/*.html, /docs/index.html/extra",
            "/**/*.html, /docs/index.html.bak",
            "/**/*.html, /docs/index.HTML",
            "/**/*.css, /assets/styles.css.map",
            "/**/*.js, /assets/app.json",
            "/profile/**, /profiles/avatar.png",
            "/profile/**, /Profile/avatar.png",
            "/api/official/events/*, /api/official/events/42/signup",
            "/api/official/events/*, /api/official/events/42/complete",
            "/*/api-docs, /a/b/api-docs",
            "/api/template/list, /api/template/list/extra",
            "/api/template/list, /api/template/listing",
            "/api/template/list, /api/template/list/",
            "/api/template/list, /API/template/list",
            "/api/template/list, /api/template/List"
    })
    void doesNotBroadenSuffixPrefixCaseOrSingleSegmentBoundaries(String pattern, String path) {
        assertFalse(new LegacyAntRequestMatcher(pattern, HttpMethod.GET).matches(request("GET", path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void getOnlyRuleDoesNotAuthorizeOtherMethodsIncludingImplicitMvcHead(String method) {
        assertFalse(new LegacyAntRequestMatcher("/api/official/events/*", HttpMethod.GET)
                .matches(request(method, "/api/official/events/42")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
    void unrestrictedMethodRulePreservesLegacyAllMethodsBehavior(String method) {
        assertTrue(new LegacyAntRequestMatcher("/api/login", null).matches(request(method, "/api/login")));
    }

    @Test
    void contextPathAndQueryStringAreExcludedFromTheMatchedPath() {
        MockHttpServletRequest request = request("GET", "/api/official/events/42");
        request.setContextPath("/chengyin");
        request.setRequestURI("/chengyin/api/official/events/42");
        request.setQueryString("redirect=/private&format=.html");

        assertTrue(new LegacyAntRequestMatcher("/api/official/events/*", HttpMethod.GET).matches(request));
        assertFalse(new LegacyAntRequestMatcher("/chengyin/api/official/events/*", HttpMethod.GET).matches(request));
        assertFalse(new LegacyAntRequestMatcher("/**/*.html", HttpMethod.GET).matches(request));
    }

    @Test
    void concatenatesServletPathAndPathInfoWithoutFallingBackToRequestUri() {
        MockHttpServletRequest request = request("GET", "/api/official");
        request.setPathInfo("/events/42");
        request.setRequestURI("/context/api/official/events/42");

        assertTrue(new LegacyAntRequestMatcher("/api/official/events/*", HttpMethod.GET).matches(request));
        assertFalse(new LegacyAntRequestMatcher("/api/official", HttpMethod.GET).matches(request));
        request.setPathInfo("/events/42/signup");
        assertFalse(new LegacyAntRequestMatcher("/api/official/events/*", HttpMethod.GET).matches(request));
    }

    @Test
    void acceptsPathInfoOnlyServletMappingAndDoesNotTrimPathSegments() {
        MockHttpServletRequest request = request("POST", "");
        request.setPathInfo("/api/template/list");
        assertTrue(new LegacyAntRequestMatcher("/api/template/list", HttpMethod.POST).matches(request));

        request.setPathInfo("/api/template/ list");
        assertFalse(new LegacyAntRequestMatcher("/api/template/list", HttpMethod.POST).matches(request));
    }

    @Test
    void doesNotAuthorizeARequestUriWhenItsServletPathIsDifferent() {
        MockHttpServletRequest request = request("POST", "/api/template/publish");
        request.setRequestURI("/api/template/list");
        assertFalse(new LegacyAntRequestMatcher("/api/template/list", HttpMethod.POST).matches(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/**", "**"})
    void universalPatternsStillHonorTheMethodRestriction(String pattern) {
        assertTrue(new LegacyAntRequestMatcher(pattern, HttpMethod.GET).matches(request("GET", "")));
        assertTrue(new LegacyAntRequestMatcher(pattern, HttpMethod.GET).matches(request("GET", "/private/nested")));
        assertFalse(new LegacyAntRequestMatcher(pattern, HttpMethod.GET).matches(request("POST", "/private/nested")));
    }

    @Test
    void rejectsMissingAndBlankPatterns() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyAntRequestMatcher(null, null));
        assertThrows(IllegalArgumentException.class, () -> new LegacyAntRequestMatcher("", null));
        assertThrows(IllegalArgumentException.class, () -> new LegacyAntRequestMatcher("  ", null));
    }

    private static MockHttpServletRequest request(String method, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setServletPath(servletPath);
        return request;
    }
}
