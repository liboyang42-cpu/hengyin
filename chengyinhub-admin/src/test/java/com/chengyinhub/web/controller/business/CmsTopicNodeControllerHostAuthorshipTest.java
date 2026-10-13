package com.chengyinhub.web.controller.business;

import java.io.InputStream;

import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.impl.ChapterMerchantNodeServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 5:平台从零建/改探店日节点（首三期平台组局，不开放商家自助发布）。
 *
 * <p>唯一容易静默出错的一处:{@code CmsTopicNodeMapper} 的可见性闸是
 * 「{@code merchant_member_id is null} 或 {@code node_audit_status = 1}」。
 * 后台绑了商家却没置 1 ⇒ 该点位**在玩家侧消失**、后台列表里照旧显示 ⇒ 整章空掉且零报错。
 * 所以「新建或首次绑商家时按已过审留痕」必须由服务端强制，不能指望前端记得传；
 * 编辑既有商家点位则必须省略审核列，保留专用审核入口写下的原值。
 */
class CmsTopicNodeControllerHostAuthorshipTest {

    private static final long TOPIC = 100L;
    private static final long CHAPTER = 9L;
    private static final long MERCHANT = 8801L;

    private ICmsTopicNodeService nodeService;
    private ICmsTopicChapterService chapterService;
    private ITopicOperationsScopeAuthorizer authorizer;
    private CmsTopicNodeController controller;

    @BeforeEach
    void setUp() {
        nodeService = mock(ICmsTopicNodeService.class);
        chapterService = mock(ICmsTopicChapterService.class);
        authorizer = mock(ITopicOperationsScopeAuthorizer.class);
        controller = new CmsTopicNodeController();
        ReflectionTestUtils.setField(controller, "cmsTopicNodeService", nodeService);
        ReflectionTestUtils.setField(controller, "cmsTopicChapterService", chapterService);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", authorizer);
        when(authorizer.canViewTopic(TOPIC)).thenReturn(true);
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        when(chapterService.selectCmsTopicChapterById(CHAPTER)).thenReturn(chapter);
        when(nodeService.insertCmsTopicNode(any())).thenReturn(1);
        when(nodeService.updateCmsTopicNode(any())).thenReturn(1);
    }

    private CmsTopicNode body(Long merchantMemberId) {
        CmsTopicNode node = new CmsTopicNode();
        node.setTopicId(TOPIC);
        node.setChapterId(CHAPTER);
        node.setName("毛孩子咖啡");
        node.setMerchantMemberId(merchantMemberId);
        return node;
    }

    private CmsTopicNode captureInserted() {
        ArgumentCaptor<CmsTopicNode> captor = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeService).insertCmsTopicNode(captor.capture());
        return captor.getValue();
    }

    private CmsTopicNode captureUpdated() {
        ArgumentCaptor<CmsTopicNode> captor = ArgumentCaptor.forClass(CmsTopicNode.class);
        verify(nodeService).updateCmsTopicNode(captor.capture());
        return captor.getValue();
    }

    /** 不连 DB,但走真实 MyBatis mapper 动态 SQL，证明 null 审核列不会进入 UPDATE。 */
    private String renderedUpdateSql(CmsTopicNode node) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CmsTopicNode", CmsTopicNode.class);
        String resource = "mapper/business/CmsTopicNodeMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement(
                "com.chengyinhub.business.mapper.CmsTopicNodeMapper.updateCmsTopicNode")
                .getBoundSql(node).getSql().replaceAll("\\s+", " ").trim();
    }

    private void assertAuditColumnsOmittedFromUpdate(CmsTopicNode node) throws Exception {
        String sql = renderedUpdateSql(node);
        assertTrue(sql.contains("name = ?"), sql);
        assertFalse(sql.contains("node_audit_status"), sql);
        assertFalse(sql.contains("node_audit_reason"), sql);
        assertFalse(sql.contains("node_audit_member_id"), sql);
        assertFalse(sql.contains("node_audit_time"), sql);
    }

    @Test
    void platformBoundMerchantNodeIsBornApproved() {
        assertEquals(200, controller.add(body(MERCHANT)).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureInserted();
        assertEquals(MERCHANT, saved.getMerchantMemberId().longValue());
        assertEquals(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED, saved.getNodeAuditStatus().intValue(),
                "绑了商家又不置已过审 ⇒ 该点位在玩家侧静默消失");
        assertNotNull(saved.getNodeAuditTime());
        assertEquals(0L, saved.getNodeAuditMemberId().longValue(),
                "那一列是 memberId 语义,别把后台 SysUser.userId 塞进去混读");
    }

    /** 主办方自建点位:审核态整组留空,免得请求体夹一个 0 造出「主办方点位待审」这种不存在的状态。 */
    @Test
    void hostOwnNodeKeepsAuditColumnsEmpty() {
        CmsTopicNode node = body(null);
        node.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        node.setNodeAuditReason("请求体夹带的");

        controller.add(node);

        CmsTopicNode saved = captureInserted();
        assertNull(saved.getNodeAuditStatus());
        assertNull(saved.getNodeAuditReason());
        assertNull(saved.getNodeAuditMemberId());
        assertNull(saved.getNodeAuditTime());
    }

    /** 主办方既有点位首次合法绑定商家时自动过审,避免绑定后在玩家侧静默消失。 */
    @Test
    void firstMerchantBindingIsApproved() {
        CmsTopicNode existing = body(null);
        existing.setId(76L);
        when(nodeService.selectCmsTopicNodeById(76L)).thenReturn(existing);

        CmsTopicNode edit = body(MERCHANT);
        edit.setId(76L);
        assertEquals(200, controller.edit(edit).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureUpdated();
        assertEquals(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED, saved.getNodeAuditStatus().intValue());
        assertEquals(0L, saved.getNodeAuditMemberId().longValue());
        assertNotNull(saved.getNodeAuditTime());
    }

    /** 换绑到另一商家必须重新待审，并由真实 UPDATE 清空上一家的审核留痕。 */
    @Test
    void rebindingMerchantResetsApprovalAndClearsPreviousAuditTrace() throws Exception {
        CmsTopicNode existing = body(MERCHANT);
        existing.setId(80L);
        existing.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        existing.setNodeAuditReason("上一家已过审");
        existing.setNodeAuditMemberId(8802L);
        existing.setNodeAuditTime(new java.util.Date(1_700_000_000_000L));
        when(nodeService.selectCmsTopicNodeById(80L)).thenReturn(existing);

        CmsTopicNode edit = body(9901L);
        edit.setId(80L);
        assertEquals(200, controller.edit(edit).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureUpdated();
        assertEquals(Integer.valueOf(ChapterMerchantNodeServiceImpl.AUDIT_PENDING), saved.getNodeAuditStatus(),
                "A 的过审结论不能被 B 继承");
        assertNull(saved.getNodeAuditReason());
        assertNull(saved.getNodeAuditMemberId());
        assertNull(saved.getNodeAuditTime());

        String sql = renderedUpdateSql(saved);
        assertTrue(sql.contains("merchant_member_id = ?"), sql);
        assertTrue(sql.contains("node_audit_status = ?"), sql);
        assertTrue(sql.contains("node_audit_reason = null"), sql);
        assertTrue(sql.contains("node_audit_member_id = null"), sql);
        assertTrue(sql.contains("node_audit_time = null"), sql);
    }

    /** 编辑已过审的商家点位:审核列必须整组省略,由 mapper 保留数据库原值。 */
    @Test
    void editingAlreadyApprovedNodePreservesAuditColumns() {
        CmsTopicNode existing = body(MERCHANT);
        existing.setId(77L);
        existing.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_APPROVED);
        when(nodeService.selectCmsTopicNodeById(77L)).thenReturn(existing);

        CmsTopicNode edit = body(null);   // 表单只改了名字，没回传归属
        edit.setId(77L);
        edit.setName("毛孩子咖啡(改名)");
        assertEquals(200, controller.edit(edit).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureUpdated();
        assertNull(saved.getNodeAuditStatus(), "更新对象必须省略审核态,让 mapper 保留数据库里的已过审状态");
        assertNull(saved.getNodeAuditReason());
        assertNull(saved.getNodeAuditMemberId());
        assertNull(saved.getNodeAuditTime());
    }

    /** 编辑待审商家点位不得借普通编辑入口自动过审。 */
    @Test
    void editingPendingNodePreservesAuditColumns() throws Exception {
        CmsTopicNode existing = body(MERCHANT);
        existing.setId(78L);
        existing.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_PENDING);
        existing.setNodeAuditReason("等待审核");
        existing.setNodeAuditMemberId(8802L);
        existing.setNodeAuditTime(new java.util.Date(1_700_000_000_000L));
        when(nodeService.selectCmsTopicNodeById(78L)).thenReturn(existing);

        CmsTopicNode edit = body(null);
        edit.setId(78L);
        edit.setName("待审点位(改名)");
        assertEquals(200, controller.edit(edit).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureUpdated();
        assertNull(saved.getNodeAuditStatus(), "更新对象必须省略审核态,让 mapper 保留数据库里的待审状态");
        assertNull(saved.getNodeAuditReason(), "更新对象必须省略理由,让 mapper 保留数据库里的待审理由");
        assertNull(saved.getNodeAuditMemberId());
        assertNull(saved.getNodeAuditTime());
        assertAuditColumnsOmittedFromUpdate(saved);
    }

    /** 编辑已驳回商家点位不得清空驳回理由或自动过审。 */
    @Test
    void editingRejectedNodePreservesStatusAndReason() throws Exception {
        CmsTopicNode existing = body(MERCHANT);
        existing.setId(79L);
        existing.setNodeAuditStatus(ChapterMerchantNodeServiceImpl.AUDIT_REJECTED);
        existing.setNodeAuditReason("门店照片无法核验");
        existing.setNodeAuditMemberId(8803L);
        existing.setNodeAuditTime(new java.util.Date(1_700_000_100_000L));
        when(nodeService.selectCmsTopicNodeById(79L)).thenReturn(existing);

        CmsTopicNode edit = body(null);
        edit.setId(79L);
        edit.setName("驳回点位(改名)");
        assertEquals(200, controller.edit(edit).get(AjaxResult.CODE_TAG));

        CmsTopicNode saved = captureUpdated();
        assertNull(saved.getNodeAuditStatus(), "更新对象必须省略审核态,让 mapper 保留数据库里的驳回状态");
        assertNull(saved.getNodeAuditReason(), "更新对象必须省略理由,不能清空既有驳回原因");
        assertNull(saved.getNodeAuditMemberId());
        assertNull(saved.getNodeAuditTime());
        assertAuditColumnsOmittedFromUpdate(saved);
    }

    /** 范围闸不得被本次改动削弱:不在运营范围内的主题一律拒。 */
    @Test
    void outOfScopeTopicStillRejected() {
        when(authorizer.canViewTopic(TOPIC)).thenReturn(false);
        AjaxResult r = controller.add(body(MERCHANT));
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).insertCmsTopicNode(any());
    }

    /** 章节与主题必须自洽:章节不属于所传主题时拒,防跨主题挂点位。 */
    @Test
    void chapterTopicMismatchRejected() {
        CmsTopicNode node = body(MERCHANT);
        node.setTopicId(999L);
        AjaxResult r = controller.add(node);
        assertEquals(500, r.get(AjaxResult.CODE_TAG));
        verify(nodeService, never()).insertCmsTopicNode(any());
    }
}
