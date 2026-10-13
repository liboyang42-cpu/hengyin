package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.service.IClubChapterRecruitService;
import com.chengyinhub.business.service.ICmsTopicNodeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 俱乐部端「商家与客户」弹层的商家 tab(Figma J7)只需要一件事:
 * 这个主题的每个节点被哪家商家接了、审到哪一步、能不能给联系方式。
 *
 * ⚠️ 这里**不**管招募条件(品类/容量/条款/权益门槛)——那些在发布主题的章节弹窗里设,
 * 见「00 流程与弹层规则 · 读我」:编辑圆钮「只改开放商家承接与展示;主题内容去编辑主题页」。
 * 也不管章节招募开关:J4 的章节行按稿是只读的。
 */
@Service
public class ClubChapterRecruitServiceImpl implements IClubChapterRecruitService {
    private static final int NODE_PENDING = 0;
    private static final int NODE_APPROVED = 1;

    @Autowired private IClubAccessService clubAccessService;
    @Autowired private ICmsTopicService cmsTopicService;
    @Autowired private ICmsTopicNodeService cmsTopicNodeService;
    @Autowired private IMmsMerchantService mmsMerchantService;

    @Override
    public Map<String, Object> overview(Long principalMemberId, Long clubId, Long topicId) {
        ClubAccessContext access = requireTopic(principalMemberId, clubId, topicId);
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topicId", topicId);
        boolean canManage = access.hasPermission(ClubPermission.ACTIVITY_MANAGE);
        out.put("canManage", canManage);
        out.put("nodes", nodeRows(nodesOf(topicId), canManage));
        out.put("topicName", topic == null ? null : topic.getName());
        return out;
    }

    private ClubAccessContext requireTopic(Long principalMemberId, Long clubId, Long topicId) {
        if (principalMemberId == null) throw new ServiceException("请先登录");
        if (clubId == null || topicId == null) throw new ServiceException("缺少俱乐部或主题参数");
        CmsTopic topic = cmsTopicService.selectCmsTopicById(topicId);
        if (topic == null || topic.getClubId() == null || !topic.getClubId().equals(clubId)) {
            throw new ServiceException("该主题不属于本俱乐部", HttpStatus.FORBIDDEN);
        }
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, clubId, null);
        if (access == null || !access.hasPermission(ClubPermission.ACTIVITY_READ)) {
            throw new ServiceException("当前岗位没有这个权限", HttpStatus.FORBIDDEN);
        }
        return access;
    }

    private List<CmsTopicNode> nodesOf(Long topicId) {
        CmsTopicNode probe = new CmsTopicNode();
        probe.setTopicId(topicId);
        List<CmsTopicNode> nodes = cmsTopicNodeService.selectCmsTopicNodeList(probe);
        if (nodes == null) return Collections.emptyList();
        nodes.sort(Comparator.comparing(n -> n.getSortId() == null ? Long.MAX_VALUE : n.getSortId()));
        return nodes;
    }

    /**
     * @param canManage 是否下发商家联系方式。
     *
     * <p>★2026-09-11 收窄:这道接口的门槛是 {@code ACTIVITY_READ},而
     * {@link com.chengyinhub.business.access.club.ClubPermissionPolicy} 给<b>普通成员</b>就带了这一条 ——
     * 于是任何一个入了会的人直接打这个接口,都能拿到本团所有已确认商家的手机号。
     * 联系方式是给「要去对接的人」用的,不是给「能看见活动的人」用的,所以跟着 ACTIVITY_MANAGE 走。
     *
     * <p>⚠️ 与 {@code ClubCrmAppServiceImpl.phoneText} 那条「任何一档都不下发原始号码」<b>不是一回事</b>:
     * 那边是<b>玩家(客户)</b>的号码,俱乐部只有联系必要没有留存必要,所以连管理层也只给脱敏串;
     * 这边是<b>商家的经营联系方式</b>,已确认合作的管理层要照着它打电话对接站点,给脱敏串等于不给。
     * 判据是「谁的号、给谁看」,不是「有没有打码」。
     */
    private List<Map<String, Object>> nodeRows(List<CmsTopicNode> nodes, boolean canManage) {
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (CmsTopicNode node : nodes) {
            index++;
            Integer audit = node.getNodeAuditStatus();
            boolean hasMerchant = node.getMerchantMemberId() != null;
            MmsMerchant merchant = hasMerchant
                    ? mmsMerchantService.selectMmsMerchantByMemberId(node.getMerchantMemberId()) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.getId());
            item.put("chapterId", node.getChapterId());
            item.put("index", index);
            item.put("name", node.getName());
            item.put("merchantName", merchant == null ? null : merchant.getName());
            item.put("state", !hasMerchant ? "OPEN"
                    : (audit != null && audit == NODE_APPROVED ? "APPROVED"
                    : (audit != null && audit == NODE_PENDING ? "PENDING" : "REJECTED")));
            // ★ Figma J7:联系方式只在商家接受合作(节点审核通过)后才下发。
            //   未确认的商家**回包里没有 phone 这个键** —— 是服务端不给,不是前端不显示。
            if (canManage && audit != null && audit == NODE_APPROVED && merchant != null) {
                item.put("phone", merchant.getPhone());
            }
            out.add(item);
        }
        return out;
    }
}
