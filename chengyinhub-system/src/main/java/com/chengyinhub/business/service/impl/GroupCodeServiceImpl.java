package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.mapper.CoopGroupVerifyMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IGroupCodeService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** 将团码签发和接待台账收口在场次维度，避免主题级团码串场。 */
@Service
public class GroupCodeServiceImpl implements IGroupCodeService {

    private static final Logger logger = LoggerFactory.getLogger(GroupCodeServiceImpl.class);
    private static final long GROUP_CODE_TTL_MS = 300_000L;

    private final ICmsActivityService cmsActivityService;
    private final IClubAccessService clubAccessService;
    private final CoopOrderMapper coopOrderMapper;
    private final CoopGroupVerifyMapper coopGroupVerifyMapper;
    private final IQrcodeService qrcodeService;
    private final String dynSecret;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Autowired
    private com.chengyinhub.business.mapper.CmsTopicMapper cmsTopicMapper;

    public GroupCodeServiceImpl(ICmsActivityService cmsActivityService, IClubAccessService clubAccessService,
                                CoopOrderMapper coopOrderMapper, CoopGroupVerifyMapper coopGroupVerifyMapper,
                                IQrcodeService qrcodeService,
                                @Value("${verify.dyncode-secret:${token.secret}}") String dynSecret) {
        this.cmsActivityService = cmsActivityService;
        this.clubAccessService = clubAccessService;
        this.coopOrderMapper = coopOrderMapper;
        this.coopGroupVerifyMapper = coopGroupVerifyMapper;
        this.qrcodeService = qrcodeService;
        this.dynSecret = dynSecret;
    }

    @Override
    public AjaxResult issue(Long activityId, long issuerMemberId) {
        if (issuerMemberId <= 0) return AjaxResult.error("请先登录");
        if (activityId == null || activityId <= 0) return AjaxResult.error("参数缺失");
        CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
        if (!isActiveGroupActivity(activity)) return AjaxResult.error("该场次不可带队核销");
        Long topicId = activity.getTopicId();
        if (!canCheckIn(activity, issuerMemberId)) {
            return AjaxResult.error("您没有该场次的团码核销权限");
        }

        long now = System.currentTimeMillis();
        String code = VerifyDynCodeUtil.issue(topicId, "group_v2_" + activityId + "_" + issuerMemberId,
                now, GROUP_CODE_TTL_MS, ThreadLocalRandom.current().nextLong(), dynSecret);
        // CU-C-78:二维码是团码唯一的出示形态(码本身是一长串签名令牌,现场只能扫图),
        // 出图失败时不能降级成「业务成功 + qrcodeUrl 为空」—— 那会让页面以为已出码,
        // 而商家那边无图可扫。code 本身是纯签名、不落库(见 VerifyDynCodeUtil.issue),
        // 失败不产生半成品,重试重新签一张即可。
        String qrcodeUrl;
        try {
            qrcodeUrl = qrcodeService.generateInlineQrcode(code, 300, 300);
        } catch (Exception e) {
            logger.error("签发团码二维码生成失败", e);
            return AjaxResult.error("团码二维码生成失败，请稍后重试");
        }
        if (qrcodeUrl == null || qrcodeUrl.trim().isEmpty()) {
            logger.error("签发团码二维码生成失败:出码服务未返回可用地址");
            return AjaxResult.error("团码二维码生成失败，请稍后重试");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("qrcodeUrl", qrcodeUrl);
        data.put("ttlMs", GROUP_CODE_TTL_MS);
        data.put("expiresAt", now + GROUP_CODE_TTL_MS);
        data.put("topicId", topicId);
        data.put("activityId", activityId);
        data.put("clubId", activity.getClubId());
        return AjaxResult.success(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult redeem(String code, long merchantMemberId) {
        return redeem(code, merchantMemberId, merchantMemberId, "MERCHANT_OWNER");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AjaxResult redeem(String code, long merchantMemberId, long actorMemberId,
                             String actorRoleCode) {
        if (merchantMemberId <= 0) return AjaxResult.error("请先登录");
        if (actorMemberId <= 0 || actorRoleCode == null || actorRoleCode.trim().isEmpty()) {
            return AjaxResult.error("核销操作人无效");
        }
        VerifyDynCodeUtil.Result result = VerifyDynCodeUtil.verify(code, System.currentTimeMillis(), dynSecret);
        if (!result.valid) return AjaxResult.error(result.reason == null ? "核销码无效" : result.reason);
        if (result.type == null || !result.type.startsWith("group")) return AjaxResult.error("非团核销码");
        if (!result.type.startsWith("group_v2_")) return AjaxResult.error("团码版本已过期,请领队重新出码");
        Long topicId = result.regId;
        if (topicId == null || topicId <= 0) return AjaxResult.error("核销码格式错误");

        Long activityId;
        long leaderId;
        try {
            String[] parts = result.type.split("_", -1);
            if (parts.length != 4 || !"group".equals(parts[0]) || !"v2".equals(parts[1])) {
                return AjaxResult.error("核销码格式错误");
            }
            activityId = Long.parseLong(parts[2]);
            leaderId = Long.parseLong(parts[3]);
        } catch (Exception e) {
            return AjaxResult.error("核销码格式错误");
        }
        CmsActivity activity = cmsActivityService.selectCmsActivityById(activityId);
        if (!isActiveGroupActivity(activity) || !topicId.equals(activity.getTopicId())) {
            return AjaxResult.error("团码场次无效");
        }

        CoopOrder order = null;
        for (CoopOrder candidate : coopOrderMapper.selectEffectiveOrdersByTopicId(topicId)) {
            if ("merchant".equals(candidate.getToType()) && Long.valueOf(merchantMemberId).equals(candidate.getToId())) {
                order = candidate;
                break;
            }
        }
        if (order == null) return AjaxResult.error("您在本主题没有生效的合作单,无法核销");
        if (!canCheckIn(activity, leaderId)) {
            return AjaxResult.error("该团码的出示人已不再具备场次团码权限");
        }

        CoopGroupVerify verification = new CoopGroupVerify();
        verification.setTopicId(topicId);
        verification.setActivityId(activityId);
        verification.setCoopOrderId(order.getId());
        verification.setMerchantMemberId(merchantMemberId);
        verification.setLeaderMemberId(leaderId);
        verification.setVerifyTime(new Date());
        int inserted = coopGroupVerifyMapper.insertIgnoreCoopGroupVerify(verification);

        if (inserted == 1) {
            BusinessAuditEvent audit = new BusinessAuditEvent();
            audit.setDomainType("GROUP_ACTIVITY");
            audit.setDomainId(activityId);
            audit.setOwnerMemberId(merchantMemberId);
            audit.setActorMemberId(actorMemberId);
            audit.setActorRoleCode(actorRoleCode);
            audit.setAction("GROUP_CODE_REDEEM");
            audit.setTargetType("COOP_GROUP_VERIFY");
            audit.setTargetId(activityId);
            audit.setRelationId(order.getId());
            audit.setBeforeJson("{\"received\":false}");
            audit.setAfterJson("{\"received\":true}");
            audit.setRequestId("group-redeem-" + activityId + "-" + merchantMemberId);
            audit.setCreateTime(new Date());
            if (businessAuditEventMapper.insertBusinessAuditEvent(audit) != 1) {
                throw new IllegalStateException("团码核销审计写入失败");
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("topicId", topicId);
        data.put("activityId", activityId);
        data.put("leaderMemberId", leaderId);
        data.put("coopOrderId", order.getId());
        data.put("alreadyVerified", inserted == 0);
        return AjaxResult.success(inserted == 0 ? "已记录过" : "已记录接待该团", data);
    }

    private boolean isActiveGroupActivity(CmsActivity activity) {
        return activity != null && activity.getTopicId() != null && activity.getClubId() != null
                && activity.getClubId() > 0 && Integer.valueOf(0).equals(activity.getDelFlag())
                && Long.valueOf(1L).equals(activity.getStatus())
                && Long.valueOf(1L).equals(activity.getPublishStatus())
                && activity.getCancelTime() == null
                && (activity.getEndDate() == null || !new Date().after(activity.getEndDate()));
    }

    /**
     * 这个俱乐部有没有资格带这一场。两条路,任一成立即可:
     *
     * <p>① <b>承接</b>:主题是别人的,俱乐部靠一张生效的 {@code to_type='club'} 合作单拿到带团权;
     * <p>② <b>自办</b>:主题本来就挂在这个俱乐部名下(cms_topic.club_id)。
     *
     * <p>★2026-09-11 补上第二条。原来只认①,而 {@code ClubSessionServiceImpl} 明确允许
     * 主理人拿自有主题开场 —— 自己的主题不需要也不会有「承接自己」的合作单,
     * 于是自办场次一律出不了团码,错误文案还是「您没有该场次的团码核销权限」,
     * 看上去像权限配错,实际是判据缺了一半,查半天查不到真因。
     *
     * <p>注意这不会放松商家侧:{@code redeem} 仍然要求扫码的商家在本主题有生效合作单,
     * 没有商家合作的自办场次,码签得出来也没人能核 —— 那是另一回事,不该由签发这一步来拦。
     */
    private boolean clubRunsThisSession(CmsActivity activity) {
        for (CoopOrder order : coopOrderMapper.selectEffectiveOrdersByTopicId(activity.getTopicId())) {
            if ("club".equals(order.getToType()) && activity.getClubId().equals(order.getToId())) {
                return true;
            }
        }
        com.chengyinhub.business.domain.CmsTopic topic = cmsTopicMapper.selectCmsTopicById(activity.getTopicId());
        return topic != null && activity.getClubId().equals(topic.getClubId());
    }

    private boolean canCheckIn(CmsActivity activity, long memberId) {
        if (!clubRunsThisSession(activity)) return false;
        try {
            clubAccessService.require(memberId, activity.getClubId(), activity.getId(),
                    ClubPermission.EVENT_CHECKIN);
            return true;
        } catch (ServiceException ignored) {
            return false;
        }
    }
}
