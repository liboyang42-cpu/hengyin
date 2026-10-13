package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.VerificationReview;
import com.chengyinhub.business.mapper.VerificationReviewMapper;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 核销异常人工复核队列 + 人工处置。
 *
 * <p><b>只做人审</b>:处置只翻 {@code verification_review.status},
 * 不改 {@code redemption}、不退款、不动任何资金,也不接自动扣款(审计 3 · T3)。
 *
 * <p>批 5 · T3:此前队列**只能看不能动** —— {@code status} 永远停在 0,
 * 而探店日一跑起来 burst 工单会持续入队,没有任何动作能把它们清掉。
 *
 * <p>范围过滤按审计 3 · B7:逐行核 {@code topic_id} 的运营范围。
 * 读用 {@code canViewTopic}、写用 {@code canEditTopic} —— 两条合同独立,
 * 即使当前实现结果相同,将来扩大受托读范围也不得自动获得处置权。
 */
@RestController
@RequestMapping("/business/verificationReview")
public class VerificationReviewController extends BaseController {
    @Autowired private VerificationReviewMapper reviewMapper;
    @Autowired private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @PreAuthorize("@ss.hasPermi('business:verificationReview:list')")
    @GetMapping("/list")
    public AjaxResult list(Integer status, Long topicId, Long verifierMemberId) {
        if (topicId != null && !topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            return success(new ArrayList<VerificationReview>());
        }
        VerificationReview query = new VerificationReview();
        query.setStatus(status);
        query.setTopicId(topicId);
        query.setVerifierMemberId(verifierMemberId);
        List<VerificationReview> rows = reviewMapper.selectQueue(query);
        // 逐行过滤而不是只在传了 topicId 时过滤:不传 topicId 是最常用的「看全部待复核」,
        // 那条路不过滤 = 范围闸对唯一真实使用方无效。
        List<VerificationReview> visible = new ArrayList<>();
        if (rows != null) {
            for (VerificationReview row : rows) {
                if (row != null && topicOperationsScopeAuthorizer.canViewTopic(row.getTopicId())) {
                    visible.add(row);
                }
            }
        }
        return success(visible);
    }

    /**
     * 人工处置:1 = 已确认正常,2 = 确认异常。
     *
     * <p>结论只收 1/2:收 0 等于「把处置过的退回待复核」,那是撤销不是处置,
     * 需要另一条带独立权限的路,不在本端点里顺手开。
     *
     * <p>操作留痕走 {@code @Log}(后台账号是 {@code SysUser},sys_oper_log 里能查到谁在何时处置了哪一条)。
     * 表上没有 handled_by 列 —— 不为此加列是刻意的:审计真源统一在 sys_oper_log,
     * 多一份就会有两份不一致的「谁处置的」。
     */
    @PreAuthorize("@ss.hasPermi('business:verificationReview:handle')")
    @Log(title = "核销异常复核", businessType = BusinessType.UPDATE)
    @PostMapping("/handle")
    public AjaxResult handle(Long id, Integer status) {
        if (id == null || id <= 0) return error("参数有误");
        if (status == null || (status != 1 && status != 2)) {
            return error("处置结论只能是 1(已确认正常) 或 2(确认异常)");
        }
        VerificationReview review = reviewMapper.selectById(id);
        // 对象归属:不能凭前端传的 topicId 判范围,必须先把行读出来拿它自己的 topic_id 判。
        if (review == null || review.getTopicId() == null
                || !topicOperationsScopeAuthorizer.canEditTopic(review.getTopicId())) {
            return error("工单不存在或当前账号无操作范围");
        }
        // 判过范围的那一行连同 status=0 一起写进 WHERE:并发/重复处置只会有一方拿到 1 行。
        int rows = reviewMapper.updateStatus(id, review.getTopicId(), status);
        return rows == 1 ? success("已处置") : error("该工单已被处置或状态已变化");
    }
}
