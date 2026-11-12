package com.chengyinhub.business.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地点合同 {@code contentContractJson} 的对外可见性。
 *
 * <p>合同里有作者私有的现场备注({@code observationNote})、媒体引用与上一版绑定元数据。
 * 它只给作者本人的 {@code /api/template/topic-template/binding-status} 用，
 * <b>任何返回裸 {@link CmsTopic} 的接口都不得带出</b>；写入方向同样拒绝 ——
 * 客户端若能塞这份 JSON，就能伪造 {@code bindingRevision/confirmedBy} 绕过服务端确认。
 *
 * <p>负控：把 {@code @JsonIgnore} 换成 {@code @JsonProperty(access = WRITE_ONLY)}
 * （写入方向放行）或删掉注解，本用例立刻红。
 */
class CmsTopicContentContractExposureTest {

    private static final String CONTRACT = "{\"schemaVersion\":1,\"locationSlots\":[{\"slotKey\":\"loc-11\","
            + "\"purpose\":\"SPACE\",\"required\":true}],\"locationBindings\":[{\"slotKey\":\"loc-11\","
            + "\"nodeId\":\"501\",\"bindingRevision\":2,\"verificationStatus\":\"BOUND_UNVERIFIED\","
            + "\"confirmedBy\":\"2000\",\"observationNote\":\"店员说二楼靠窗\"}]}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void genericTopicSerializationOmitsTheWholeContract() throws Exception {
        CmsTopic topic = new CmsTopic();
        topic.setId(42L);
        topic.setCityCode("320100");
        topic.setContentContractJson(CONTRACT);

        String json = objectMapper.writeValueAsString(topic);

        assertFalse(json.contains("contentContractJson"), "裸 CmsTopic 不得带出合同");
        assertFalse(json.contains("locationSlots"));
        assertFalse(json.contains("observationNote"));
        assertFalse(json.contains("店员说二楼靠窗"), "作者私有现场备注不得出现在公共序列化里");
        assertFalse(json.contains("confirmedBy"), "服务端确认事实不得被客户端读回伪造");
        assertTrue(json.contains("\"cityCode\":\"320100\""), "城市本身是可公开事实，照常下发");
    }

    @Test
    void genericDeserializationCannotInjectTheContract() throws Exception {
        CmsTopic incoming = objectMapper.readValue(
                "{\"id\":42,\"contentContractJson\":" + objectMapper.writeValueAsString(CONTRACT) + "}",
                CmsTopic.class);

        assertNull(incoming.getContentContractJson(),
                "通用 JSON 绑定不得写入合同：合同只能经作者绑定、采用或发布链路由服务端写");
        assertEquals(Long.valueOf(42L), incoming.getId());
    }

    @Test
    void toStringDoesNotLeakAuthorPrivateBindingFacts() {
        CmsTopic topic = new CmsTopic();
        topic.setContentContractJson(CONTRACT);

        String text = topic.toString();

        assertFalse(text.contains("contentContractJson"));
        assertFalse(text.contains("店员说二楼靠窗"));
        assertFalse(text.contains("confirmedBy"));
    }

    @Test
    void authorReadbackStillGetsTheContractThroughTheEntity() {
        // 读取侧不能被注解挡住：专用读接口靠 getter 取合同再按白名单投影。
        CmsTopic topic = new CmsTopic();
        topic.setContentContractJson(CONTRACT);
        assertNotNull(topic.getContentContractJson());
        assertTrue(topic.getContentContractJson().contains("loc-11"));
    }
}
