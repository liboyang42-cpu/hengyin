package com.chengyinhub.business.it;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenericGameplayHttpSchemaTest {
    @Test void requiredMapperColumnsAreProbedBeforeTheApplicationStarts() throws Exception {
        Statement statement=mock(Statement.class);
        ResultSet offer=columns(3),role=columns(1),application=columns(1);
        when(statement.executeQuery("SELECT withdraw_reason, withdraw_required, replacement_of_offer_id FROM merchant_chapter_offer WHERE 1=0")).thenReturn(offer);
        when(statement.executeQuery("SELECT declared_by FROM product_role WHERE 1=0")).thenReturn(role);
        when(statement.executeQuery("SELECT replacement_of_offer_id FROM merchant_chapter_application WHERE 1=0")).thenReturn(application);
        GenericGameplayHttpE2eIT.assertFixtureMapperColumns(statement);
        verify(offer).close();verify(role).close();verify(application).close();
    }
    @Test void missingColumnsAbortTheFixtureRatherThanSilencingMapperFailures() throws Exception {
        Statement statement=mock(Statement.class);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("Unknown column withdraw_reason"));
        assertTrue(assertThrows(SQLException.class,()->GenericGameplayHttpE2eIT.assertFixtureMapperColumns(statement)).getMessage().contains("withdraw_reason"));
        verify(statement,times(1)).executeQuery(anyString());
    }
    @Test void transcriptRedactsNestedTeamInviteCodesWithoutMutatingHttpResponse() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode source=json.readTree("{\"data\":{\"team\":{\"inviteCode\":\"local-secret\"},\"nested\":[{\"inviteCode\":\"other-secret\"}]},\"code\":200}");
        ((com.fasterxml.jackson.databind.node.ObjectNode)source.path("data")).put("nonce","local-nonce");
        ((com.fasterxml.jackson.databind.node.ObjectNode)source.path("data")).put("quoteSign","local-quote").put("token","local-token").put("password","local-password");
        com.fasterxml.jackson.databind.JsonNode safe=GenericGameplayHttpE2eIT.redact(source);
        assertEquals("[REDACTED]",safe.path("data").path("nonce").asText());
        for(String key:new String[]{"quoteSign","token","password"})assertEquals("[REDACTED]",safe.path("data").path(key).asText());
        assertEquals("local-quote",source.path("data").path("quoteSign").asText());
        assertEquals("[REDACTED]",safe.path("data").path("team").path("inviteCode").asText());
        assertEquals("[REDACTED]",safe.path("data").path("nested").path(0).path("inviteCode").asText());
        assertEquals(200,safe.path("code").asInt());assertEquals("local-secret",source.path("data").path("team").path("inviteCode").asText());
    }
    private ResultSet columns(int count) throws Exception {
        ResultSet result=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(count);return result;
    }
}
