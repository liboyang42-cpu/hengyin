package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.TopicContentRevisionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1 冻结地理:唯一消费路径 {@code $.themeConfig.contentGeography}
 * = {@code {schemaVersion:1,countryCode,cityCode}}(BE-14 冻结口径)。
 *
 * <p>证三件事:发布时把作者真实国家/城市写进冻结 payload;manifest 投影一致;
 * 新局只从不可变修订/冻结图取,不回读当前可编辑主题,也没有 themeConfig 时绝不凭空创建
 * (创建会改变旅程/玩法判定)。
 */
class TopicContentGeographyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode frozen(String extra) throws Exception {
        return (ObjectNode) JSON.readTree("{\"runtimeSnapshotVersion\":1,\"routeStations\":[]" + extra + "}");
    }

    private static ObjectNode withTheme() throws Exception {
        return frozen(",\"themeConfig\":{\"chapters\":[]}");
    }

    @Test
    void cityComesFromTheAuthoredTopicAndCountryOnlyFromTheExplicitContract() throws Exception {
        TopicContentGeography geography = TopicContentGeography.of(
                withTheme(), "310100", "{\"placeCountryCode\":\"jp\",\"other\":1}");

        assertEquals("JP", geography.getCountryCode(), "显式小写也要规范成 alpha-2 大写");
        assertEquals("310100", geography.getCityCode());
    }

    @Test
    void missingCountryStaysUnknownInsteadOfDefaultingToChina() throws Exception {
        TopicContentGeography geography = TopicContentGeography.of(withTheme(), "310100", "{}");

        assertNull(geography.getCountryCode(), "没有显式 placeCountryCode 就是 UNKNOWN,绝不推断 CN");

        ObjectNode manifest = TopicContentPublicManifest.build(withTheme(), 5L, 1L, "sha", geography);
        JsonNode projected = manifest.path("contentGeography");
        assertEquals(1, projected.path("schemaVersion").asInt());
        assertEquals("310100", projected.path("cityCode").asText());
        assertTrue(projected.path("countryCode").isNull());
    }

    @Test
    void payloadGeographyWinsOverTheTopicsCurrentColumns() throws Exception {
        TopicContentGeography geography = TopicContentGeography.of(
                frozen(",\"themeConfig\":{\"contentGeography\":"
                        + "{\"schemaVersion\":1,\"countryCode\":\"NL\",\"cityCode\":\"AMS\"}}"),
                "310100", "{\"placeCountryCode\":\"JP\"}");

        assertEquals("NL", geography.getCountryCode());
        assertEquals("AMS", geography.getCityCode(), "冻结图自己声明的城市优先于主题当前列");
    }

    @Test
    void malformedCountryIsDroppedRatherThanGuessed() throws Exception {
        assertNull(TopicContentGeography.of(withTheme(), null, "{\"placeCountryCode\":\"JPN\"}").getCountryCode());
        assertNull(TopicContentGeography.of(withTheme(), null, "{\"placeCountryCode\":\"中国\"}").getCountryCode());
        assertNull(TopicContentGeography.of(withTheme(), null, "not-json").getCountryCode());
    }

    @Test
    void publishWritesGeographyIntoTheFrozenPayloadAtTheAgreedPath() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setCityCode("310100");
        topic.setContentContractJson("{\"placeCountryCode\":\"jp\"}");
        String snapshot = withTheme().toString();

        String decorated = TopicContentGeography.decoratePublication(snapshot, topic);

        JsonNode geography = JSON.readTree(decorated).path("themeConfig").path("contentGeography");
        assertEquals(1, geography.path("schemaVersion").asInt());
        assertEquals("JP", geography.path("countryCode").asText());
        assertEquals("310100", geography.path("cityCode").asText());
        assertFalse(JSON.readTree(decorated).has("contentGeography"), "地理不在 header 根键上");
    }

    @Test
    void decorateNeverCreatesAThemeConfigOrTouchesAnAlreadyFrozenGeography() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setCityCode("310100");

        String withoutTheme = frozen("").toString();
        assertEquals(withoutTheme, TopicContentGeography.decoratePublication(withoutTheme, topic),
                "没有 themeConfig 时不能凭空创建(会改变旅程/玩法判定)");

        String already = frozen(",\"themeConfig\":{\"contentGeography\":"
                + "{\"schemaVersion\":1,\"countryCode\":\"NL\",\"cityCode\":\"AMS\"}}").toString();
        assertEquals(already, TopicContentGeography.decoratePublication(already, topic),
                "编译器已冻结的地理必须逐字节保留");
    }

    @Test
    void decorateLeavesBytesAloneWhenNothingIsAuthored() throws Exception {
        CmsTopic topic = new CmsTopic();
        String snapshot = withTheme().toString();

        assertEquals(snapshot, TopicContentGeography.decoratePublication(snapshot, topic));
        assertEquals(snapshot, TopicContentGeography.decoratePublication(snapshot, null));
        assertEquals("not-json", TopicContentGeography.decoratePublication("not-json", topic),
                "坏 payload 不能把正式发布挡下来");
    }

    @Test
    void manifestOmitsGeographyEntirelyWhenNothingIsFrozen() throws Exception {
        TopicContentGeography geography = TopicContentGeography.of(withTheme(), null, null);
        assertTrue(geography.isEmpty());

        ObjectNode manifest = TopicContentPublicManifest.build(withTheme(), 5L, 1L, "sha", geography);

        assertFalse(manifest.has("contentGeography"),
                "两个值都未知时和「老数据没有该键」同义,不给排行制造第二种空值");
    }

    @Test
    void newRunSnapshotCopiesGeographyFromThatRevisionsManifestIntoThemeConfig() throws Exception {
        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(8L);
        revision.setContentSha256("sha");
        revision.setPublicManifestJson("{\"manifestVersion\":1,\"contentGeography\":"
                + "{\"schemaVersion\":1,\"countryCode\":\"NL\",\"cityCode\":\"AMS\"}}");
        ObjectNode snapshot = withTheme();

        TopicContentRevisionHeader.bind(snapshot, revision);

        JsonNode geography = snapshot.path("themeConfig").path("contentGeography");
        assertEquals("NL", geography.path("countryCode").asText());
        assertEquals("AMS", geography.path("cityCode").asText());
        assertEquals(8L, snapshot.path("contentRevisionId").asLong());
    }

    @Test
    void compilerProvidedGeographyIsNeverOverwrittenInTheRunSnapshot() throws Exception {
        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(8L);
        revision.setPublicManifestJson("{\"contentGeography\":{\"schemaVersion\":1,"
                + "\"countryCode\":\"JP\",\"cityCode\":\"TYO\"}}");
        ObjectNode snapshot = frozen(",\"themeConfig\":{\"contentGeography\":"
                + "{\"schemaVersion\":1,\"countryCode\":\"NL\",\"cityCode\":\"AMS\"}}");

        TopicContentRevisionHeader.bind(snapshot, revision);

        assertEquals("NL", snapshot.path("themeConfig").path("contentGeography").path("countryCode").asText());
        assertEquals("AMS", snapshot.path("themeConfig").path("contentGeography").path("cityCode").asText());
    }

    @Test
    void runSnapshotWithoutThemeConfigGetsNoGeographyInsteadOfAFabricatedOne() throws Exception {
        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(8L);
        revision.setPublicManifestJson("{\"contentGeography\":{\"schemaVersion\":1,"
                + "\"countryCode\":\"NL\",\"cityCode\":\"AMS\"}}");
        ObjectNode snapshot = frozen("");

        TopicContentRevisionHeader.bind(snapshot, revision);

        assertFalse(snapshot.has("themeConfig"), "不创建 themeConfig");
        assertFalse(snapshot.has("contentGeography"), "地理不能挂到根键");
    }

    @Test
    void legacyRunsAndRevisionsWithoutGeographyStayUnknown() throws Exception {
        ObjectNode snapshot = withTheme();
        TopicContentRevisionHeader.bind(snapshot, null);
        assertFalse(snapshot.path("themeConfig").has("contentGeography"));
        assertFalse(snapshot.has("contentRevisionId"));

        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(8L);
        revision.setPublicManifestJson("{\"manifestVersion\":1}");
        ObjectNode second = withTheme();
        TopicContentRevisionHeader.bind(second, revision);
        assertFalse(second.path("themeConfig").has("contentGeography"),
                "没有冻结地理的老修订不能凭空多出城市/国家");
    }

    @Test
    void unreadableManifestIsTreatedAsNoGeographyInsteadOfFailingTheRun() throws Exception {
        assertNull(TopicContentGeography.fromManifestJson("not-json"));
        assertNull(TopicContentGeography.fromManifestJson("{}"));
        assertNull(TopicContentGeography.fromManifestJson(null));

        ObjectNode snapshot = withTheme();
        TopicContentRevisionView revision = new TopicContentRevisionView();
        revision.setId(1L);
        revision.setPublicManifestJson("not-json");
        TopicContentRevisionHeader.bind(snapshot, revision);
        assertEquals(1L, snapshot.path("contentRevisionId").asLong());
        assertFalse(snapshot.path("themeConfig").has("contentGeography"));
    }

    @Test
    void manifestStillCarriesNoAnswersWhileAddingGeography() throws Exception {
        ObjectNode source = (ObjectNode) JSON.readTree("{\"runtimeSnapshotVersion\":1,"
                + "\"nodeAnswers\":{\"1\":{\"questionAnswer\":\"秘密\"}},"
                + "\"themeConfig\":{\"chapters\":[]},"
                + "\"routeStations\":[{\"nodeId\":1,\"nodeName\":\"A\",\"scanReady\":true}]}");

        JsonNode manifest = TopicContentPublicManifest.build(source, 5L, 2L, "sha",
                TopicContentGeography.of(source, "310100", "{\"placeCountryCode\":\"JP\"}"));

        assertEquals("310100", manifest.path("contentGeography").path("cityCode").asText());
        assertFalse(manifest.toString().contains("秘密"));
        assertFalse(manifest.has("nodeAnswers"));
    }
}
