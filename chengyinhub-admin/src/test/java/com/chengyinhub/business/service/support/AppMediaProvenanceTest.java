package com.chengyinhub.business.service.support;

import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppMediaProvenanceTest {
    @Test void acceptsOnlyDeclaredTypesAndBoundedPlainFields() {
        for (String type : new String[]{"original", "licensed", "ai-generated", "public-domain", "unknown"})
            assertEquals(type, AppMediaProvenance.normalize(Map.of("sourceType", type)).get("sourceType"));
        for (Map<String,Object> invalid : java.util.List.<Map<String,Object>>of(
                Map.of(), Map.of("sourceType", "trusted"), Map.of("sourceType", 1),
                Map.of("sourceType", "original", "memberId", 8L),
                Map.of("sourceType", "original", "licenseNote", "x".repeat(2001)),
                Map.of("sourceType", "original", "attribution", Map.of("secret", "x")),
                Map.of("sourceType", "original", "aiModel", "x".repeat(101)),
                Map.of("sourceType", "original", "createdAt", "invalid"),
                Map.of("sourceType", "original", "createdAt", "0001-01-01T00:00:00Z")))
            assertThrows(ServiceException.class, () -> AppMediaProvenance.normalize(invalid));
    }

    @Test void creationTimeIsParsedAndOptionalFieldsCanBeCleared() {
        Map<String,Object> values=AppMediaProvenance.normalize(Map.of("sourceType","ai-generated",
                "licenseNote"," self checked ","createdAt","2026-11-09T00:00:00Z"));
        assertEquals("self checked",values.get("licenseNote"));
        assertEquals(java.sql.Timestamp.from(java.time.Instant.parse("2026-11-09T00:00:00Z")),values.get("sourceCreatedAt"));
        assertNull(values.get("attribution"));assertNull(values.get("aiModel"));
    }

    @Test void newUnknownAndMissingDeclarationsCannotPublish() {
        for (Map<String,Object> row : java.util.List.<Map<String,Object>>of(
                Map.of("sourceType","unknown","licenseNote","not confirmed"),
                Map.of("sourceType","licensed"),Map.of("sourceType","original","licenseNote"," "),
                Map.of("sourceType","ai-generated"),Map.of("sourceType","public-domain"))) {
            ServiceException failure=assertThrows(ServiceException.class,()->AppMediaProvenance.requirePublication(row));
            assertTrue(failure.getMessage().contains("ASSET_LICENSE_MISSING"));
        }
        for(String type:new String[]{"original","licensed","ai-generated","public-domain"})
            assertDoesNotThrow(()->AppMediaProvenance.requirePublication(Map.of("sourceType",type,"licenseNote","test declaration")));
    }

    @Test void untouchedLegacyIsUnknownWithoutMutationOrRevokingExistingPublication() {
        Map<String,Object> legacy=new HashMap<>();legacy.put("id",7L);
        assertTrue(AppMediaProvenance.missing(legacy));
        assertDoesNotThrow(()->AppMediaProvenance.requirePublication(legacy));
        assertEquals("unknown",AppMediaProvenance.ownerView(legacy).get("sourceType"));
        assertEquals(true,AppMediaProvenance.publicView(legacy).get("sourcePendingConfirmation"));
        assertEquals(Map.of("id",7L),legacy);
    }

    @Test void publicProjectionNeverContainsPrivateDeclarationOrStorageFields() {
        Map<String,Object> row=new HashMap<>(Map.of("sourceType","ai-generated","licenseNote","private agreement",
                "attribution","Test author","aiModel","private model","objectKey","private key","memberId",9L));
        row.put("sourceCreatedAt",java.sql.Timestamp.valueOf("2026-11-09 00:00:00"));
        assertEquals(Map.of("sourceType","ai-generated","attribution","Test author","aiGenerated",true,
                "sourcePendingConfirmation",false),AppMediaProvenance.publicView(row));
        assertEquals("private agreement",AppMediaProvenance.ownerView(row).get("licenseNote"));
    }
}
