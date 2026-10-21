package com.chengyinhub.web.controller.business;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 认领入口与列表同权限(business:audit:list),POST /business/audit/{id}/claim。 */
class AuditWorkbenchClaimEndpointTest {

    @Test
    void claimIsPostWithListPermission() throws Exception {
        Method claim = AuditWorkbenchController.class.getMethod("claim", Long.class);
        Method list = AuditWorkbenchController.class.getMethod("list", com.chengyinhub.business.domain.AuditTask.class);
        assertArrayEquals(new String[]{"/{id}/claim"}, claim.getAnnotation(PostMapping.class).value());
        assertEquals(list.getAnnotation(PreAuthorize.class).value(), claim.getAnnotation(PreAuthorize.class).value());
        assertEquals("@ss.hasPermi('business:audit:list')", claim.getAnnotation(PreAuthorize.class).value());
        assertEquals("id", claim.getParameters()[0].getAnnotation(PathVariable.class).value());
    }
}
