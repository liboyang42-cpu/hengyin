package com.chengyinhub.security;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JettisonDependencyCanaryTest {
    @Test
    void parsesSdkJsonAndRejectsExcessiveNestingWithoutStackOverflow() throws Exception {
        JSONObject metadata = new JSONObject("{\"Code\":\"Success\",\"Expiration\":\"2100-01-01T00:00:00Z\"}");
        assertEquals("Success", metadata.getString("Code"));
        assertEquals("2100-01-01T00:00:00Z", metadata.getString("Expiration"));

        StringBuilder nested = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            nested.append("{\"value\":");
        }
        nested.append('0');
        for (int i = 0; i < 10000; i++) {
            nested.append('}');
        }
        assertThrows(JSONException.class, () -> new JSONObject(nested.toString()));
    }
}
