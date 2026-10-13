package com.chengyinhub.business.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 身份能力位的服务端强制点（X03）。写法与 {@link QuotaServiceImpl} 同构：
 * 取 member → 取 permission → 判位 → 放行返回 null。
 *
 * @author chengyinhub
 */
@Service
public class CapabilityGuardImpl implements ICapabilityGuard
{
    private static final Logger log = LoggerFactory.getLogger(CapabilityGuardImpl.class);

    /** 权益清单「博弈机制（竞速/对抗/协作）」对应的三个机制键。清单没提的一律不拦。 */
    private static final String[] GAME_MECHANIC_KEYS = { "timer", "leaderboard", "multiplayer" };

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    /** 与 TemplatePublishServiceImpl / AdvancedGameRuntimeServiceImpl 一致:它不是 bean,直接 new。 */
    private final AdvancedGameConfigValidator configValidator = new AdvancedGameConfigValidator();

    @Override
    public String checkAdvancedMechanics(Long memberId, String advancedConfigJson)
    {
        if (StringUtils.isEmpty(advancedConfigJson))
        {
            return null;
        }
        // ⚠️ 这里必须 fail-closed。
        // 早先写成「解析失败就 return null(放行),交回上游判」—— 那是个 fail-open:
        // 它把「我看不懂这份配置」当成了「这份配置没有启用任何受管机制」。
        // 一道读不懂输入就放行的闸，等于没有这道闸。
        // 现实里上游 normalizeAdvancedConfig 会先跑同一个校验器、先抛错，
        // 所以非法配置根本到不了这儿；但一道闸的安全性不该建立在调用顺序上。
        String normalized;
        try
        {
            normalized = configValidator.validateAndNormalize(advancedConfigJson);
        }
        catch (IllegalArgumentException e)
        {
            log.warn("[CapabilityGuard] 高级玩法配置无法校验,按未解锁处理 memberId={}", memberId, e);
            return "高级玩法配置无法解析，请检查后重试";
        }
        if (normalized == null)
        {
            return null;   // 校验器明确判定「没有配置」,与空串同义
        }
        JsonNode root;
        try
        {
            root = objectMapper.readTree(normalized);
        }
        catch (Exception e)
        {
            log.warn("[CapabilityGuard] 已归一化的配置仍读不出 JSON,按未解锁处理 memberId={}", memberId, e);
            return "高级玩法配置无法解析，请检查后重试";
        }
        boolean wantsBranch = mechanicEnabled(root, "branch");
        boolean wantsGame = false;
        for (String key : GAME_MECHANIC_KEYS)
        {
            wantsGame = wantsGame || mechanicEnabled(root, key);
        }
        if (!wantsBranch && !wantsGame)
        {
            return null;
        }
        UserPermission perm = permissionOf(memberId);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }
        if (wantsBranch && !Boolean.TRUE.equals(perm.getCanBranch()))
        {
            return "当前身份未解锁「分支 / 条件玩法」，请移除分支配置后再保存";
        }
        if (wantsGame && !Boolean.TRUE.equals(perm.getCanGameMechanic()))
        {
            return "当前身份未解锁「博弈机制（竞速/对抗/协作）」，请移除计时 / 排行 / 多人配置后再保存";
        }
        return null;
    }

    @Override
    public String checkDesignMedal(Long memberId)
    {
        UserPermission perm = permissionOf(memberId);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }
        return Boolean.TRUE.equals(perm.getCanDesignMedal())
                ? null : "当前身份未解锁「勋章设计」";
    }

    @Override
    public String checkCreateGroup(Long memberId)
    {
        UserPermission perm = permissionOf(memberId);
        if (perm == null)
        {
            return "当前身份权限配置缺失，请联系管理员";
        }
        return Boolean.TRUE.equals(perm.getCanCreateGroup())
                ? null : "当前身份未解锁「建群」";
    }

    /** 机制「启用」的口径与 AdvancedGameConfigValidator.enabled 一致：对象存在且 enabled=true。 */
    private boolean mechanicEnabled(JsonNode root, String key)
    {
        JsonNode node = root == null ? null : root.get(key);
        return node != null && node.isObject() && node.path("enabled").asBoolean(false);
    }

    private UserPermission permissionOf(Long memberId)
    {
        if (memberId == null || memberId <= 0)
        {
            return null;
        }
        try
        {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            if (member == null)
            {
                return null;
            }
            return roleService.getPermission(member);
        }
        catch (Exception e)
        {
            log.warn("[CapabilityGuard] 取权限失败 memberId={}", memberId, e);
            return null;
        }
    }
}
