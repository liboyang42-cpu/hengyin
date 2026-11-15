package com.chengyinhub.business.opsconsole;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.CmsTopic;
import com.fasterxml.jackson.databind.ObjectMapper;

class JourneyRulesAdminServiceTest
{
    private final JourneyRulesAdminService service = new JourneyRulesAdminService();

    @Test
    void blankClearsRulesAndBadJsonIsRefused()
    {
        CmsTopic topic = new CmsTopic();
        assertNull(service.normalize(topic, "  "));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(topic, "{not json"));
        assertThrows(IllegalArgumentException.class, () -> service.normalize(topic, "[1,2]"));
    }

    @Test
    void validRulesGoThroughTheAuthorValidator() throws Exception
    {
        CmsTopic topic = new CmsTopic();
        String normalized = service.normalize(topic, "{\"schemaVersion\":1,\"checkEnabled\":true}");
        assertNotNull(normalized);
        assertTrue(new ObjectMapper().readTree(normalized).path("checkEnabled").asBoolean());
    }

    @Test
    void frozenBindingBlocksCannotBeInjectedFromTheConsole() throws Exception
    {
        CmsTopic topic = new CmsTopic();
        String normalized = service.normalize(topic, "{\"schemaVersion\":1,\"checkEnabled\":true,\"attributeBinding\":{\"x\":1},\"attributeDefinition\":{\"y\":2}}");
        assertFalse(new ObjectMapper().readTree(normalized).has("attributeBinding"));
        assertFalse(new ObjectMapper().readTree(normalized).has("attributeDefinition"));
    }
}
