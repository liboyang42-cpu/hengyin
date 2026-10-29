package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.IProductRoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 移交承接 NPE:copyCmsTopic:189 的三元表达式对 null 拆箱。
 *
 * `params != null ? (Integer) params.get("copyType") : 1` —— 两侧混用 Integer 与 int 字面量,
 * 触发 JLS 15.25 二元数值提升:Integer 那一侧被**拆箱成 int**。所以 params 非空但没带 copyType 时,
 * 对 null 拆箱 → NPE,当场炸在 :189,连主题都没复制。
 *
 * ApiTopicController:377(移交承接给俱乐部)建的 params 只塞了 memberId、从不塞 copyType
 * ⇒ 每次必炸 ⇒ 该功能生产上一次都没成功过,且被 :74 的 catch 吞成「复制主题失败」。
 *
 * 另一个入口 ApiTopicTemplateController:74 显式 put("copyType", 3) 所以一直没暴露;
 * 后台 CmsTopicController:71 的 @RequestBody(required=false) 传 null 时走 else 分支拿到 1,也躲过了。
 */
class CopyTopicCopyTypeNpeTest {

    private static final Long ORIGINAL = 100L;
    private static final Long NEW_TOPIC = 101L;
    private static final Long MEMBER = 900001L;

    private CmsTopicMapper cmsTopicMapper;
    private CmsTopicServiceImpl svc;

    @BeforeEach
    void setUp() {
        cmsTopicMapper = mock(CmsTopicMapper.class);

        CmsTopic original = new CmsTopic();
        original.setId(ORIGINAL);
        original.setName("原主题");
        when(cmsTopicMapper.selectCmsTopicById(ORIGINAL)).thenReturn(original);
        // insert 回填自增主键,模拟 MyBatis useGeneratedKeys
        doAnswer(inv -> {
            ((CmsTopic) inv.getArgument(0)).setId(NEW_TOPIC);
            return 1;
        }).when(cmsTopicMapper).insertCmsTopic(any());

        svc = new CmsTopicServiceImpl();

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(svc, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        ReflectionTestUtils.setField(svc, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(svc, "productRoleService", mock(IProductRoleService.class));
        // [W4闭环] copyNodes 会查节点模板决定是否深拷贝;本测试聚焦 copyType 语义,
        // 模板查不到 = 原样保留 templateId(与断言「模板 id 带过去」一致)。
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(svc, "cmsMemberTemplateMapper",
                mock(com.chengyinhub.business.mapper.CmsMemberTemplateMapper.class));
    }

    /**
     * ★ 红测:复现 ApiTopicController:377 的真实调用形状 —— params 非空、只带 memberId、无 copyType。
     * 修复前:NPE 炸在 :189。修复后:copyType 取文档化的默认值 1(只复制基本信息),正常返回新主题 id。
     */
    @Test
    void copyWithParamsLackingCopyType_doesNotThrow_andDefaultsToBasicInfoOnly() {
        Map<String, Object> params = new HashMap<>();
        params.put("memberId", MEMBER); // 与 ApiTopicController:377 逐字一致:只有 memberId

        Long newId = com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, params);

        assertEquals(NEW_TOPIC, newId, "移交承接必须复制成功,不能 NPE");
    }

    /** params 为 null(后台 CmsTopicController:71 不传 body)照旧走默认 1 —— 既有行为不许回归。 */
    @Test
    void copyWithNullParams_stillDefaultsToOne() {
        assertEquals(NEW_TOPIC, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, null), "params=null 既有行为不变");
    }

    /**
     * copyType 显式传值仍生效 —— 用 JSON 常见的 Integer 装箱值,守着 ApiTopicTemplateController:74 的既有契约。
     * copyType=1 不触发 :220/:224,故不需要 stub 章节/票的 mapper。
     */
    @Test
    void copyWithExplicitCopyTypeOne_isHonoured() {
        Map<String, Object> params = new HashMap<>();
        params.put("memberId", MEMBER);
        params.put("copyType", 1);

        assertEquals(NEW_TOPIC, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, params), "显式 copyType 契约不变");
    }

    /**
     * 后台 CmsTopicController:71 收的是裸 JSON body,copyType 可能是 Long(Jackson 大整数)或畸形串。
     * 原 `(Integer)` 强转会 CCE;Convert 单参版对畸形串返回 null 会把 NPE 顺延到 :220。两者都必须落到默认 1。
     */
    @Test
    void copyWithNonIntegerCopyType_fallsBackToDefault_neverThrows() {
        Map<String, Object> longValued = new HashMap<>();
        longValued.put("copyType", 1L); // Jackson 可能给 Long
        assertEquals(NEW_TOPIC, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, longValued), "Long 不该 CCE");

        Map<String, Object> garbage = new HashMap<>();
        garbage.put("copyType", "abc"); // 畸形值
        assertEquals(NEW_TOPIC, com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, garbage), "畸形值该落回默认 1,不该 NPE");
    }

    /**
     * ★ copyType=2(移交承接用的深度):章节 + 节点都要落库,且节点必须带上
     * <b>template_id(=「游戏」)</b> 与 <b>xpValue</b>。
     * <p>xpValue 是 M7 纯停靠节点探索值(CmsTopicNode:76),copyNodes 的逐字段手抄漏了它
     * (`insertCmsTopicNode` 明明有 xp_value 列)⇒ 每次复制静默丢 XP。这正是 :319 注释警告的
     * 「手抄漏字段 → 副本静默丢配置、零报错」。
     */
    @Test
    void copyTypeTwo_copiesChaptersAndNodes_carryingGameTemplateAndXp() {
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicNodeMapper", nodeMapper);

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(11L);
        chapter.setName("第一章");
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        doAnswer(inv -> {
            ((CmsTopicChapter) inv.getArgument(0)).setId(21L); // 新章节 id
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());

        CmsTopicNode node = new CmsTopicNode();
        node.setId(31L);
        node.setChapterId(11L);
        node.setName("游戏节点");
        node.setTemplateId(77L);   // ←「游戏」:节点挂的 CmsMemberTemplate
        node.setXpValue(50);       // ← M7 停靠节点探索值
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        // CORE OWN-C03-13：复制写入节点后须生成有效身份，否则 fail-closed；夹具按真实插入回填 id。
        doAnswer(inv -> {
            ((CmsTopicNode) inv.getArgument(0)).setId(41L);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());

        Map<String, Object> params = new HashMap<>();
        params.put("memberId", MEMBER);
        params.put("copyType", 2);

        com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(svc).copyCmsTopic(ORIGINAL, params);

        ArgumentCaptor<CmsTopicNode> cap = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeMapper).insertCmsTopicNode(cap.capture());
        CmsTopicNode copied = cap.getValue();
        assertEquals(Long.valueOf(77L), copied.getTemplateId(), "游戏(template_id)必须跟着移交");
        assertEquals(Integer.valueOf(50), copied.getXpValue(), "节点 XP 必须跟着移交,不能静默丢");
        assertEquals(Long.valueOf(21L), copied.getChapterId(), "节点要挂到新章节上");
        assertEquals(NEW_TOPIC, copied.getTopicId(), "节点要挂到新主题上");
    }
}
