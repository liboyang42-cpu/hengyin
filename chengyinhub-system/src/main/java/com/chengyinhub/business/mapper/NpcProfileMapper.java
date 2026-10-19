package com.chengyinhub.business.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import com.chengyinhub.business.domain.NpcProfile;

/**
 * NPC 人设 Mapper。
 *
 * @author chengyinhub
 */
public interface NpcProfileMapper
{
    /**
     * 按作用域查已启用的 NPC(局内:活动角色+门店分身)。
     */
    List<NpcProfile> selectEnabledByScope(int scopeType, Long scopeId);

    /**
     * 取全局 NPC(scope_type=3, 业务保证只有一条 enabled)。
     */
    NpcProfile selectGlobalNpc();

    // ===== 门店分身(scope_type=2)后台维护。四条语句都写死 scope_type = 2,
    //       后台这条通道永远碰不到剧本角色(1)与全局 NPC(3)。 =====

    /**
     * 后台列表:含待审/停用,不含已删。scopeId / name 为空即不过滤。
     */
    List<NpcProfile> selectMerchantNpcList(@Param("scopeId") Long scopeId, @Param("name") String name);

    /**
     * 后台详情(scope_type=2 且未删)。
     */
    NpcProfile selectMerchantNpcById(@Param("id") Long id);

    /**
     * 新增门店分身。
     * ⚠️ 不写 active_slot —— migration_npc_governance_v1.sql 的 uk_active_scope(scope_type,scope_id,active_slot)
     *    靠「NULL 不参与唯一」放行同 scope 多条;给 scope 2 打 active_slot=1 会在第二条上撞 1062。
     */
    int insertMerchantNpc(NpcProfile profile);

    /**
     * 编辑门店分身:只改展示字段,scope_type/scope_id 不可迁移。
     */
    int updateMerchantNpc(NpcProfile profile);

    /** 只写声音字段；voiceVersion 必须为读取时版本，成功后数据库递增一次。 */
    int updateMerchantNpcVoice(NpcProfile profile);

    /** 软删除门店分身。 */
    int deleteMerchantNpcById(@Param("id") Long id);

    /** 商家自己编辑；实现必须带 scope_id 归属条件并把内容打回待审。 */
    int updateMerchantSelfNpc(NpcProfile profile);

    /** 后台只审核待审记录。 */
    int auditMerchantNpc(NpcProfile profile);

    /** 仅当门店分身仍使用送检头像时清图并停用，避免迟到回调误伤新头像。 */
    int hideMerchantNpcAvatarIfCurrent(@Param("id") Long id,
                                       @Param("expectedAvatar") String expectedAvatar);

    // ===== 主题节点 NPC(scope_type=4,挂 cms_topic_node.id,一节点一条)=====
    // 五条语句都写死 scope_type = 4,碰不到剧本角色(1)/门店分身(2)/全局(3)。
    // ⚠️ 写入恒带 active_slot = 1,吃 uk_active_scope(scope_type,scope_id,active_slot)
    //    唯一键 —— 「一节点一 NPC」是库侧保证,不是「service 里记得先查一遍」的约定。

    /**
     * 按节点取那条 NPC(含停用/未过审)。
     *
     * <p>⚠️ 与玩家读的 {@link #selectEnabledByScope} 分工不同:这里是**商家回填/回调定位**用的。
     * 违规下架(enabled=0)后如果这里查不到,商家再保存就会插第二条并撞唯一键,表现为
     * 「明明改一句话却报冲突」——查得到才改得回来。
     */
    NpcProfile selectNodeNpcByNodeId(@Param("nodeId") Long nodeId);

    /** 内容安全回调定位目标行(含停用)。 */
    NpcProfile selectNodeNpcById(@Param("id") Long id);

    /**
     * 新增节点 NPC。audit_status/enabled 由服务端定。
     * ⚠️ active_slot = 1 写死在本语句里(不读入参):scope 4 一节点一条,靠唯一键防并发重复插入。
     */
    int insertNodeNpc(NpcProfile profile);

    /**
     * 编辑节点 NPC:只改展示字段,scope 不可迁移。
     * auditStatus 仅在「下架/未过审的行重新保存回到待审」时传 0(N2);在架编辑传 null,<if> 跳过。
     */
    int updateNodeNpc(NpcProfile profile);

    /** 只写节点声音；voiceVersion 必须为读取时版本，成功后数据库递增一次。 */
    int updateNodeNpcVoice(NpcProfile profile);

    /** 内容安全回调判违规:自动下架(清头像 + enabled=0),玩家读立即不可见。 */
    int hideNodeNpcById(@Param("id") Long id);
}
