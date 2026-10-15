package com.chengyinhub.security;

import com.chengyinhub.common.utils.JsonUtils;
import com.chengyinhub.web.controller.api.ApiAnalyticsController;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JacksonDependencyCanaryTest {
    @Test
    void managedJacksonBomsAreUniqueAndResolveToRuntimeVersions() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var pom = factory.newDocumentBuilder().parse(Path.of("..", "pom.xml").toFile());
        var xpath = XPathFactory.newInstance().newXPath();
        for (String group : new String[]{"com.fasterxml.jackson", "tools.jackson"}) {
            String dependency = "/project/dependencyManagement/dependencies/dependency[groupId='"
                    + group + "' and artifactId='jackson-bom']";
            assertEquals("1", xpath.evaluate("count(" + dependency + ")", pom), group);
            assertEquals("pom", xpath.evaluate(dependency + "/type", pom));
            assertEquals("import", xpath.evaluate(dependency + "/scope", pom));
            assertEquals(group.equals("com.fasterxml.jackson") ? "${jackson2.version}" : "${jackson3.version}",
                    xpath.evaluate(dependency + "/version", pom));
        }
        assertEquals(xpath.evaluate("/project/properties/jackson2.version", pom), new ObjectMapper().version().toString());
        assertEquals(xpath.evaluate("/project/properties/jackson3.version", pom),
                Class.forName("tools.jackson.databind.cfg.PackageVersion").getField("VERSION").get(null).toString());
    }

    @Test
    void directParserRejectsExcessiveNestingAndStillReadsNormalJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(JsonProcessingException.class, () -> mapper.readTree(nested()));
        assertEquals(7, mapper.readTree("{\"value\":7}").get("value").intValue());
        assertEquals("\"2026-10-07\"", JsonUtils.toJsonString(LocalDate.of(2026, 10, 7)));
    }

    @Test
    void mvcConverterRejectsDeepUnknownFieldBeforeControllerAndRecovers() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiAnalyticsController()).build();
        mvc.perform(post("/api/analytics/events/batch").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unknown\":" + nested() + "}"))
                .andExpect(status().isBadRequest());
        // A normal empty DTO reaches the existing controller validation without parsing failure.
        mvc.perform(post("/api/analytics/events/batch").contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void jacksonYamlAndBootConfigurationLoaderRemainCompatible() throws Exception {
        String yaml = "audit:\n  enabled: true\n";
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        assertTrue(mapper.readTree(mapper.writeValueAsString(mapper.readTree(yaml)))
                .get("audit").get("enabled").booleanValue());
        assertEquals(Boolean.TRUE, new YamlPropertySourceLoader()
                .load("audit", new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))
                .get(0).getProperty("audit.enabled"));
        assertThrows(YAMLException.class, () -> new YamlPropertySourceLoader()
                .load("audit", new ByteArrayResource("!!java.util.ArrayList []"
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void yamlDefaultLoaderRejectsJavaObjectTagsAndKeepsStandardValues() {
        Yaml yaml = new Yaml();
        assertThrows(YAMLException.class, () -> yaml.load("!!java.util.ArrayList []"));
        Map<String, Object> values = yaml.load("enabled: true\nlabel: 城瘾\n");
        assertEquals(Boolean.TRUE, values.get("enabled"));
        assertEquals("城瘾", values.get("label"));
    }

    private static String nested() {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < 10000; i++) json.append("{\"value\":");
        json.append('0');
        for (int i = 0; i < 10000; i++) json.append('}');
        return json.toString();
    }
}
