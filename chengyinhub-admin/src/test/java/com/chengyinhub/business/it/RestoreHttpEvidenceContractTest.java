package com.chengyinhub.business.it;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** SOURCE only: actual journal parser and Spring JSON writers. No app/DB/provider/HTTP is started. */
class RestoreHttpEvidenceContractTest {
    @TempDir Path directory;

    private List<Map<String,Object>> journal() {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(String stage:List.of("ENTER","RETURN","SQL"))
            rows.add(Map.of("bootId","actual-journal-boot","symbol",stage.equals("SQL") ? "com.example.Mapper.insert!selectKey" : "ActualOwner.method","stage",stage,"held",true));
        rows.add(Map.of("bootId","actual-journal-boot","symbol","ActualOwner.method","stage","THROW","held",true,
                "errorType","com.chengyinhub.common.config.RestoreDispatchGuard$Blocked","blocked",true));
        return rows;
    }

    private Path writeJournal(List<Map<String,Object>> rows) throws Exception {
        Path file=directory.resolve("actual-events.jsonl");
        StringBuilder text=new StringBuilder();
        for(var row:rows)text.append(RestoreHttpApplicationChild.JSON.writeValueAsString(row)).append("\n");
        Files.writeString(file,text.toString());
        return file;
    }

    private JsonNode wire(HttpMessageConverter<Object> converter,Object value) throws Exception {
        var output=new MockHttpOutputMessage();
        converter.write(value,MediaType.APPLICATION_JSON,output);
        return RestoreHttpApplicationChild.JSON.readTree(output.getBodyAsBytes());
    }

    @Test void realJournalRowsRemainPlainValuesAndRoundTripWithBothActualSpringJsonConverters() throws Exception {
        var original=journal();
        var rows=RestoreHttpApplicationChild.events(writeJournal(original));
        assertEquals(original,rows);assertEquals(4,rows.size());
        var value=AjaxResult.success(Map.of("bootId","actual-journal-boot","held",true,
                "serverUuid","11111111-1111-1111-1111-111111111111","schema","owned","events",rows,
                "verifiedFacts",List.of(),"inbox",List.of()));
        for(var converter:List.<HttpMessageConverter<Object>>of(
                new MappingJackson2HttpMessageConverter(),new JacksonJsonHttpMessageConverter())) {
            JsonNode body=wire(converter,value);
            assertEquals(200,body.path("code").asInt());
            assertEquals(7,body.path("data").size());assertEquals(4,body.path("data").path("events").size());
            for(int i=0;i<rows.size();i++)
                assertEquals(RestoreHttpApplicationChild.JSON.valueToTree(original.get(i)),
                        body.path("data").path("events").get(i));
            assertTrue(body.path("data").path("events").get(3).path("blocked").asBoolean());
            assertEquals("com.chengyinhub.common.config.RestoreDispatchGuard$Blocked",
                    body.path("data").path("events").get(3).path("errorType").asText());
        }
    }

    @Test void selectedActualMvcJsonConverterMustMatchOriginalProductionPreference() {
        assertDoesNotThrow(() -> RestoreHttpApplicationChild.assertHttpJsonConverters(
                List.of(new StringHttpMessageConverter(),new MappingJackson2HttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter())));
        var wrong=assertThrows(RestoreHttpApplicationChild.StartupRejected.class,
                () -> RestoreHttpApplicationChild.assertHttpJsonConverters(
                        List.of(new StringHttpMessageConverter(),new JacksonJsonHttpMessageConverter(),
                                new MappingJackson2HttpMessageConverter())));
        assertEquals(RestoreHttpApplicationChild.StartupReason.ASSERTION_REJECTED,wrong.reason);
        assertThrows(RestoreHttpApplicationChild.StartupRejected.class,
                () -> RestoreHttpApplicationChild.assertHttpJsonConverters(List.of(new StringHttpMessageConverter())));
    }

    @Test void malformedMissingOrUnexpectedJournalFieldsAreRejectedWithoutSkippingOrManufacturingRows() throws Exception {
        var valid=journal().getFirst();
        for(String kind:List.of("missing","extra","held-type","stage","symbol")) {
            var row=new java.util.HashMap<String,Object>(valid);
            switch(kind) {
                case "missing" -> row.remove("bootId");
                case "extra" -> row.put("private-canary","private-SQL-token-canary");
                case "held-type" -> row.put("held",1);
                case "stage" -> row.put("stage","UNKNOWN");
                default -> row.put("symbol","private SQL/value");
            }
            Path file=writeJournal(List.of(valid,row));
            var failure=assertThrows(IllegalStateException.class,() -> RestoreHttpApplicationChild.events(file));
            assertTrue(failure.getMessage().startsWith("original event"));
            assertFalse(failure.getMessage().contains("private"));
        }
        Path file=directory.resolve("null-event.jsonl");Files.writeString(file,"null\n");
        assertThrows(IllegalStateException.class,() -> RestoreHttpApplicationChild.events(file));
    }

    @Test void throwClassificationFieldsAreRequiredAndPreservedAsRealBooleans() throws Exception {
        var valid=journal().getLast();
        for(String kind:List.of("blocked","errorType","blocked-type")) {
            var row=new java.util.HashMap<String,Object>(valid);
            if(kind.equals("blocked-type"))row.put("blocked","true");else row.remove(kind);
            Path file=writeJournal(List.of(row));
            assertThrows(IllegalStateException.class,() -> RestoreHttpApplicationChild.events(file));
        }
        assertEquals(valid,RestoreHttpApplicationChild.events(writeJournal(List.of(valid))).getFirst());
    }

    @Test void callbackEnvelopeKeepsOriginalFactIdentityAndSingleDataLayerUnderBothJsonWriters() throws Exception {
        var original=Map.of("originalOutcome","FAIL","factId",71L,"factHash","a".repeat(64));
        for(var converter:List.<HttpMessageConverter<Object>>of(
                new MappingJackson2HttpMessageConverter(),new JacksonJsonHttpMessageConverter())) {
            JsonNode body=wire(converter,AjaxResult.success(original));
            assertEquals(200,body.path("code").asInt());
            assertEquals(3,body.path("data").size());assertFalse(body.path("data").has("data"));
            assertEquals("FAIL",body.path("data").path("originalOutcome").asText());
            assertEquals(71L,body.path("data").path("factId").asLong());
            assertEquals("a".repeat(64),body.path("data").path("factHash").asText());
        }
    }
}
