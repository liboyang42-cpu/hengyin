package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.ThemeStateKeyMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.service.support.TopicRouteConfigValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 只读发布预演的真实编译器合同（只跑编译期冻结代码；不跑 Spring / 存储 / HTTP）。
 *
 * <p>这里要钉住四件事，任何一条退化成 mock 断言都不算数：
 * <ol>
 *   <li>模板线索未登记时，预演把它并入内存声明投影后<b>继续</b>跑后面的冻结校验；</li>
 *   <li>因此紧随其后的非法配置仍会被真实校验器拦下（预演不会吞异常提前返回「通过」）；</li>
 *   <li>预演全程不取任何行锁 / 声明写锁、不 upsert、不改调用方实体；</li>
 *   <li>预演结束后上下文被还原，同一编译器的后续调用不继承上一次的声明投影。</li>
 * </ol>
 */
class PublicationPreviewTest {
    private static final long TOPIC = 10L;
    private static final long NODE = 11L;
    private static final long TEMPLATE = 701L;
    /** 模板「线索」工具产出的稳定键（同名同键），预演时尚未登记。 */
    private static final String CLUE_KEY = "clue.h_abcdef123456";

    private final ObjectMapper json = new ObjectMapper();

    // ---------------------------------------------------------------- fixtures

    /** 模板配了 interactionTools（configured=true，走完整编译路径）并声明一条未登记的线索。 */
    private String templateConfigWithClue() {
        return "{\"schemaVersion\":1,\"interactionTools\":{},\"clues\":{\"" + CLUE_KEY + "\":\"地图\"}}";
    }

    private static final class Fixture {
        final CmsTopicMapper topics = mock(CmsTopicMapper.class);
        final CmsTopicNodeMapper nodes = mock(CmsTopicNodeMapper.class);
        final CmsTopicChapterMapper chapters = mock(CmsTopicChapterMapper.class);
        final CmsMemberTemplateMapper templates = mock(CmsMemberTemplateMapper.class);
        final ThemeStateKeyMapper keys = mock(ThemeStateKeyMapper.class);
        final CmsTopic topic = new CmsTopic();
        final CmsTopicNode node = new CmsTopicNode();
        final CmsMemberTemplate template = new CmsMemberTemplate();
        final TopicRoutePublicationCompiler compiler;

        Fixture(ObjectMapper json, String themeRuntimeJson, String templateAdvancedJson) {
            topic.setId(TOPIC); topic.setRouteMode("LINEAR");
            topic.setThemeRuntimeConfigJson(themeRuntimeJson);
            node.setId(NODE); node.setTopicId(TOPIC); node.setTemplateId(TEMPLATE);
            node.setStatus(1L); node.setSortId(1L); node.setName("站点");
            // 个人任务要求「真实商户到达」:站点挂承接商家,冻结出的 routeStations 才带 merchantMemberId。
            node.setMerchantMemberId(88L);
            template.setId(TEMPLATE); template.setValidationMethod(1);
            template.setQuestionAnswer("A"); template.setAdvancedConfigJson(templateAdvancedJson);

            when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
            when(topics.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic);
            when(chapters.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
            when(chapters.selectByTopicForUpdate(TOPIC)).thenReturn(Collections.emptyList());
            when(nodes.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
            when(nodes.selectByTopicForUpdate(TOPIC)).thenReturn(Collections.singletonList(node));
            when(templates.selectCmsMemberTemplateByIds(any())).thenReturn(Collections.singletonList(template));
            when(templates.selectCmsMemberTemplateByIdsForUpdate(any())).thenReturn(Collections.singletonList(template));
            when(keys.selectByTopicId(TOPIC)).thenReturn(Collections.emptyList());
            when(keys.selectByTopicIdForUpdate(TOPIC)).thenReturn(Collections.emptyList());
            when(keys.lockTopicForStateKeyWrite(TOPIC)).thenReturn(TOPIC);
            compiler = new TopicRoutePublicationCompiler(nodes, templates, chapters, keys,
                    Clock.systemUTC(), json, new TopicRouteConfigValidator(), topics);
        }

        void assertNoLockNoWrite() {
            verify(topics, never()).selectCmsTopicByIdForUpdate(any());
            verify(chapters, never()).selectByTopicForUpdate(any());
            verify(nodes, never()).selectByTopicForUpdate(any());
            verify(templates, never()).selectCmsMemberTemplateByIdsForUpdate(any());
            verify(keys, never()).lockTopicForStateKeyWrite(any());
            verify(keys, never()).selectByTopicIdForUpdate(any());
            verify(keys, never()).selectByTopicIdAndStateKeyForUpdate(any(), any());
            verify(keys, never()).upsertThemeStateKey(any());
            // 普通读确实被用上了（不是靠空投影蒙混）
            verify(topics, atLeastOnce()).selectCmsTopicById(TOPIC);
            verify(nodes, atLeastOnce()).selectCmsTopicNodeList(any());
            verify(templates, atLeastOnce()).selectCmsMemberTemplateByIds(any());
        }
    }

    private ThemeStateKey projected(String key, String label) {
        ThemeStateKey row = new ThemeStateKey();
        row.setTopicId(TOPIC); row.setStateKey(key);
        row.setKeyType(ThemeStateKey.TYPE_FLAG); row.setScope(ThemeStateKey.SCOPE_RUN);
        row.setLabel(label); row.setInitialValue(0);
        return row;
    }

    private List<String> stateKeyLabels(JsonNode frozen) {
        List<String> keys = new ArrayList<>();
        for (JsonNode row : frozen.path("stateKeys")) keys.add(row.path("key").asText() + "=" + row.path("label").asText());
        return keys;
    }

    // ------------------------------------------------------------------ cases

    /**
     * 未登记的模板线索不得让预演提前结束：它被并入本次内存声明投影，随后继续跑
     * freezeJourney / freezeKeys，最终快照里能看到这条线索。
     */
    @Test
    void unregisteredTemplateClueIsDeclaredInMemoryAndTheFreezeRunsToCompletion() throws Exception {
        Fixture f = new Fixture(json, "{\"schemaVersion\":1}", templateConfigWithClue());

        String frozen = f.compiler.previewPublication(f.topic, null, Collections.emptyList());
        JsonNode snapshot = json.readTree(frozen);

        assertEquals(1, snapshot.path("runtimeSnapshotVersion").asInt(), "完整冻结跑到了结尾");
        assertTrue(stateKeyLabels(snapshot).contains(CLUE_KEY + "=地图"),
                "派生线索必须进入本局声明投影: " + stateKeyLabels(snapshot));
        f.assertNoLockNoWrite();
        assertEquals("{\"schemaVersion\":1}", f.topic.getThemeRuntimeConfigJson(), "不改调用方实体");
        verify(f.keys, never()).upsertThemeStateKey(any());
    }

    /**
     * 同一次预演里，线索之后的非法配置仍被真实校验器拦下 —— 证明线索登记点没有吞掉后续校验。
     * 这里用一个引用了本主题不存在节点的场景配置：它只在 assertCompiledReferences 阶段被检查，
     * 而那一步恰好在 registerTemplateClues 之后。
     */
    @Test
    void aLaterInvalidFrozenConfigIsStillRejectedAfterTheClueIsDeclared() {
        Fixture f = new Fixture(json,
                // scenes[].name 是必填(ThemeRuntimeConfig#text(...,40,true));缺了它会在 name 校验就失败,
                // 测不到「线索登记之后仍继续走到引用校验」这条断言。
                // scenes 非空时还要求 currentScene(ThemeRuntimeConfig:76);否则同样在引用校验之前就失败。
                "{\"schemaVersion\":1,\"currentScene\":\"s1\",\"scenes\":[{\"id\":\"s1\",\"name\":\"场景一\","
                        + "\"nodeConfigs\":{\"999\":{\"schemaVersion\":1}}}]}",
                templateConfigWithClue());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> f.compiler.previewPublication(f.topic, null, Collections.emptyList()));
        assertEquals("场景引用外来节点", error.getMessage(),
                "预演必须在登记线索之后继续走到引用校验，而不是提前结束");
        assertNoProjectionWrites(f);
    }

    /** 预演声明只影响本次调用：再跑一次（不传声明）不得继承上一次的线索。 */
    @Test
    void previewContextIsRestoredAndNeverLeaksIntoTheNextCall() throws Exception {
        Fixture f = new Fixture(json, "{\"schemaVersion\":1}", templateConfigWithClue());

        String first = f.compiler.previewPublication(f.topic, null,
                Collections.singletonList(projected("tag.extra", "额外标记")));
        assertTrue(stateKeyLabels(json.readTree(first)).contains("tag.extra=额外标记"));

        String second = f.compiler.previewPublication(f.topic, null, Collections.emptyList());
        List<String> labels = stateKeyLabels(json.readTree(second));
        assertFalse(labels.contains("tag.extra=额外标记"), "上一次的声明投影不得残留: " + labels);
        assertTrue(labels.contains(CLUE_KEY + "=地图"), "本次仍然按库内声明 + 本次派生线索计算");
        // 非预演路径不得残留预演标志:它仍按原口径拒绝在只读事务外登记线索。
        IllegalStateException plainError = assertThrows(IllegalStateException.class,
                () -> f.compiler.freezePublication(f.topic, null));
        assertEquals("自动线索声明必须加入主题写事务", plainError.getMessage(), "预演标志不得残留到非预演调用");
        f.assertNoLockNoWrite();
    }

    /** 预演不接受在写事务里被调用：那样会退化成取锁读。 */
    @Test
    void previewRefusesToRunInsideAWritableTransaction() {
        Fixture f = new Fixture(json, "{\"schemaVersion\":1}", templateConfigWithClue());
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class,
                    () -> f.compiler.previewPublication(f.topic, null, Collections.emptyList()));
            verify(f.keys, never()).upsertThemeStateKey(any());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    /** 未实现该窄接口时必须显式失败，不能静默返回一个「看起来通过」的值。 */
    @Test
    void theInterfaceDefaultFailsInsteadOfSilentlyPassing() throws Exception {
        var method = ITopicRouteRuntimeService.class.getMethod("previewPublication",
                CmsTopic.class, String.class, List.class);
        assertTrue(method.isDefault());
        ITopicRouteRuntimeService unwired = (ITopicRouteRuntimeService) Proxy.newProxyInstance(
                ITopicRouteRuntimeService.class.getClassLoader(),
                new Class<?>[]{ITopicRouteRuntimeService.class},
                (proxy, invoked, args) -> invoked.isDefault()
                        ? InvocationHandler.invokeDefault(proxy, invoked, args) : null);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(unwired, new CmsTopic(), null, Collections.emptyList()));
    }

    private static void assertNoProjectionWrites(Fixture f) {
        verify(f.keys, never()).upsertThemeStateKey(any());
        verify(f.keys, never()).lockTopicForStateKeyWrite(any());
        verify(f.keys, never()).selectByTopicIdForUpdate(any());
        verify(f.topics, never()).selectCmsTopicByIdForUpdate(any());
        verify(f.chapters, never()).selectByTopicForUpdate(any());
        verify(f.nodes, never()).selectByTopicForUpdate(any());
    }
}
