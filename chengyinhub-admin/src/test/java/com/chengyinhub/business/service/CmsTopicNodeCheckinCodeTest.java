package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.impl.CmsTopicNodeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 5 · T1:走标准 CRUD 建的节点必须自带打卡码。
 *
 * <p>为什么是 P0 级:{@code CmsTopicNodeController.add} 是后台建节点的唯一入口,
 * 而它以前不生成 {@code checkin_code} ⇒ 落 NULL ⇒ 玩家扫店内静态码时
 * {@code /api/play/checkin} 按码反查取不到行 ⇒ 进店永远建不起来、{@code arrived} 恒 false,
 * 且**零报错**。四处既有生成点(复制主题 / 工作台批量建 / 单节点新建 / B1 二期复制)
 * 都各自 setCheckinCode,唯独标准 CRUD 这条路漏了。
 *
 * <p>闸放在 Service 而不是 Controller:商家自助点位({@code ChapterMerchantNodeServiceImpl.submit})
 * 也走同一个 {@code insertCmsTopicNode},闸在 Controller 会把它漏掉。
 *
 * <p>唯一性由迁移里的唯一索引承重(见 migration_20260817_tandianri_node_checkin_code_unique.sql);
 * 本类只钉「生成」这一半 —— 形状(32 位十六进制)、不可猜(逐次互不相同)、显式传入不被覆盖。
 */
class CmsTopicNodeCheckinCodeTest {

    private CmsTopicNodeServiceImpl service(CmsTopicNodeMapper mapper) {
        CmsTopicNodeServiceImpl impl = new CmsTopicNodeServiceImpl();
        ReflectionTestUtils.setField(impl, "cmsTopicNodeMapper", mapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.node(impl);
        return impl;
    }

    private CmsTopicNode draft() {
        CmsTopicNode node = new CmsTopicNode();
        node.setTopicId(100L);
        node.setChapterId(9L);
        node.setName("第一站");
        return node;
    }

    @Test
    void standardCrudInsert_generatesUnguessableCheckinCode() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        when(mapper.insertCmsTopicNode(any())).thenReturn(1);
        CmsTopicNode node = draft();

        service(mapper).insertCmsTopicNode(node);

        assertNotNull(node.getCheckinCode(), "标准 CRUD 建的节点必须带打卡码,否则玩家永远扫不出来");
        assertTrue(node.getCheckinCode().matches("[0-9a-f]{32}"),
                "打卡码形状必须是 32 位十六进制(与既有四处生成点同口径),实际:" + node.getCheckinCode());
    }

    /** 「不可猜」的可测代理:同一 topic/chapter 反复建,码必须两两互不相同。 */
    @Test
    void generatedCheckinCode_isNotDerivedFromNodeIdentity() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        when(mapper.insertCmsTopicNode(any())).thenReturn(1);
        CmsTopicNodeServiceImpl service = service(mapper);

        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            CmsTopicNode node = draft();
            service.insertCmsTopicNode(node);
            codes.add(node.getCheckinCode());
        }
        assertEquals(200, codes.size(), "200 次生成必须两两不同;有重复说明码是可推导的");
    }

    /** 已显式指定的码(复制主题 / 工作台批量建 / B1 二期复制)不得被覆写。 */
    @Test
    void explicitCheckinCode_isPreserved() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        when(mapper.insertCmsTopicNode(any())).thenReturn(1);
        CmsTopicNode node = draft();
        node.setCheckinCode("aaaaaaaabbbbbbbbccccccccdddddddd");

        service(mapper).insertCmsTopicNode(node);

        assertEquals("aaaaaaaabbbbbbbbccccccccdddddddd", node.getCheckinCode());
    }

    /**
     * 存量空码在编辑时补齐。
     *
     * <p>迁移只回填生产库;开发/测试库以及「本次迁移之后又被谁清空」的行仍会是空码,
     * 而空码节点在页面上看起来完全正常 —— 补在这里,编辑一次即自愈。
     */
    @Test
    void updateBackfillsBlankCheckinCode() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        CmsTopicNode existing = draft();
        existing.setId(7L);
        existing.setCheckinCode("");
        when(mapper.selectCmsTopicNodeById(7L)).thenReturn(existing);
        when(mapper.updateCmsTopicNode(any())).thenReturn(1);

        CmsTopicNode edit = draft();
        edit.setId(7L);
        service(mapper).updateCmsTopicNode(edit);

        assertNotNull(edit.getCheckinCode(), "存量空码节点编辑后必须补上码");
        assertTrue(edit.getCheckinCode().matches("[0-9a-f]{32}"));
    }

    /** 已有码的节点编辑时不得换码 —— 换了等于让店里已印好的海报全部作废。 */
    @Test
    void updateNeverRotatesExistingCheckinCode() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        CmsTopicNode existing = draft();
        existing.setId(7L);
        existing.setCheckinCode("aaaaaaaabbbbbbbbccccccccdddddddd");
        when(mapper.selectCmsTopicNodeById(7L)).thenReturn(existing);
        when(mapper.updateCmsTopicNode(any())).thenReturn(1);

        CmsTopicNode edit = draft();
        edit.setId(7L);
        service(mapper).updateCmsTopicNode(edit);

        assertEquals(null, edit.getCheckinCode(),
                "没传码就不该动码:mapper 的 <if test=\"checkinCode != null\"> 会跳过该列,原码保持不变");
    }

    /**
     * 表单里那个空输入框不许把已印好的码抹掉。
     *
     * <p>mapper 的 {@code <if test="checkinCode != null">} 判的是 null 不是空白 ⇒
     * 传空串会真的 {@code set checkin_code = ''},而抹完页面上看不出任何异常,
     * 玩家扫码那天才发现。归一成 null 才是「不改」。
     */
    @Test
    void updateWithBlankStringDoesNotWipeExistingCode() {
        CmsTopicNodeMapper mapper = mock(CmsTopicNodeMapper.class);
        CmsTopicNode existing = draft();
        existing.setId(7L);
        existing.setCheckinCode("aaaaaaaabbbbbbbbccccccccdddddddd");
        when(mapper.selectCmsTopicNodeById(7L)).thenReturn(existing);
        when(mapper.updateCmsTopicNode(any())).thenReturn(1);

        CmsTopicNode edit = draft();
        edit.setId(7L);
        edit.setCheckinCode("   ");
        service(mapper).updateCmsTopicNode(edit);

        assertEquals(null, edit.getCheckinCode(), "空串必须归一成 null,否则会把已印好的码写成空");
    }
}
