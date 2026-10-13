package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.vo.NodeMerchantOverride;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.INodeMerchantOverrideService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NodeMerchantOverrideServiceImpl implements INodeMerchantOverrideService {

    private final ICmsRegistrationMerchantService registrationMerchantService;

    public NodeMerchantOverrideServiceImpl(ICmsRegistrationMerchantService registrationMerchantService) {
        this.registrationMerchantService = registrationMerchantService;
    }

    @Override
    public NodeMerchantOverride forNode(Long nodeId) {
        if (nodeId == null || nodeId <= 0) {
            return NodeMerchantOverride.empty();
        }
        CmsRegistrationMerchant probe = new CmsRegistrationMerchant();
        probe.setNodeId(nodeId);
        // 中标判据 = audit_status(见 CmsRegistrationMerchantMapper#confirmWinnerIfPending:
        // 它同时置 audit_status=1 和 status=1)。这里只认 audit_status ——
        // status 在这张表上是「报名进程」,语义比 audit_status 松,两个都判反而多一处会漂的地方。
        probe.setAuditStatus(1L);
        probe.setDelFlag(0);
        List<CmsRegistrationMerchant> won = registrationMerchantService.selectCmsRegistrationMerchantList(probe);
        if (won == null || won.isEmpty()) {
            return NodeMerchantOverride.empty();
        }
        // 竞争位保证一节点至多一个中标商家(win_key 唯一约束 + slotHasOtherWinner 读守卫)。
        // 真出现多行说明约束被绕过了 —— 取第一条而不是抛错:玩家正在店里玩,
        // 这里炸掉等于把数据问题变成一次玩家侧故障。异常态由 win_key 和调配侧兜。
        CmsRegistrationMerchant r = won.get(0);
        return new NodeMerchantOverride(
                r.getMemberId(), r.getRuleInstructions(), r.getFeedbackText(), r.getCouponId());
    }
}
