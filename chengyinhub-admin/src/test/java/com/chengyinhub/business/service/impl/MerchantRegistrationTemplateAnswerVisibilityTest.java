package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.ViewRegistrationMerchant;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationScanService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.IViewRegistrationMerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 核销答案的可见性闸：只有中标商家看得到明文。
 *
 * 口径 2026-08-06 用户拍板「商家可见」—— 商家现场就是靠对暗号核销的，看不到答案这活干不了。
 * 但「商家」不等于「任何提交过报名的人」：待审核 / 已驳回 / 待调配的商家拿到答案，
 * 等于任何人报个名就能把这条路线的所有谜底导出去。
 *
 * 判据是 auditStatus==1（已中标），不是 status、不是「报名存在」。
 * 前端对此没有任何判断，完全依赖这一层，所以这道闸破了不会有第二层兜住。
 */
class MerchantRegistrationTemplateAnswerVisibilityTest {

    private static final Long ME = 88L;
    private static final Long REG = 900L;
    private static final Long NODE = 500L;
    private static final Long TPL = 700L;

    private IViewRegistrationMerchantService viewSvc;
    private ICmsRegistrationMerchantService regSvc;
    private ICmsTopicNodeService nodeSvc;
    private ICmsMemberTemplateService tplSvc;
    private MerchantRegistrationDetailReadServiceImpl service;

    @BeforeEach
    void setUp() {
        viewSvc = mock(IViewRegistrationMerchantService.class);
        regSvc = mock(ICmsRegistrationMerchantService.class);
        nodeSvc = mock(ICmsTopicNodeService.class);
        tplSvc = mock(ICmsMemberTemplateService.class);
        ICmsTopicChapterService chapterSvc = mock(ICmsTopicChapterService.class);
        ICmsRegistrationService registrationSvc = mock(ICmsRegistrationService.class);
        ICmsRegistrationScanService scanSvc = mock(ICmsRegistrationScanService.class);

        when(registrationSvc.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        when(scanSvc.selectCmsRegistrationScanList(any())).thenReturn(Collections.emptyList());

        service = new MerchantRegistrationDetailReadServiceImpl(
                viewSvc, regSvc, nodeSvc, tplSvc, chapterSvc, registrationSvc, scanSvc);
    }

    /** @param auditStatus 1=已中标，其余=还没中标（待审/驳回/待调配） */
    private void given(Long auditStatus) {
        ViewRegistrationMerchant v = new ViewRegistrationMerchant();
        v.setId(REG);
        v.setMemberId(ME);
        v.setTopicId(300L);
        v.setNodeId(NODE);
        when(viewSvc.selectViewRegistrationMerchantById(REG)).thenReturn(v);

        CmsRegistrationMerchant raw = new CmsRegistrationMerchant();
        raw.setId(REG);
        raw.setMemberId(ME);
        raw.setAuditStatus(auditStatus);
        when(regSvc.selectCmsRegistrationMerchantById(REG)).thenReturn(raw);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE);
        node.setName("第三站");
        node.setTemplateId(TPL);
        when(nodeSvc.selectCmsTopicNodeById(NODE)).thenReturn(node);

        // 每次都造一个新实体：strip 是原地改对象，复用会让上一个用例的剥离污染下一个
        CmsMemberTemplate tpl = new CmsMemberTemplate();
        tpl.setId(TPL);
        tpl.setTitle("暗号对答");
        tpl.setQuestionAnswer("夜航船");
        tpl.setCorrectAnswer("C");
        tpl.setHint1("看吧台");
        tpl.setHint2("问店员");
        when(tplSvc.selectCmsMemberTemplateById(TPL)).thenReturn(tpl);
    }

    /** 中标商家：明文放行，否则现场没法对暗号。 */
    @Test
    @Timeout(5)
    void assignedMerchant_seesPlaintextAnswer() {
        given(1L);
        CmsMemberTemplate got = service.getRegistrationDetail(ME, REG).getTemplateDetail();
        assertEquals("夜航船", got.getQuestionAnswer(), "中标商家看不到答案就核销不了");
        assertEquals("C", got.getCorrectAnswer());
        // ⚠️ 这里**故意不断言** hint1/hint2/answerReveal。
        // 现行 merchantView 是原样放行,提示也跟着出去了;但拍板的原话是「核销答案对商家可见」,
        // 而 TemplateSecrets 自己的注释把 hint 定性为「积分解锁付费墙内的内容」—— 那不是核销素材。
        // 把它断言成「应该可见」等于替用户追认一个没拍过的口径,所以留白待裁决(见 §待办)。
    }

    /**
     * ★负控：待审核的商家必须拿不到答案。
     * 这条要是绿不了，就意味着任何人报个名就能把整条路线的谜底导出去。
     */
    @Test
    @Timeout(5)
    void pendingMerchant_answerStripped() {
        given(0L);
        CmsMemberTemplate got = service.getRegistrationDetail(ME, REG).getTemplateDetail();
        assertNull(got.getQuestionAnswer(), "还没中标就拿到答案 = 报名即可泄题");
        assertNull(got.getCorrectAnswer());
        assertNull(got.getHint1());
        assertNull(got.getHint2());
        assertEquals("暗号对答", got.getTitle(), "剥的是答案不是整个模板,标题还得有");
    }

    /** ★负控：被驳回的商家同样拿不到 —— 驳回后还能看谜底,驳回就没意义。 */
    @Test
    @Timeout(5)
    void rejectedMerchant_answerStripped() {
        given(2L);
        assertNull(service.getRegistrationDetail(ME, REG).getTemplateDetail().getQuestionAnswer());
    }

    /** ★负控：auditStatus 为 null（老数据/待调配）时按「没中标」处理,fail-closed。 */
    @Test
    @Timeout(5)
    void nullAuditStatus_failsClosed() {
        given(null);
        assertNull(service.getRegistrationDetail(ME, REG).getTemplateDetail().getQuestionAnswer(),
                "拿不准的一律当没中标,不能默认放行");
    }
}
