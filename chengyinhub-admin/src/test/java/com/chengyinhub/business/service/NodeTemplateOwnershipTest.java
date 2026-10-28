package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.dto.NodeDTO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W4 · 节点模板越权 —— {@code saveNodes} 原样 copyProperties 落 templateId,零归属校验。
 *
 * <p><b>为什么这是洞(而不是「模板本来就是公共的」)</b>:仓库里有两套模板,别混:
 * <ul>
 *   <li>{@code CmsTemplateLibrary} = <b>公共模板库</b>({@code /api/template/list} 与 {@code /info},
 *       按 status=1+publishStatus=1 过滤、<b>不按 memberId</b>)—— 它就是给所有人用的。</li>
 *   <li>{@code CmsMemberTemplate} = <b>我的模板</b>(publish 时绑 memberId,连重名都按人查)。</li>
 * </ul>
 * 而节点的 templateId 指向的是<b>后者</b>({@code ApiRegistrationMerchantController:88/179}
 * 拿它调 {@code cmsMemberTemplateService.selectCmsMemberTemplateById}),且小程序发布页选模板调的是
 * {@code /api/template/my-list}(fabu/index.js:3329)—— <b>本来就只该挂自己的</b>。
 * 后端不校验 ⇒ 绕过前端直接构造请求即可挂他人模板。
 *
 * <p><b>校验口径对齐 my-list 的引用分支</b>({@code ApiTemplateController:216-224}):
 * 引用发布时前端取的是 <b>memberId=自己 + status=1(已审核) + draftStatus=1(非草稿)</b>,
 * 后端校验必须是同三条 —— 只判归属会放过「自己的草稿/未审核模板」,那是另一种绕过前端。
 *
 * <p>⚠️ 负控:去掉 assertTemplateOwned 调用 ⇒ {@code othersTemplate_rejected} 红。
 */
class NodeTemplateOwnershipTest {

    private static final Long ME = 100L;
    private static final Long OTHER = 200L;
    private static final Long TPL = 55L;
    private static final Long TOPIC = 700L;

    private CmsMemberTemplateMapper templateMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicServiceImpl service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(CmsMemberTemplateMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "cmsMemberTemplateMapper", templateMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        // CORE OWN-C03-13：节点插入后须生成有效身份，否则 fail-closed；夹具按真实插入回填自增 id。
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(9000L);
        when(nodeMapper.insertCmsTopicNode(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            ((com.chengyinhub.business.domain.CmsTopicNode) inv.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        });
    }

    private CmsMemberTemplate tpl(Long owner, Long status, Integer draftStatus) {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setId(TPL);
        t.setMemberId(owner);
        t.setStatus(status);
        t.setDraftStatus(draftStatus);
        t.setDelFlag(0);
        return t;
    }

    private NodeDTO node(Long templateId) {
        NodeDTO n = new NodeDTO();
        n.setName("测试节点");
        n.setTemplateId(templateId);
        return n;
    }

    private void save(List<NodeDTO> nodes, Long memberId) {
        ReflectionTestUtils.invokeMethod(com.chengyinhub.business.service.support.TopicGraphTestWiring.graph(service), "saveNodes", nodes, TOPIC, 1L, "tester", memberId);
    }

    /** ★挂他人模板 ⇒ 拒。这是洞本身。 */
    @Test
    void othersTemplate_rejected() {
        when(templateMapper.selectCmsMemberTemplateById(TPL)).thenReturn(tpl(OTHER, 1L, 1));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> save(Collections.singletonList(node(TPL)), ME));
        assertTrue(e.getMessage().contains("模板"), "实际:" + e.getMessage());
    }

    /** 挂自己的已发布模板 ⇒ 放行(正常主链路,拦了就是打挂发布)。 */
    @Test
    void ownTemplate_passes() {
        when(templateMapper.selectCmsMemberTemplateById(TPL)).thenReturn(tpl(ME, 1L, 1));
        save(Collections.singletonList(node(TPL)), ME);
    }

    /** templateId=0 / null ⇒ 放行。前端默认就是 0(fabu/index.js:95「templateId: 0」)。 */
    @Test
    void noTemplate_passes() {
        save(Collections.singletonList(node(0L)), ME);
        save(Collections.singletonList(node(null)), ME);
    }

    /** 自己的**草稿**模板 ⇒ 拒。my-list 的引用分支明确 draftStatus=1,只判归属会放过它。 */
    @Test
    void ownDraftTemplate_rejected() {
        when(templateMapper.selectCmsMemberTemplateById(TPL)).thenReturn(tpl(ME, 1L, 0));
        assertThrows(RuntimeException.class, () -> save(Collections.singletonList(node(TPL)), ME));
    }

    /** 自己的**未审核**模板 ⇒ 拒。同上,引用分支要 status=1。 */
    @Test
    void ownUnapprovedTemplate_rejected() {
        when(templateMapper.selectCmsMemberTemplateById(TPL)).thenReturn(tpl(ME, 0L, 1));
        assertThrows(RuntimeException.class, () -> save(Collections.singletonList(node(TPL)), ME));
    }

    /** 模板不存在 ⇒ 拒(fail-closed,别让脏 id 落库)。 */
    @Test
    void missingTemplate_rejected() {
        when(templateMapper.selectCmsMemberTemplateById(TPL)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> save(Collections.singletonList(node(TPL)), ME));
    }
}
