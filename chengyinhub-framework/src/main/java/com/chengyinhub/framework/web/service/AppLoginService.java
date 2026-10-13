package com.chengyinhub.framework.web.service;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.Constants;
import com.chengyinhub.common.constant.UserConstants;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.SecurityUtils;
import com.chengyinhub.common.exception.user.*;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.MessageUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.common.utils.ip.IpUtils;
import com.chengyinhub.framework.manager.AsyncManager;
import com.chengyinhub.framework.manager.factory.AsyncFactory;
import com.chengyinhub.framework.security.context.AuthenticationContextHolder;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.logging.Logger;

/**
 * 登录校验方法
 *
 * @author ruoyi
 */
@Component
public class AppLoginService {
    private static final String AUTHENTICATION_ERROR = "账号或者密码错误";

    @Resource
    private AppTokenService appTokenService;
    @Resource
    private IUmsMemberService appUserSevice;
    @Resource
    private UmsMemberMapper umsMemberMapper;
    @Resource
    private IAppSessionRegistryService appSessionRegistryService;


    /**
     * 登录验证
     *
     * @param username 用户名
     * @param password 密码
     * @param openid 用户微信openid
     * @return 结果
     */
    public String login(String username, String password,String openid) {
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password) || StringUtils.isEmpty(openid)) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        UmsMember umsMember = new UmsMember();
        umsMember.setPhone(username);
        List<UmsMember> list = appUserSevice.selectUmsMemberList(umsMember);
        if (list.size() != 1) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        Long memberId = list.get(0).getId();
        if (memberId == null) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        long authenticationEpoch = appSessionRegistryService.currentOrCreateEpoch(memberId);
        list = appUserSevice.selectUmsMemberList(umsMember);
        if (list.size() != 1 || !memberId.equals(list.get(0).getId()) || isInactive(list.get(0))) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        UmsMember member = list.get(0);

        // [P0-安全] 密码校验改 BCrypt;兼容旧明文口令并在本次登录平滑升级为哈希,避免锁死存量用户。
        String stored = member.getPassword();
        boolean matched;
        boolean needUpgrade = false;
        if (stored != null && stored.startsWith("$2")) {
            matched = SecurityUtils.matchesPassword(password, stored); // 已是 BCrypt 哈希
        } else {
            matched = stored != null && stored.equals(password);       // 旧明文口令:兼容比对
            needUpgrade = matched;
        }
        if (!matched) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        String existingOpenId = member.getWxOpenId();
        if (StringUtils.isNotEmpty(existingOpenId) && !existingOpenId.equals(openid)) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        if (needUpgrade) {
            String upgradedPassword = SecurityUtils.encryptPassword(password);
            String mutationId = appSessionRegistryService.beginMemberMutation(memberId);
            try {
                if (umsMemberMapper.upgradePasswordIfMatches(
                        memberId, stored, upgradedPassword) != 1) {
                    throw new ServiceException(AUTHENTICATION_ERROR);
                }
                appSessionRegistryService.revokeAll(memberId);
                appSessionRegistryService.finishMemberMutationAfterTransaction(memberId, mutationId);
                mutationId = null;
                authenticationEpoch = appSessionRegistryService.currentOrCreateEpoch(memberId);
                member.setPassword(upgradedPassword);
            } catch (RuntimeException e) {
                if (mutationId != null) {
                    try {
                        appSessionRegistryService.finishMemberMutation(memberId, mutationId);
                    } catch (RuntimeException cleanupFailure) {
                        e.addSuppressed(cleanupFailure);
                    }
                }
                throw e;
            }
        }

        if (StringUtils.isEmpty(member.getWxOpenId())) {
            try {
                if (umsMemberMapper.bindWxOpenIdIfBlank(memberId, openid) != 1) {
                    throw new ServiceException(AUTHENTICATION_ERROR);
                }
            } catch (org.springframework.dao.DuplicateKeyException collision) {
                throw new ServiceException(AUTHENTICATION_ERROR);
            }
            member.setWxOpenId(openid);
        }
        AppUser appUser = new AppUser();
        appUser.setUserId(memberId);
        appUser.setPassword(member.getPassword());
        appUser.setUserName(member.getPhone());

        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(memberId);
        loginAppUser.setSessionEpoch(authenticationEpoch);
        loginAppUser.setAppUser(appUser);

        recordLoginInfo(loginAppUser.getUserId());
        // 生成token
        return appTokenService.createAppToken(loginAppUser);
    }

    /**
     * 登录验证
     *
     * @param openid 用户微信openid
     * @return 结果
     */
    public String login(String openid) {
        if (StringUtils.isEmpty(openid)) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        UmsMember umsMember = new UmsMember();
        umsMember.setWxOpenId(openid);
        List<UmsMember> list = appUserSevice.selectUmsMemberList(umsMember);
        if (list.size() != 1) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        Long memberId = list.get(0).getId();
        if (memberId == null) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        long authenticationEpoch = appSessionRegistryService.currentOrCreateEpoch(memberId);
        list = appUserSevice.selectUmsMemberList(umsMember);
        if (list.size() != 1 || !memberId.equals(list.get(0).getId())
                || !openid.equals(list.get(0).getWxOpenId()) || isInactive(list.get(0))) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        UmsMember member = list.get(0);

        AppUser appUser = new AppUser();
        appUser.setUserId(memberId);
        appUser.setPassword(member.getPassword());
        appUser.setUserName(member.getPhone());

        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(memberId);
        loginAppUser.setSessionEpoch(authenticationEpoch);
        loginAppUser.setAppUser(appUser);

        recordLoginInfo(loginAppUser.getUserId());
        // 生成token
        return appTokenService.createAppToken(loginAppUser);
    }

    /**
     * 登录验证(手机号登录):直接基于已确认的用户对象签发 token。
     * 用于纯手机号用户(wxOpenId 可能为空,不能走 login(openid) 按 openid 反查,
     * 否则空 openid 会跳过过滤命中全表)。
     *
     * @param member 已经过身份校验的用户
     * @return 结果
     */
    public String loginByMember(UmsMember member, String identityType, String identityValue, String secondaryValue) {
        if (member == null || member.getId() == null
                || !matchesIdentity(member, identityType, identityValue, secondaryValue)) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        long authenticationEpoch = appSessionRegistryService.currentOrCreateEpoch(member.getId());
        member = appUserSevice.selectUmsMemberById(member.getId());
        if (member == null || isInactive(member)
                || !matchesIdentity(member, identityType, identityValue, secondaryValue)) {
            throw new ServiceException(AUTHENTICATION_ERROR);
        }
        AppUser appUser = new AppUser();
        appUser.setUserId(member.getId());
        appUser.setPassword(member.getPassword());
        appUser.setUserName(member.getPhone());

        LoginAppUser loginAppUser = new LoginAppUser();
        loginAppUser.setUserId(member.getId());
        loginAppUser.setSessionEpoch(authenticationEpoch);
        loginAppUser.setAppUser(appUser);

        recordLoginInfo(loginAppUser.getUserId());
        // 生成token
        return appTokenService.createAppToken(loginAppUser);
    }

    /**
     * 记录登录信息
     *
     * @param userId 用户ID
     */
    private void recordLoginInfo(Long userId) {
        UmsMember umsMember = new UmsMember();
        umsMember.setId(userId);
        umsMember.setLoginTime(DateUtils.getNowDate());
        appUserSevice.updateUmsMember(umsMember);
    }

    private boolean isInactive(UmsMember member) {
        return Long.valueOf(2L).equals(member.getStatus()) || Integer.valueOf(2).equals(member.getDelFlag());
    }

    private boolean matchesIdentity(UmsMember member, String identityType,
            String identityValue, String secondaryValue) {
        if ("phone".equals(identityType)) {
            return StringUtils.isNotEmpty(identityValue) && identityValue.equals(member.getPhone());
        }
        if ("wechat".equals(identityType)) {
            return (StringUtils.isNotEmpty(identityValue) && identityValue.equals(member.getWxOpenId()))
                    || (StringUtils.isNotEmpty(secondaryValue) && secondaryValue.equals(member.getUnionId()));
        }
        if ("apple".equals(identityType)) {
            return StringUtils.isNotEmpty(identityValue) && identityValue.equals(member.getAppleId());
        }
        return false;
    }
}
