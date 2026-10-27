package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.vo.ai.NpcProfileVO;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.support.MerchantNpcIdentityDirectory;

/**
 * NPC 出场解析服务。
 *
 * @author chengyinhub
 */
@Service
public class NpcProfileService
{
    /** scope_type=4 = 主题节点 NPC(挂 cms_topic_node.id)。与 NodeNpcService.SCOPE_NODE 同值。 */
    public static final int SCOPE_NODE = 4;

    @Autowired
    private NpcProfileMapper npcProfileMapper;

    @Autowired
    private MerchantNpcIdentityDirectory merchantNpcIdentityDirectory;

    /**
     * 取全局 NPC(scope_type=3,常驻地图/我的页)。
     */
    public NpcProfileVO resolveGlobal()
    {
        NpcProfile p = npcProfileMapper.selectGlobalNpc();
        return p == null ? null : toVo(p);
    }

    /**
     * 取局内 NPC(活动角色 scope_type=1 + 门店分身 scope_type=2)。
     */
    public List<NpcProfileVO> resolveForActivity(Long activityId)
    {
        List<NpcProfile> profiles = npcProfileMapper.selectEnabledByScope(1, activityId);
        List<NpcProfileVO> vos = new ArrayList<>();
        if (profiles != null)
        {
            for (NpcProfile p : profiles)
            {
                vos.add(toVo(p));
            }
        }
        return vos;
    }

    /**
     * 取门店分身(scope_type=2,挂 merchantId)。
     * 一个门店允许配多条(sort 定顺序),店铺主页静态出场只用第一条。
     */
    public NpcProfileVO resolveForMerchant(Long merchantId)
    {
        if (merchantId == null)
        {
            return null;
        }
        List<NpcProfile> profiles = npcProfileMapper.selectEnabledByScope(2, merchantId);
        if (profiles == null || profiles.isEmpty())
        {
            return null;
        }
        return toVo(profiles.get(0));
    }

    /**
     * 取主题节点 NPC(scope_type=4,挂 cms_topic_node.id)。玩家读 → 只可能拿到
     * {@code selectEnabledByScope} 已过滤过的「audit_status=1 AND enabled=1」版本。
     */
    public NpcProfileVO resolveForNode(Long nodeId)
    {
        if (nodeId == null)
        {
            return null;
        }
        List<NpcProfile> profiles = npcProfileMapper.selectEnabledByScope(SCOPE_NODE, nodeId);
        if (profiles == null || profiles.isEmpty())
        {
            return null;
        }
        return toVo(profiles.get(0));
    }

    /**
     * 节点上出场的 NPC:优先节点级(已过审),没有才回落门店分身。
     *
     * <p>回落的理由:存量商家节点(2026-09-15 之前)没有节点 NPC,门店分身在自由探索里
     * 一直是「店铺替身」的出场者 —— 直接切到节点级会让这些店的分身凭空消失。
     * 两个 scope 都走同一道读闸(audit_status=1 AND enabled=1 AND del_flag='0')。
     */
    public NpcProfileVO resolveForNodeOrMerchant(Long nodeId, Long merchantMemberId)
    {
        NpcProfileVO nodeNpc = resolveForNode(nodeId);
        if (nodeNpc != null || merchantMemberId == null)
        {
            return nodeNpc;
        }
        // 节点上记的是商家 member_id,门店分身挂的是 mms_merchant.id:先解析唯一可公开商户行,不能拿 member_id 当商户主键查。
        return resolveForMerchant(merchantNpcIdentityDirectory.availableMerchantIdForMember(merchantMemberId));
    }

    /**
     * 节点上记的商家 member_id 对应的门店分身列表。门店分身挂的是 mms_merchant.id(保存端
     * /api/merchant/npc/save 写的 scope_id),拿 member_id 直接当 scope_id 查永远读不到(M-28)。
     */
    public List<NpcProfileVO> resolveEnabledListForMerchantMember(Long merchantMemberId)
    {
        return resolveEnabledList(2, merchantNpcIdentityDirectory.availableMerchantIdForMember(merchantMemberId));
    }

    /**
     * 取某作用域下全部启用 NPC(读闸同 {@link #resolveForNodeOrMerchant}:audit_status=1 AND
     * enabled=1 AND del_flag='0'),按 sort / 默认序。R11-A 叙事段按 id 解析时用它做主题归属核对
     * —— 只看第一个会漏掉同节点/同门店的第二条。
     */
    public List<NpcProfileVO> resolveEnabledList(int scopeType, Long scopeId)
    {
        List<NpcProfileVO> vos = new ArrayList<>();
        if (scopeId == null)
        {
            return vos;
        }
        List<NpcProfile> profiles = npcProfileMapper.selectEnabledByScope(scopeType, scopeId);
        if (profiles != null)
        {
            for (NpcProfile p : profiles)
            {
                vos.add(toVo(p));
            }
        }
        return vos;
    }

    private NpcProfileVO toVo(NpcProfile p)
    {
        NpcProfileVO vo = new NpcProfileVO();
        vo.setProfileId(p.getId());
        vo.setName(p.getName());
        vo.setAvatar(p.getAvatar());
        vo.setScopeType(p.getScopeType());
        vo.setSort(p.getSort());
        vo.setGreeting(p.getGreeting());
        return vo;
    }
}
