package com.chengyinhub.business.service.merchantauthoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.merchantgap.MerchantGapH2;
import com.chengyinhub.business.merchantgap.MerchantGapSupport;
import com.chengyinhub.business.service.support.TemplateAuditSnapshot;

/** authoring/draft-selection/{list,resolve}. */
class MerchantDraftSelectionServiceTest {
    static final long OWNER = 9002L, STAFF = 9006L, STORE = 8001L;
    static final String HASH = "a".repeat(64);

    MerchantGapH2 db;
    CmsMemberTemplateMapper templates;
    MerchantDraftSelectionService service;

    @BeforeEach
    void setUp() throws Exception {
        db = MerchantGapH2.create();
        IMerchantAccessService access = mock(IMerchantAccessService.class);
        when(access.require(eq(OWNER), anyString())).thenReturn(new MerchantAccessContext(OWNER, STORE, OWNER, MerchantRoleCode.MERCHANT_OWNER));
        when(access.require(eq(STAFF), anyString())).thenReturn(new MerchantAccessContext(STAFF, STORE, OWNER, "MERCHANT_MANAGER"));
        templates = mock(CmsMemberTemplateMapper.class);
        service = new MerchantDraftSelectionService(access, templates, db.jdbc);
        for (long id = 1; id <= 21; id++) {
            db.jdbc.update("INSERT INTO merchant_authoring_source(id, candidate_id, owner_member_id, merchant_row_id, actual_template_id, template_snapshot_json, template_content_hash, confirmation_hash, content_hash, source_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    id, id, OWNER, STORE, 500 + id, "{}", HASH, HASH, HASH, "{}", new Timestamp(0));
            CmsMemberTemplate t = new CmsMemberTemplate();
            t.setId(500 + id);
            t.setMemberId(OWNER);
            t.setTitle("草稿" + id);
            t.setDelFlag(0);
            when(templates.selectCmsMemberTemplateById(500 + id)).thenReturn(id == 3 ? null : t);
        }
    }

    static Map<String, Object> page(Long before) {
        Map<String, Object> body = new HashMap<>();
        body.put("beforeSourceId", before);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listPagesDescendingWithLookaheadCursorAndUnavailableRows() {
        Map<String, Object> first = service.list(OWNER, page(null));
        assertEquals("MERCHANT_AUTHORING_DRAFT_SELECTION_PAGE_V1", first.get("profile"));
        assertEquals(OWNER, first.get("ownerMemberId"));
        assertEquals(false, first.get("publicationAuthority"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) first.get("rows");
        assertEquals(20, rows.size());
        assertEquals(21L, rows.get(0).get("sourceId"));
        assertEquals(2L, first.get("nextBeforeSourceId"));
        Map<String, Object> second = service.list(OWNER, page(2L));
        List<Map<String, Object>> rest = (List<Map<String, Object>>) second.get("rows");
        assertEquals(1, rest.size());
        assertNull(second.get("nextBeforeSourceId"));
        Map<String, Object> unavailable = rows.stream().filter(r -> Long.valueOf(3).equals(r.get("sourceId"))).findFirst().orElseThrow();
        assertEquals(Map.of("sourceId", 3L, "memberTemplateId", 503L, "state", "UNAVAILABLE"), unavailable);
        assertEquals(403, assertThrows(MerchantGapException.class, () -> service.list(STAFF, page(null))).getStatus());
        assertEquals(400, assertThrows(MerchantGapException.class, () -> service.list(OWNER, Map.of())).getStatus());
    }

    Map<String, Object> resolveBody(long sourceId, long templateId, String templateHash) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", MerchantDraftSelectionService.SOURCE_KIND);
        source.put("sourceId", sourceId);
        source.put("sourceVersion", 1);
        source.put("contentHash", HASH);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", source);
        body.put("memberTemplateId", templateId);
        body.put("templateContentHash", templateHash);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveRechecksTheCurrentTemplateHash() {
        CmsMemberTemplate t = templates.selectCmsMemberTemplateById(507L);
        String current = MerchantGapSupport.sha256(TemplateAuditSnapshot.personal(t));
        Map<String, Object> resolved = service.resolve(OWNER, resolveBody(7, 507, current));
        assertEquals("CURRENT_AT_READ_ONLY", resolved.get("currentness"));
        assertEquals("草稿7", ((Map<String, Object>) resolved.get("row")).get("templateTitle"));
        assertEquals(409, assertThrows(MerchantGapException.class, () -> service.resolve(OWNER, resolveBody(7, 507, "b".repeat(64)))).getStatus());
        assertEquals(409, assertThrows(MerchantGapException.class, () -> service.resolve(OWNER, resolveBody(3, 503, current))).getStatus());
        assertEquals(403, assertThrows(MerchantGapException.class, () -> service.resolve(OWNER, resolveBody(99, 507, current))).getStatus());
    }
}
