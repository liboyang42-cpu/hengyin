package com.chengyinhub.business.service.impl;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.PlayerActivityRole;
import com.chengyinhub.business.mapper.PlayerActivityRoleMapper;
import com.chengyinhub.business.service.IPlayerRoleService;

/**
 * 俱乐部活动角色分配(升级2)。
 * 五个固定角色轮转:已分配人数 % 5 → 下一个;并发双请求由唯一键 (activity_id, member_id)
 * 收敛,输家捕获 DuplicateKey 后回读赢家写入的角色 —— 一人恒一角色。
 */
@Service
public class PlayerRoleServiceImpl implements IPlayerRoleService {

    /** C06 口径:导航员看路,观察员找线索,记录员留证据,交涉员打开门,解谜员拆题 */
    private static final String[] ROLE_CODES = {"NAVIGATOR", "OBSERVER", "RECORDER", "NEGOTIATOR", "DECODER"};
    private static final String[] ROLE_NAMES = {"导航员", "观察员", "记录员", "交涉员", "解谜员"};

    @Autowired
    private PlayerActivityRoleMapper playerActivityRoleMapper;

    @Override
    public PlayerActivityRole assignOrGet(Long memberId, Long activityId) {
        if (memberId == null || memberId <= 0 || activityId == null || activityId <= 0) {
            return null;
        }
        PlayerActivityRole existing = playerActivityRoleMapper.selectByActivityAndMember(activityId, memberId);
        if (existing != null) {
            return existing;
        }
        int assigned = playerActivityRoleMapper.countByActivity(activityId);
        PlayerActivityRole role = new PlayerActivityRole();
        role.setActivityId(activityId);
        role.setMemberId(memberId);
        role.setRoleCode(ROLE_CODES[assigned % ROLE_CODES.length]);
        role.setCreateTime(new Date());
        try {
            playerActivityRoleMapper.insertPlayerActivityRole(role);
            return role;
        } catch (DuplicateKeyException e) {
            // 并发输家:回读赢家写的角色,保证一人恒一角色
            return playerActivityRoleMapper.selectByActivityAndMember(activityId, memberId);
        }
    }

    @Override
    public PlayerActivityRole readAssigned(Long memberId, Long activityId) {
        if (memberId == null || memberId <= 0 || activityId == null || activityId <= 0) {
            return null;
        }
        return playerActivityRoleMapper.selectByActivityAndMember(activityId, memberId);
    }

    @Override
    public String roleName(String roleCode) {
        for (int i = 0; i < ROLE_CODES.length; i++) {
            if (ROLE_CODES[i].equals(roleCode)) return ROLE_NAMES[i];
        }
        return "";
    }
}
