package com.chengyinhub.business.appgaps;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkshopOwnedReadTest {
    static final Set<String> META = Set.of("schema", "scope", "availability", "purchasedLibraryStatus", "contentUseStatus", "checkedAt");

    @Test void listIsHonestNotEnabledWithExactKeys() {
        Map<?, ?> d = (Map<?, ?>) new WorkshopOwnedRead().list("").get("data");
        assertEquals(union(META, "items", "hasMore"), d.keySet());
        assertEquals("NOT_ENABLED", d.get("availability")); assertEquals(java.util.List.of(), d.get("items")); assertEquals(false, d.get("hasMore"));
        assertTrue(((String) d.get("checkedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z"));
        assertEquals(400, new WorkshopOwnedRead().list("x=1").get("code"));
    }

    @Test void detailAndPackageCarryNullPayloadWhenNotEnabled() {
        Map<?, ?> d = (Map<?, ?>) new WorkshopOwnedRead().detail("claim-1").get("data");
        assertEquals(union(META, "item"), d.keySet()); assertNull(d.get("item"));
        Map<?, ?> p = (Map<?, ?>) new WorkshopOwnedRead().pkg("claim-1").get("data");
        assertEquals(Set.of("schema", "scope", "claimId", "checkedAt", "availability", "purchasedLibraryStatus", "contentUseStatus", "manifestStatus", "editorStatus", "packageInfo"), p.keySet());
        assertEquals("workshop-package-v1", p.get("schema")); assertNull(p.get("packageInfo"));
        assertEquals(400, new WorkshopOwnedRead().detail("bad id").get("code"));
        assertEquals(400, new WorkshopOwnedRead().pkg(null).get("code"));
    }

    static Set<String> union(Set<String> a, String... more) {
        java.util.HashSet<String> s = new java.util.HashSet<>(a); s.addAll(Set.of(more)); return s;
    }
}
