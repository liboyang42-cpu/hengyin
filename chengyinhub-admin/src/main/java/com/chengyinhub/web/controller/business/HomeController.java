package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.vo.HomeDataVO;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/business/home")
public class HomeController extends BaseController {
    @Autowired
    private IUmsMemberService umsMemberService;

    /**
     * 后台首页看板。
     *
     * <p>这是全后台 476 个业务端点里**唯一**一个曾经没有任何权限注解的
     * —— 于是它压根不在 {@code ci/role_permission_contract.py} 扫描的权限宇宙里，
     * 「谁能看」从来没被决定过，只是没人拦。挂 {@code business:analytics:list}：
     * 四个角色都持有这一位，没有任何角色因此丢掉首页；作用是把它拉回权限宇宙，
     * 让契约门禁看得见它（新增的第三段审计要求每个业务端点都有权限注解）。
     *
     * <p>同时删掉了 {@code totalSalesAmount}（平台全量已支付营收）：它从来没被渲染过
     * —— index.vue 只读 merchantAuditNum / creativeSquareNum / topicNum / activityNum /
     * memberTemplateNum 五个 —— 却下发给了包括 auditor 在内的所有角色，
     * 而 auditor 正是 role_permission_contract 明确挡在全部资金/PII 权限之外的那个。
     * 没人消费的数据不该加权限门，该删掉（同 X09 的假开关、X03 的 nodeTypes）。
     */
    @PreAuthorize("@ss.hasPermi('business:analytics:list')")
    @GetMapping(value = "/data")
    public AjaxResult getHomeData() {
        HomeDataVO homeDataVO = umsMemberService.selectHomeDataList();

        HashMap<String, String> hashMap = new HashMap<>();
        hashMap.put("userNum", homeDataVO.getUserNum() + "");
        hashMap.put("merchantNum", homeDataVO.getMerchantNum() + "");
        hashMap.put("merchantAuditNum", homeDataVO.getMerchantAuditNum() + "");
        hashMap.put("creativeSquareNum", homeDataVO.getCreativeSquareNum() + "");
        hashMap.put("topicNum", homeDataVO.getTopicNum() + "");
        hashMap.put("activityNum", homeDataVO.getActivityNum() + "");
        hashMap.put("memberTemplateNum", homeDataVO.getMemberTemplateNum() + "");

        return success(hashMap);
    }
}
