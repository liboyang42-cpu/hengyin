package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.JourneyRules;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthorAttributeBindingTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode rules() throws Exception {
        return (ObjectNode) json.readTree("{\"schemaVersion\":1,\"stateEnabled\":true,\"natAuto\":false,\"attributes\":[{\"key\":\"counter.hp\",\"label\":\"体力\",\"initial\":3,\"min\":0,\"max\":20,\"visible\":true}],\"attributeBinding\":{\"schemaVersion\":1,\"registryVersion\":\"2\",\"enabledKeys\":[\"counter.hp\"]}}");
    }
    private ObjectNode definition() throws Exception {
        return (ObjectNode) json.readTree("{\"schemaVersion\":1,\"configVersion\":\"4\",\"registryVersion\":\"2\",\"attributes\":[{\"key\":\"counter.hp\",\"keyType\":\"counter\",\"label\":\"体力\",\"initial\":3,\"min\":0,\"max\":20,\"visible\":true,\"displayType\":\"bar\",\"persistScope\":\"topic\",\"color\":null,\"icon\":null,\"sortOrder\":null,\"rules\":null}]}");
    }
    @Test void freezesOnlyTheExpectedVersionAndDoesNotWriteDefinitions() throws Exception {
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        when(registry.freezeDefinition(eq(7L), eq(4L), eq("2"), any(JourneyRules.class))).thenReturn(definition());
        ObjectNode source = rules(); source.set("attributeDefinition", json.createObjectNode().put("forged", true));
        String saved = AuthorAttributeBinding.freeze(registry, 7L, 3L, 3L, 4L, source.toString(), true);
        assertEquals(definition(), json.readTree(saved).get("attributeDefinition"));
        assertFalse(JourneyRules.parse(saved).isNatAuto());
        assertEquals(definition(), JourneyRules.parse(saved).getAttributeDefinition());
        verify(registry).freezeDefinition(eq(7L), eq(4L), eq("2"), any(JourneyRules.class));
        verifyNoMoreInteractions(registry);
    }
    @Test void staleConfigAndUnconfirmedProjectionCannotReachRegistry() throws Exception {
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        ServiceException stale = assertThrows(ServiceException.class,
                () -> AuthorAttributeBinding.freeze(registry, 7L, 2L, 3L, 4L, rules().toString(), true));
        assertEquals(409, stale.getCode());
        ObjectNode source = rules(); source.putArray("attributes");
        assertThrows(ServiceException.class, () -> AuthorAttributeBinding.freeze(registry, 7L, 3L, 3L, 4L, source.toString(), true));
        verifyNoInteractions(registry);
    }
    @Test void registryConflictIsNotRetriedWithLatestVersion() throws Exception {
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        when(registry.freezeDefinition(eq(7L), eq(4L), eq("2"), any(JourneyRules.class)))
                .thenThrow(new ServiceException("版本已变化",409).setDetailMessage("ATTRIBUTE_REGISTRY_VERSION_CONFLICT"));
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> AuthorAttributeBinding.freeze(registry, 7L, 3L, 3L, 4L, rules().toString(), true));
        assertEquals("ATTRIBUTE_REGISTRY_VERSION_CONFLICT", conflict.getDetailMessage());
        verify(registry).freezeDefinition(eq(7L), eq(4L), eq("2"), any(JourneyRules.class));
        verifyNoMoreInteractions(registry);
    }
    @Test void oldDraftsRemainEditableButCannotPublishUnconfirmedCopies() throws Exception {
        ThemeStateKeyService registry = mock(ThemeStateKeyService.class);
        ObjectNode source = rules(); source.remove("attributeBinding");
        String saved = AuthorAttributeBinding.freeze(registry, 7L, null, 3L, 4L, source.toString(), true);
        assertEquals(source, json.readTree(saved));
        assertThrows(ServiceException.class, () -> AuthorAttributeBinding.freeze(registry,7L,null,3L,3L,saved,false));
        verifyNoInteractions(registry);
    }
    @Test void versionAndPartialDefinitionsFailClosed() throws Exception {
        for (String raw : new String[]{"2","\"02\"","\"0\"","\"9223372036854775808\"","null"})
            assertThrows(ServiceException.class, () -> AuthorAttributeBinding.version(json.readTree(raw)));
        ObjectNode partial = definition(); ((ObjectNode) partial.path("attributes").get(0)).remove("persistScope");
        assertThrows(IllegalArgumentException.class, () -> AuthorAttributeBinding.validateDefinition(partial));
    }
}
