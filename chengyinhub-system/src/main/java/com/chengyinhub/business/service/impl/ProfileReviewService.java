package com.chengyinhub.business.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberProfileVersion;
import com.chengyinhub.business.domain.WxContentCallbackInbox;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberProfileVersionMapper;
import com.chengyinhub.business.mapper.WxContentCallbackInboxMapper;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.support.NpcAvatar;

/**
 * 资料版本化提交与审核回执(#25)。
 *
 * <p>版本:每次 /api/user/update 成功记一行,版本号按会员递增。审核状态<b>不</b>另存一份:
 * 头像的图片安全复核结论只有内容安全回调 inbox 是权威,这里按送检时的媒体哈希实时读出来。
 *
 * <p>回执状态(只描述能证明的事):
 * PENDING 已送检,微信还没给结论(含人工复核中);APPROVED 该图复核通过;REJECTED 该图复核判违规;
 * NOT_SUBMITTED 没有送检记录(内容安全未开启或送检失败)—— 不是「通过」;NOT_REQUIRED 内置预设形象,不送检。
 * live 表示这张图此刻是否就是对外展示的那张,与复核结论分开给,不互相推断。
 */
@Service
public class ProfileReviewService
{
    private static final Logger log = LoggerFactory.getLogger(ProfileReviewService.class);

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String NOT_SUBMITTED = "NOT_SUBMITTED";
    public static final String NOT_REQUIRED = "NOT_REQUIRED";

    @Autowired
    private UmsMemberProfileVersionMapper versionMapper;

    @Autowired
    private WxContentCallbackInboxMapper inboxMapper;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    /**
     * 记一次成功的资料提交。记录失败(如迁移未落库)不影响已经成功的资料保存,返回 null。
     *
     * @param avatarRef       本次写进 ums_member 的头像引用;未改头像传 null
     * @param submittedAvatar 本次送检用的原始头像串(与 submitMediaCheck 收到的一致);未改传 null
     */
    public Integer recordMemberSubmission(Long memberId, List<String> changedFields, String avatarRef, String submittedAvatar)
    {
        if (memberId == null || memberId <= 0) return null;
        UmsMemberProfileVersion row = new UmsMemberProfileVersion();
        row.setMemberId(memberId);
        row.setChangedFields(String.join(",", changedFields));
        boolean avatarChanged = avatarRef != null && !avatarRef.trim().isEmpty();
        row.setAvatarRef(avatarChanged ? avatarRef : null);
        row.setAvatarMediaHash(avatarChanged && submittedAvatar != null && !submittedAvatar.trim().isEmpty()
                ? WxContentCallbackInboxServiceImpl.sha256Hex(submittedAvatar.trim()) : null);
        for (int attempt = 0; attempt < 2; attempt++)
        {
            try
            {
                versionMapper.insertNextVersion(row);
                UmsMemberProfileVersion latest = versionMapper.selectLatest(memberId);
                return latest == null ? null : latest.getVersionNo();
            }
            catch (DuplicateKeyException concurrent)
            {
                // 同一会员两次保存并发撞了同一个版本号:再取一次 max+1
            }
            catch (RuntimeException e)
            {
                log.warn("[ProfileReview] version record unavailable memberId={} errorType={}", memberId,
                        e.getClass().getSimpleName());
                return null;
            }
        }
        log.warn("[ProfileReview] version record contended memberId={}", memberId);
        return null;
    }

    /** 本人资料审核回执。表缺失或读失败时 available=false,不编造状态。 */
    public Map<String, Object> memberReceipt(Long memberId)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try
        {
            UmsMemberProfileVersion latest = versionMapper.selectLatest(memberId);
            UmsMemberProfileVersion avatarVersion = versionMapper.selectLatestAvatarVersion(memberId);
            UmsMember member = umsMemberMapper.selectUmsMemberById(memberId);
            String currentAvatar = member == null ? null : member.getAvatar();
            out.put("available", true);
            out.put("currentVersion", latest == null ? null : latest.getVersionNo());
            out.put("submittedAt", latest == null ? null : latest.getCreateTime());
            out.put("changedFields", latest == null ? null : latest.getChangedFields());
            if (avatarVersion == null)
            {
                out.put("avatar", null);
                return out;
            }
            Map<String, Object> avatar = new LinkedHashMap<String, Object>();
            avatar.put("versionNo", avatarVersion.getVersionNo());
            avatar.put("state", mediaState(IWxContentSecurityService.BIZ_TYPE_MEMBER_PROFILE, memberId,
                    avatarVersion.getAvatarMediaHash()));
            avatar.put("live", currentAvatar != null && currentAvatar.equals(avatarVersion.getAvatarRef()));
            out.put("avatar", avatar);
            return out;
        }
        catch (RuntimeException e)
        {
            log.warn("[ProfileReview] member receipt unavailable memberId={} errorType={}", memberId,
                    e.getClass().getSimpleName());
            out.clear();
            out.put("available", false);
            return out;
        }
    }

    /** 门店 AI 形象(npc_profile scope_type=2)的审核回执:文字人设审核 + 形象图复核 + 玩家此刻能否看到。 */
    public Map<String, Object> merchantNpcReceipt(Long merchantId, NpcProfile npc)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (npc == null)
        {
            out.put("hasProfile", false);
            return out;
        }
        Integer audit = npc.getAuditStatus();
        out.put("hasProfile", true);
        out.put("review", Integer.valueOf(1).equals(audit) ? APPROVED : Integer.valueOf(2).equals(audit) ? REJECTED : PENDING);
        out.put("reviewReason", Integer.valueOf(2).equals(audit) ? npc.getAuditReason() : null);
        // 商家改形象会把 audit_status 置回待审,玩家读闸只放已过审 + 启用:待审期间玩家看不到任何版本。
        out.put("playerVisible", Integer.valueOf(1).equals(audit) && Integer.valueOf(1).equals(npc.getEnabled()));
        String avatar = npc.getAvatar();
        String avatarState;
        if (avatar == null || avatar.trim().isEmpty()) avatarState = null;
        else if (NpcAvatar.isPreset(avatar)) avatarState = NOT_REQUIRED;
        else
        {
            try
            {
                // 门店分身头像按 (BIZ_TYPE_MERCHANT_NPC, npc_profile.id) 送检(#473);商家 logo/封面/相册才是
                // (MERCHANT_PROFILE, merchantId)。按后者查,回执永远 NOT_SUBMITTED,同图时还会读到 logo 的结论。
                avatarState = mediaState(IWxContentSecurityService.BIZ_TYPE_MERCHANT_NPC, npc.getId(),
                        WxContentCallbackInboxServiceImpl.sha256Hex(avatar.trim()));
            }
            catch (RuntimeException e)
            {
                log.warn("[ProfileReview] merchant avatar receipt unavailable merchantId={} npcId={} errorType={}", merchantId,
                        npc.getId(), e.getClass().getSimpleName());
                avatarState = null;
            }
        }
        out.put("avatarReview", avatarState);
        return out;
    }

    private String mediaState(int bizType, Long bizId, String mediaHash)
    {
        if (mediaHash == null || mediaHash.isEmpty()) return NOT_SUBMITTED;
        WxContentCallbackInbox row = inboxMapper.selectLatestByTargetMedia(bizType, bizId, mediaHash);
        if (row == null) return NOT_SUBMITTED;
        if (Integer.valueOf(1).equals(row.getVerdict())) return REJECTED;
        Integer state = row.getState();
        if (Integer.valueOf(0).equals(row.getVerdict())
                && (Integer.valueOf(WxContentCallbackInbox.STATE_DONE).equals(state)
                    || Integer.valueOf(WxContentCallbackInbox.STATE_IGNORED_STALE).equals(state)))
        {
            return APPROVED;
        }
        return PENDING;
    }
}
