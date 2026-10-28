package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.utils.GeoDistanceUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.OfficialBroadcast;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventArrivalVerification;
import com.chengyinhub.business.domain.OfficialEventBinding;
import com.chengyinhub.business.domain.OfficialEventFulfillmentRequirement;
import com.chengyinhub.business.domain.OfficialEventMission;
import com.chengyinhub.business.domain.OfficialEventMissionEvidence;
import com.chengyinhub.business.domain.OfficialEventOperatorAuthorization;
import com.chengyinhub.business.domain.OfficialEventParticipation;
import com.chengyinhub.business.domain.OfficialEventParty;
import com.chengyinhub.business.domain.OfficialEventRewardIssuance;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.dto.OfficialEventV2ArrivalRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2DraftRequest;
import com.chengyinhub.business.domain.dto.OfficialEventV2PublishCheck;
import com.chengyinhub.business.domain.dto.PublishSafetyRequest;
import com.chengyinhub.business.domain.dto.SafetyDecision;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import com.chengyinhub.business.mapper.OfficialBroadcastMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.IContentSafetyGate;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.IOfficialBroadcastService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.service.ISysConfigService;
import com.chengyinhub.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 官方活动 V2 的唯一业务写入口。将绑定、任务、承接、证据和资格收进一个深模块，
 * 调用方不能再用 event_signup.progress 或页面回调伪造完成。
 */
@Service
public class OfficialEventV2ServiceImpl implements IOfficialEventV2Service {

    private static final String POLICY_SELF_RUN = "SELF_RUN";
    private static final String POLICY_OPTIONAL_PARTNERS = "OPTIONAL_PARTNERS";
    private static final String POLICY_REQUIRED_FULFILLMENT = "REQUIRED_FULFILLMENT";
    private static final String CONTROL_NORMAL = "NORMAL";
    private static final String CONTROL_PAUSED = "PAUSED";
    private static final String PAUSE_NOTIFICATION_PENDING = "PENDING";
    private static final double MAX_MOVE_SPEED_MPS = 35D;
    private static final int MIN_SPEED_DISTANCE_M = 300;
    private static final int GPS_TOLERANCE_M = 30;
    private static final int DEFAULT_MAX_ACCURACY_M = 80;
    private static final long ARRIVAL_MIN_INTERVAL_MILLIS = 30_000L;
    /** 发布检查 WARN：报名期/距开场不足该时长时提示(不阻断)。 */
    private static final long PUBLISH_WARN_LEAD_MILLIS = 24L * 3600_000L;

    @Autowired
    private OfficialEventMapper eventMapper;
    @Autowired
    private EventSignupMapper signupMapper;
    @Autowired
    private OfficialEventV2Mapper v2Mapper;
    @Autowired
    private RoamMapper roamMapper;
    @Autowired
    private MmsMerchantMapper merchantMapper;
    @Autowired
    private ClubMapper clubMapper;
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private ISysConfigService configService;
    @Autowired
    private IUmsMemberService umsMemberService;
    @Autowired
    private IContentSafetyGate contentSafetyGate;
    @Autowired
    private IAuditTaskService auditTaskService;
    @Autowired
    private ISysUserService sysUserService;
    @Autowired
    private OfficialEventV2SettlementHelper settlementHelper;
    @Autowired
    private IOfficialBroadcastService officialBroadcastService;
    @Autowired
    private OfficialBroadcastMapper officialBroadcastMapper;

    @Override
    public Long resolveAuthorizedOfficialMember(Long sysUserId) {
        if (!isActiveBackendUser(sysUserId) || v2Mapper == null) return null;
        OfficialEventOperatorAuthorization authorization = v2Mapper.selectActiveOperatorAuthorization(sysUserId);
        if (authorization == null || !OfficialEventOperatorAuthorization.STATUS_ACTIVE.equals(authorization.getStatus())) return null;
        Long memberId = authorization.getMemberId();
        return isEffectiveOfficialMember(memberId) ? memberId : null;
    }

    @Override
    public Map<String, Object> operatorIdentity(Long sysUserId) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sysUserId", sysUserId);
        OfficialEventOperatorAuthorization authorization = sysUserId == null || v2Mapper == null
                ? null : v2Mapper.selectActiveOperatorAuthorization(sysUserId);
        Long memberId = resolveAuthorizedOfficialMember(sysUserId);
        data.put("memberId", memberId);
        data.put("authorized", memberId != null);
        data.put("authorizationStatus", authorization == null ? "UNBOUND" : authorization.getStatus());
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String authorizeOperator(Long sysUserId, Long memberId, Long grantedBy, String reason) {
        if (sysUserId == null || sysUserId <= 0 || memberId == null || memberId <= 0) {
            return "后台账号和官方会员不能为空";
        }
        if (StringUtils.isEmpty(reason)) return "授权必须填写原因";
        if (!isActiveBackendUser(grantedBy)) return "当前授权账号不可用";
        if (!isActiveBackendUser(sysUserId)) return "待授权后台账号不存在或已停用";
        if (!isEffectiveOfficialMember(memberId)) return "目标会员不是启用中的白名单官方会员";

        OfficialEventOperatorAuthorization existing = v2Mapper.selectActiveOperatorAuthorizationForUpdate(sysUserId);
        if (existing != null && v2Mapper.revokeOperatorAuthorization(existing.getId(), grantedBy, "授权替换：" + reason) != 1) {
            return "原授权状态已变化，请刷新后重试";
        }
        OfficialEventOperatorAuthorization authorization = new OfficialEventOperatorAuthorization();
        authorization.setSysUserId(sysUserId);
        authorization.setActiveSysUserId(sysUserId);
        authorization.setMemberId(memberId);
        authorization.setStatus(OfficialEventOperatorAuthorization.STATUS_ACTIVE);
        authorization.setGrantedBy(grantedBy);
        authorization.setGrantedAt(new Date());
        authorization.setGrantReason(reason);
        if (v2Mapper.insertOperatorAuthorization(authorization) != 1) return "后台主办授权写入失败";
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String revokeOperatorAuthorization(Long sysUserId, Long revokedBy, String reason) {
        if (sysUserId == null || sysUserId <= 0) return "后台账号不能为空";
        if (StringUtils.isEmpty(reason)) return "撤销必须填写原因";
        if (!isActiveBackendUser(revokedBy)) return "当前授权账号不可用";
        OfficialEventOperatorAuthorization existing = v2Mapper.selectActiveOperatorAuthorizationForUpdate(sysUserId);
        if (existing == null) return "该后台账号没有有效主办授权";
        return v2Mapper.revokeOperatorAuthorization(existing.getId(), revokedBy, reason) == 1 ? null : "授权状态已变化，请刷新后重试";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OfficialEvent saveDraft(OfficialEventV2DraftRequest request, Long operatorId) {
        if (request == null || request.getEvent() == null) {
            throw new ServiceException("活动草稿不能为空");
        }
        if (operatorId == null || operatorId <= 0) {
            throw new ServiceException("未识别草稿创建人");
        }
        OfficialEvent event = request.getEvent();
        if (StringUtils.isEmpty(event.getTitle())) {
            throw new ServiceException("活动标题不能为空");
        }
        if (!isEffectiveOfficialMember(operatorId)) {
            throw new ServiceException("当前账号不是有效官方发布会员");
        }
        boolean existingDraft = event.getId() != null;
        List<OfficialEventParty> parties;
        List<OfficialEventParty> preservedParties = new ArrayList<>();

        if (!existingDraft) {
            parties = normalizeDraftParties(request.getParties(), operatorId);
            event.setContractVersion(2);
            event.setStatus(OfficialEvent.ST_DRAFT);
            event.setControlState(CONTROL_NORMAL);
            event.setSenderAccount(operatorId);
            event.setCreatorIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
            event.setFulfillmentPolicy(defaultPolicy(event.getFulfillmentPolicy()));
            event.setCreateTime(new Date());
            eventMapper.insertOfficialEvent(event);
        } else {
            // 与排期、报名共用活动行锁：校验通过后不能被另一事务换掉草稿合同。
            OfficialEvent stored = requireV2EventForUpdate(event.getId());
            if (!OfficialEventParty.IDENTITY_UMS_MEMBER.equals(stored.getCreatorIdentitySource())) {
                throw new ServiceException("无权修改该活动草稿");
            }
            if (stored.getStatus() == null || stored.getStatus() != OfficialEvent.ST_DRAFT) {
                throw new ServiceException("仅草稿活动可整体修改；运行中请走受控变更命令");
            }
            if (OfficialEvent.CONTROL_PENDING_REVIEW.equals(stored.getControlState())) {
                // 审核中改内容会让审核员看到的快照与实际上架内容不一致
                throw new ServiceException("活动审核中，暂不能修改");
            }
            preservedParties = v2Mapper.selectPartiesByEventForUpdate(stored.getId());
            if (preservedParties == null) preservedParties = new ArrayList<>();
            OfficialEventParty effectiveOrganizer = validateDraftOverwrite(stored, preservedParties, request.getParties(), operatorId);
            if (effectiveOrganizer == null && (stored.getSenderAccount() == null || !stored.getSenderAccount().equals(operatorId))) {
                throw new ServiceException("无权修改该活动草稿");
            }
            parties = normalizeDraftParties(request.getParties(), operatorId);
            // 草稿保存不能借由请求体改变生命周期或归属。
            event.setContractVersion(2);
            event.setStatus(OfficialEvent.ST_DRAFT);
            event.setControlState(CONTROL_NORMAL);
            event.setSenderAccount(stored.getSenderAccount());
            event.setCreatorIdentitySource(stored.getCreatorIdentitySource());
            event.setFulfillmentPolicy(defaultPolicy(event.getFulfillmentPolicy()));
            event.setUpdateTime(new Date());
            eventMapper.updateOfficialEvent(event);
        }

        Long eventId = event.getId();
        // 草稿还未产生 evidence，先删需求再删任务/绑定，避免保存半套关系。
        v2Mapper.deleteRequirementsByEvent(eventId);
        if (existingDraft) v2Mapper.deleteInvitedPartiesByEvent(eventId);
        v2Mapper.deleteMissionsByEvent(eventId);
        v2Mapper.deleteBindingsByEvent(eventId);

        Map<String, Long> bindingIds = new HashMap<>();
        Set<String> bindingCodes = new HashSet<>();
        int bindingSort = 0;
        for (OfficialEventBinding binding : request.getBindings()) {
            validateDraftBinding(binding);
            if (!bindingCodes.add(binding.getBindingCode())) throw new ServiceException("活动绑定编码不能重复：" + binding.getBindingCode());
            binding.setEventId(eventId);
            binding.setStatus(OfficialEventBinding.STATUS_DRAFT);
            binding.setSort(binding.getSort() == null ? bindingSort++ : binding.getSort());
            v2Mapper.insertBinding(binding);
            bindingIds.put(binding.getBindingCode(), binding.getId());
        }

        Map<String, Long> missionIds = new HashMap<>();
        Set<String> missionCodes = new HashSet<>();
        int missionSort = 0;
        for (OfficialEventMission mission : request.getMissions()) {
            Long bindingId = mission.getBindingId();
            if (bindingId == null && StringUtils.isNotEmpty(mission.getBindingCode())) {
                bindingId = bindingIds.get(mission.getBindingCode());
            }
            if (bindingId == null) {
                throw new ServiceException("任务缺少有效绑定");
            }
            mission.setBindingId(bindingId);
            mission.setEventId(eventId);
            mission.setRequiredCount(mission.getRequiredCount() == null ? 1 : mission.getRequiredCount());
            mission.setRuleVersion(StringUtils.isEmpty(mission.getRuleVersion()) ? "1" : mission.getRuleVersion());
            mission.setVisibility(mission.getVisibility() == null ? 1 : mission.getVisibility());
            mission.setStatus(OfficialEventMission.STATUS_DRAFT);
            mission.setSort(mission.getSort() == null ? missionSort++ : mission.getSort());
            validateDraftMission(mission);
            if (!missionCodes.add(mission.getMissionCode())) throw new ServiceException("活动任务编码不能重复：" + mission.getMissionCode());
            v2Mapper.insertMission(mission);
            missionIds.put(mission.getMissionCode(), mission.getId());
        }

        for (OfficialEventFulfillmentRequirement requirement : request.getFulfillmentRequirements()) {
            Long missionId = requirement.getMissionId();
            if (missionId == null && StringUtils.isNotEmpty(requirement.getMissionCode())) {
                missionId = missionIds.get(requirement.getMissionCode());
            }
            requirement.setMissionId(missionId);
            requirement.setEventId(eventId);
            requirement.setRequiredCapacity(requirement.getRequiredCapacity() == null ? 0 : requirement.getRequiredCapacity());
            requirement.setCritical(requirement.getCritical() == null ? 1 : requirement.getCritical());
            requirement.setStatus(StringUtils.isEmpty(requirement.getStatus()) ? "DRAFT" : requirement.getStatus());
            validateDraftRequirement(requirement);
            if (missionId != null && !missionIds.containsValue(missionId)) {
                throw new ServiceException("履约需求引用了当前活动外的任务");
            }
            v2Mapper.insertRequirement(requirement);
        }

        for (OfficialEventParty party : parties) {
            if (isPreservedParty(party, preservedParties)) continue;
            party.setEventId(eventId);
            party.setStatus(defaultPartyStatus(party, operatorId));
            party.setInvitedBy(operatorId);
            party.setInvitedAt(new Date());
            validateDraftParty(party);
            v2Mapper.insertParty(party);
        }
        v2Mapper.insertAuditLog(eventId, "DRAFT_SAVED", operatorId, "保存 V2 活动草稿", null, null);
        return eventMapper.selectOfficialEventById(eventId);
    }

    private OfficialEventV2PublishCheck checkPublishableInternal(Long eventId) {
        OfficialEventV2PublishCheck check = new OfficialEventV2PublishCheck();
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (event == null) {
            check.block("活动不存在");
            return check;
        }
        if (!isV2(event)) {
            check.block("历史 V1 活动不能按 V2 发布");
            return check;
        }
        validateTimeline(event, check);
        List<OfficialEventBinding> bindings = v2Mapper.selectBindingsByEvent(eventId);
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        List<OfficialEventFulfillmentRequirement> requirements = v2Mapper.selectRequirementsByEvent(eventId);
        Map<Long, OfficialEventBinding> bindingById = new HashMap<>();
        for (OfficialEventBinding binding : bindings) bindingById.put(binding.getId(), binding);

        if (missions.isEmpty()) check.block("参与型活动至少需要一项任务");
        validateOrganizer(event, parties, check);
        validateReferenceSources(event, bindings, check);
        for (OfficialEventMission mission : missions) {
            OfficialEventBinding binding = bindingById.get(mission.getBindingId());
            validatePublishMission(event, mission, binding, check);
        }
        validateMissionBindingUniqueness(missions, check);
        validatePartyAvailability(parties, check);
        validateCoverage(event, requirements, parties, check);
        if (publishCheckWarnEnabled()) {
            check.enableTiers();
            collectPublishWarnings(event, check, new Date());
        }
        return check;
    }

    /** 发布前提示(WARN)：只提醒、不阻断；阻断项仍只由上面的 BLOCK 校验决定。 */
    static void collectPublishWarnings(OfficialEvent event, OfficialEventV2PublishCheck check, Date now) {
        if (StringUtils.isBlank(event.getCoverImg())) check.warn("未设置封面图，活动卡片将使用默认图");
        if (StringUtils.isBlank(event.getStory())) check.warn("活动介绍为空，玩家可能不清楚玩法与规则");
        if (event.getSignupStart() != null && event.getActivityStart() != null
                && event.getActivityStart().getTime() - event.getSignupStart().getTime() < PUBLISH_WARN_LEAD_MILLIS) {
            check.warn("报名期不足 24 小时，可能影响报名人数");
        }
        if (event.getActivityStart() != null && now != null
                && event.getActivityStart().getTime() - now.getTime() < PUBLISH_WARN_LEAD_MILLIS) {
            check.warn("距活动开始不足 24 小时，平台审核可能来不及完成");
        }
    }

    @Override
    public OfficialEventV2PublishCheck checkPublishableForOperator(Long eventId, Long memberId) {
        requireControlledEventReader(eventId, memberId);
        return checkPublishableInternal(eventId);
    }

    private Map<String, Object> draftInternal(Long eventId) {
        OfficialEvent event = requireV2Event(eventId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", event);
        data.put("bindings", v2Mapper.selectBindingsByEvent(eventId));
        data.put("missions", v2Mapper.selectMissionsByEvent(eventId));
        data.put("parties", v2Mapper.selectPartiesByEvent(eventId));
        data.put("fulfillmentRequirements", v2Mapper.selectRequirementsByEvent(eventId));
        return data;
    }

    @Override
    public Map<String, Object> draftForOperator(Long eventId, Long memberId) {
        requireControlledEventReader(eventId, memberId);
        return draftInternal(eventId);
    }

    @Override
    public boolean canManage(Long eventId, Long memberId) {
        if (!isEffectiveOfficialMember(memberId)) return false;
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (!isV2(event)) return false;
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        if (parties == null) return false;
        for (OfficialEventParty party : parties) {
            if (isManageParty(party, memberId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canOperateControlledEvent(Long eventId, Long memberId) {
        if (!isEffectiveOfficialMember(memberId)) return false;
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (!isV2(event)) return false;
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        if (parties == null) parties = new ArrayList<OfficialEventParty>();
        for (OfficialEventParty party : parties) {
            if (isManageParty(party, memberId)) return true;
        }
        return event.getStatus() != null && event.getStatus() == OfficialEvent.ST_DRAFT
                && OfficialEventParty.IDENTITY_UMS_MEMBER.equals(event.getCreatorIdentitySource())
                && memberId.equals(event.getSenderAccount())
                && effectiveOrganizer(event, parties) == null;
    }

    @Override
    public boolean canReadControlledEvent(Long eventId, Long memberId) {
        if (canOperateControlledEvent(eventId, memberId)) return true;
        // R9-24：活动结束时主办/协办被归档为 COMPLETED，仅恢复只读；撤权/撤出/暂停/非参与者不在此列
        if (!isEffectiveOfficialMember(memberId) || !isV2(eventMapper.selectOfficialEventById(eventId))) return false;
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        if (parties == null) return false;
        for (OfficialEventParty party : parties) {
            if (usesOfficialMemberIdentity(party) && memberId.equals(party.getPartyId())
                    && (OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                    || OfficialEventParty.ROLE_CO_ORGANIZER.equals(party.getRole()))
                    && OfficialEventParty.STATUS_COMPLETED.equals(party.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private boolean isManageParty(OfficialEventParty party, Long memberId) {
        return usesOfficialMemberIdentity(party) && memberId.equals(party.getPartyId())
                && (OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                || OfficialEventParty.ROLE_CO_ORGANIZER.equals(party.getRole()))
                && (OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus())
                || OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus()));
    }

    private void requireControlledEventOperator(Long eventId, Long memberId) {
        if (!canOperateControlledEvent(eventId, memberId)) {
            throw new ServiceException("无权读取该活动受控数据");
        }
    }

    private void requireControlledEventReader(Long eventId, Long memberId) {
        if (!canReadControlledEvent(eventId, memberId)) {
            throw new ServiceException("无权读取该活动受控数据");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String schedule(Long eventId, Long operatorId) {
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (event.getStatus() == null || event.getStatus() != OfficialEvent.ST_DRAFT) {
            return "仅草稿活动可以提交排期";
        }
        if (!isEffectiveOrganizerOperator(event, v2Mapper.selectPartiesByEventForUpdate(eventId), operatorId)) {
            return "仅本场有效主办方账号可执行该命令";
        }
        if (OfficialEvent.CONTROL_PENDING_REVIEW.equals(event.getControlState())) {
            return "活动已提交审核，请等待审核结果";
        }
        OfficialEventV2PublishCheck check = checkPublishableInternal(eventId);
        if (!check.isPass()) {
            return "发布检查未通过：" + join(check.getBlockers());
        }
        // 2026-10-20 分级审核:官方活动先审后发。文本过统一门禁(PRE_REVIEW),明确违规直接拒;
        // 其余一律进待审(仍是草稿,不公开、不进生命周期),审核台通过后 applyAuditDecision 才真正排期。
        PublishSafetyRequest safety = new PublishSafetyRequest();
        safety.setContentType(AuditTask.BIZ_OFFICIAL_EVENT);
        safety.setMemberId(operatorId);
        safety.setOpenid(openidOf(operatorId));
        safety.setText(IWxContentSecurityService.joinText(event.getTitle(), event.getSubtitle(), event.getStory()));
        safety.setReviewMode(PublishSafetyRequest.ReviewMode.PRE_REVIEW.name());
        SafetyDecision decision = contentSafetyGate.decide(safety);
        if (decision == null || decision.isBlock()) {
            return "活动内容包含违规信息，请修改后重试";
        }
        event.setControlState(OfficialEvent.CONTROL_PENDING_REVIEW);
        eventMapper.updateOfficialEvent(event);
        v2Mapper.insertAuditLog(eventId, "SUBMITTED_FOR_REVIEW", operatorId, "发布检查通过，已提交平台审核", null, null);
        JSONObject snap = new JSONObject();
        snap.put("bizType", AuditTask.BIZ_OFFICIAL_EVENT);
        snap.put("bizId", eventId);
        snap.put("contractVersion", event.getContractVersion());
        snap.put("title", event.getTitle());
        snap.put("subtitle", event.getSubtitle());
        snap.put("story", event.getStory());
        snap.put("coverImg", event.getCoverImg());
        snap.put("city", event.getCity());
        snap.put("gateReason", decision.getReasonCode());
        auditTaskService.submit(AuditTask.BIZ_OFFICIAL_EVENT, eventId, operatorId, "user_publish", snap.toJSONString());
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyAuditDecision(Long eventId, boolean approved, String reason, Long submitterId) {
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (event.getStatus() == null || event.getStatus() != OfficialEvent.ST_DRAFT
                || !OfficialEvent.CONTROL_PENDING_REVIEW.equals(event.getControlState())) {
            throw new ServiceException("官方活动审核状态已变化，请刷新后重试");
        }
        if (!approved) {
            // 驳回:退回草稿(仍不公开),主办方可修改后重新提交排期
            event.setControlState(CONTROL_NORMAL);
            eventMapper.updateOfficialEvent(event);
            v2Mapper.insertAuditLog(eventId, "REVIEW_REJECTED", submitterId,
                    "平台审核未通过：" + (reason == null ? "" : reason), null, null);
            return;
        }
        // 审核期间时间线可能已过期:过审时复核同一套结构化发布检查,不通过则审核不提交
        OfficialEventV2PublishCheck check = checkPublishableInternal(eventId);
        if (!check.isPass()) {
            throw new ServiceException("发布检查未通过：" + join(check.getBlockers()));
        }
        activateScheduled(event, eventId, submitterId, "平台审核通过，活动进入已排期");
    }

    private void activateScheduled(OfficialEvent event, Long eventId, Long operatorId, String note) {
        v2Mapper.activateDraftBindings(eventId);
        v2Mapper.activateDraftMissions(eventId);
        v2Mapper.activateDraftRequirements(eventId);
        event.setStatus(OfficialEvent.ST_SCHEDULED);
        event.setControlState(CONTROL_NORMAL);
        event.setPausedFromStatus(null);
        event.setPausedAt(null);
        event.setPausedBy(null);
        event.setPausedReason(null);
        event.setPauseImpactScope(null);
        event.setPauseAlternativePlan(null);
        event.setPauseNotificationPlan(null);
        event.setPauseNotificationStatus(null);
        event.setResumeNote(null);
        eventMapper.updateOfficialEvent(event);
        v2Mapper.updateV2ControlState(eventId, CONTROL_NORMAL, null, null, null, null,
                null, null, null, null, null);
        v2Mapper.insertAuditLog(eventId, "SCHEDULED", operatorId, note, null, null);
    }

    private String openidOf(Long memberId) {
        try {
            UmsMember m = umsMemberService.selectUmsMemberById(memberId);
            return m == null ? null : m.getWxOpenId();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String signup(Long eventId, Long memberId) {
        // 每个活动的限额报名在同一活动行锁内检查并写入，避免两个新报名同时越过上限。
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (isPaused(event)) return "活动已暂停";
        if (event.getStatus() == null || (event.getStatus() != OfficialEvent.ST_SIGNUP && event.getStatus() != OfficialEvent.ST_LIVE)) {
            return "活动当前不可报名";
        }
        // 资格拒绝必须发生在兼容报名投影写入前；返回错误字符串不会回滚事务。
        OfficialEventParticipation current = v2Mapper.selectParticipation(eventId, memberId);
        if (current != null && !OfficialEventParticipation.STATE_ACTIVE.equals(current.getState())) {
            return "当前参与资格已失效";
        }
        if (event.getSignupLimit() != null && event.getSignupLimit() > 0
                && current == null
                && v2Mapper.countActiveParticipations(eventId) >= event.getSignupLimit()) {
            return "报名人数已满";
        }
        EventSignup legacy = signupMapper.selectByEventAndMember(eventId, memberId);
        if (legacy == null) {
            EventSignup signup = new EventSignup();
            signup.setEventId(eventId);
            signup.setMemberId(memberId);
            signup.setStatus(EventSignup.ST_SIGNED);
            signup.setProgress(0);
            signupMapper.insertIgnore(signup);
            legacy = signupMapper.selectByEventAndMember(eventId, memberId);
        }
        if (current == null) {
            OfficialEventParticipation participation = new OfficialEventParticipation();
            participation.setEventId(eventId);
            participation.setMemberId(memberId);
            participation.setSignupId(legacy == null ? null : legacy.getId());
            participation.setState(OfficialEventParticipation.STATE_ACTIVE);
            participation.setJoinedAt(new Date());
            v2Mapper.insertParticipationIgnore(participation);
            v2Mapper.insertAuditLog(eventId, "PARTICIPATION_JOINED", memberId, "玩家报名并建立 V2 有效参与关系", null, null);
        }
        return null;
    }

    @Override
    public Map<String, Object> detail(Long eventId, Long memberId) {
        OfficialEvent event = requirePublicV2Event(eventId);
        Map<String, Object> data = eventBase(event);
        List<OfficialEventBinding> bindings = v2Mapper.selectBindingsByEvent(eventId);
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        Map<Long, OfficialEventBinding> bindingById = new HashMap<>();
        for (OfficialEventBinding binding : bindings) bindingById.put(binding.getId(), binding);
        Map<Long, Date> evidenceTimes = memberId == null || memberId <= 0
                ? new HashMap<Long, Date>() : evidenceTimesByMission(eventId, memberId);
        List<Map<String, Object>> cards = new ArrayList<>();
        for (OfficialEventMission mission : missions) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("missionCode", mission.getMissionCode());
            card.put("missionType", mission.getMissionType());
            card.put("title", mission.getTitle());
            card.put("description", mission.getShortDescription());
            card.put("status", mission.getStatus());
            card.put("binding", bindingById.get(mission.getBindingId()));
            boolean complete = memberId != null && memberId > 0 && missionComplete(mission, evidenceTimes);
            card.put("complete", complete);
            card.put("canVerifyArrival", isArrivalMission(mission)
                    && !OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType()));
            cards.add(card);
        }
        OfficialEventParticipation participation = memberId == null || memberId <= 0 ? null : v2Mapper.selectParticipation(eventId, memberId);
        data.put("bindings", bindings);
        data.put("missions", cards);
        data.put("overlay", buildRoamOverlay(bindings, missions));
        data.put("participants", v2Mapper.countActiveParticipations(eventId));
        data.put("participation", participation);
        data.put("signed", participation != null && OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState()));
        data.put("eligible", participation != null && OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState())
                && allMissionsComplete(eventId, memberId, missions));
        data.put("paused", isPaused(event));
        markRecruitmentGap(data, eventId, event);
        return data;
    }

    /**
     * F21「必须承接」模式失去关键商家后,活动没有可履约的承接方,就不该再作为"可承接/开放中"
     * 对外展示(2026-09-15 产品裁决:商家侧处理,不改活动状态、不碰玩家报名/证据/资金)。
     *
     * <p>只做**标记**,不做撤下/拦截:列表与详情都带上 {@code recruitmentBlocked} 与
     * 玩家可读的 {@code recruitmentBlockedReason},商家招募口径据此撤下,补位承接方接受后
     * 标记自动消失。判据与发布检查/覆盖投影同一套 {@code coverageSatisfied} ——
     * 不是另立一份"缺口"口径。非 REQUIRED_FULFILLMENT 模式不标记(可选合作只是风险)。</p>
     */
    private void markRecruitmentGap(Map<String, Object> target, Long eventId, OfficialEvent event) {
        String reason = criticalCoverageGapReason(eventId, event);
        target.put("recruitmentBlocked", reason != null);
        target.put("recruitmentBlockedReason", reason);
    }

    /** 关键履约缺口的人话说明;覆盖完好或非必须承接模式返回 null。 */
    private String criticalCoverageGapReason(Long eventId, OfficialEvent event) {
        if (event == null || eventId == null
                || !POLICY_REQUIRED_FULFILLMENT.equals(defaultPolicy(event.getFulfillmentPolicy()))) {
            return null;
        }
        List<OfficialEventFulfillmentRequirement> requirements = v2Mapper.selectRequirementsByEvent(eventId);
        if (requirements == null || requirements.isEmpty()) return null;
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        for (OfficialEventFulfillmentRequirement requirement : requirements) {
            if (requirement == null || requirement.getCritical() == null || requirement.getCritical() != 1) continue;
            if (coverageSatisfied(requirement, parties)) continue;
            return "关键承接方缺失，待补位";
        }
        return null;
    }

    private Map<String, Object> activity360Internal(Long eventId) {
        OfficialEvent event = requireV2Event(eventId);
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        List<Map<String, Object>> participantRows = v2Mapper.selectParticipantRewardRows(eventId);
        if (participantRows == null) participantRows = new ArrayList<>();
        for (Map<String, Object> row : participantRows) {
            Long memberId = asLong(row.get("memberId"));
            if (memberId == null) continue;
            row.put("completedMissions", completedMissionCount(eventId, memberId, missions));
            row.put("eligible", allMissionsComplete(eventId, memberId, missions));
        }
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEvent(eventId);
        List<OfficialEventFulfillmentRequirement> requirements = v2Mapper.selectRequirementsByEvent(eventId);
        if (parties == null) parties = new ArrayList<>();
        if (requirements == null) requirements = new ArrayList<>();
        Map<Long, OfficialEventFulfillmentRequirement> requirementById = new HashMap<>();
        for (OfficialEventFulfillmentRequirement requirement : requirements) requirementById.put(requirement.getId(), requirement);
        List<Map<String, Object>> coverageRows = v2Mapper.selectFulfillmentCoverageRows(eventId);
        if (coverageRows == null) coverageRows = new ArrayList<>();
        for (Map<String, Object> row : coverageRows) {
            OfficialEventFulfillmentRequirement requirement = requirementById.get(asLong(row.get("requirementId")));
            boolean covered = requirement != null && coverageSatisfied(requirement, parties);
            row.put("covered", covered);
            row.put("coveredBy", covered ? 1 : 0);
        }
        Map<String, Object> data = eventBase(event);
        data.put("participants", v2Mapper.countActiveParticipations(eventId));
        data.put("validEvidence", v2Mapper.countValidEvidenceByEvent(eventId));
        data.put("completedParticipants", completedParticipantCount(eventId, missions));
        data.put("missions", v2Mapper.selectMission360Rows(eventId));
        data.put("parties", parties);
        data.put("coverage", coverageRows);
        data.put("participantsAndRewards", participantRows);
        List<OfficialBroadcast> notifications = officialBroadcastMapper.selectByEvent(eventId);
        data.put("notifications", notifications == null ? new ArrayList<OfficialBroadcast>() : notifications);
        data.put("sourceRisks", sourceRisks(event, v2Mapper.selectBindingsByEvent(eventId)));
        data.put("publishCheck", checkPublishableInternal(eventId));
        data.put("controlHistory", v2Mapper.selectAuditLogs(eventId));
        data.put("pauseImpactScope", event.getPauseImpactScope());
        data.put("pauseAlternativePlan", event.getPauseAlternativePlan());
        data.put("pauseNotificationPlan", event.getPauseNotificationPlan());
        data.put("pauseNotificationStatus", event.getPauseNotificationStatus());
        data.put("resumeConditions", "有效主办方、关键履约覆盖与原活动时间窗口均满足");
        return data;
    }

    @Override
    public Map<String, Object> activity360ForOperator(Long eventId, Long memberId) {
        requireControlledEventReader(eventId, memberId);
        return activity360Internal(eventId);
    }

    @Override
    public OfficialBroadcast publishBroadcastForOperator(OfficialBroadcast broadcast, Long memberId) {
        if (broadcast == null || broadcast.getEventId() == null) {
            throw new ServiceException("V2 活动通知必须绑定活动");
        }
        requireV2Event(broadcast.getEventId());
        requireControlledEventOperator(broadcast.getEventId(), memberId);
        broadcast.setSenderAccount(memberId);
        return officialBroadcastService.publish(broadcast, memberId);
    }

    @Override
    public Map<String, Object> broadcastStatsForOperator(Long broadcastId, Long memberId) {
        OfficialBroadcast broadcast = officialBroadcastService.getById(broadcastId, memberId);
        if (broadcast == null) throw new ServiceException("通知不存在");
        if (broadcast.getEventId() == null) throw new ServiceException("通知未绑定 V2 活动");
        requireV2Event(broadcast.getEventId());
        // 触达统计是纯读复盘(R9-24 残留补缺):活动结束后原主办按历史参与身份只读回看,
        // 与 360/草稿同用读闸;发布与其它写操作仍走 requireControlledEventOperator。
        requireControlledEventReader(broadcast.getEventId(), memberId);
        return officialBroadcastService.reachStats(broadcastId, memberId);
    }

    @Override
    public Map<String, Object> publicProgress(Long eventId) {
        OfficialEvent event = requirePublicV2Event(eventId);
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("participants", v2Mapper.countActiveParticipations(eventId));
        data.put("done", completedParticipantCount(eventId, missions));
        data.put("collective", collectiveProgress(event));
        return data;
    }

    @Override
    public List<Map<String, Object>> listPublic(String city, Long memberId) {
        List<Map<String, Object>> rows = v2Mapper.selectPublicV2Events(city, memberId);
        if (rows == null) return new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long eventId = asLong(row.get("id"));
            OfficialEvent event = eventId == null ? null : eventMapper.selectOfficialEventById(eventId);
            if (event != null) {
                row.put("collective", collectiveProgress(event));
                markRecruitmentGap(row, eventId, event);
            }
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> myEvents(Long memberId) {
        if (memberId == null || memberId <= 0) return new ArrayList<>();
        List<Map<String, Object>> rows = v2Mapper.selectV2EventsByMember(memberId);
        if (rows == null) return new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long eventId = asLong(row.get("id"));
            OfficialEvent event = eventId == null ? null : eventMapper.selectOfficialEventById(eventId);
            if (event != null) {
                List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
                row.put("eligible", OfficialEventParticipation.STATE_ACTIVE.equals(row.get("participationState"))
                        && allMissionsComplete(eventId, memberId, missions));
                row.put("collective", collectiveProgress(event));
            }
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> verifyArrival(Long eventId, Long memberId, OfficialEventV2ArrivalRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        // 所有会改变 evidence/资格的路径先锁活动行；撤证与结算沿用同一把锁，避免拿旧快照发奖。
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (request == null || StringUtils.isEmpty(request.getMissionCode())) return rejected(result, "任务参数错误");
        OfficialEventMission mission = v2Mapper.selectMissionByEventAndCode(eventId, request.getMissionCode());
        if (mission == null || !isArrivalMission(mission)) return rejected(result, "该任务不支持到达验证");
        String requestId = StringUtils.isEmpty(request.getRequestId()) ? UUID.randomUUID().toString() : request.getRequestId();
        if (requestId.length() > 80) return rejected(result, "请求幂等键过长");
        OfficialEventArrivalVerification duplicate = v2Mapper.selectArrivalVerificationByRequest(eventId, mission.getId(), memberId, requestId);
        if (duplicate != null && OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())) {
            return rejected(result, "ROAM_TRUSTED_LOCATION_REQUIRED");
        }
        if (duplicate != null) return verificationResult(result, duplicate, v2Mapper.countValidEvidence(eventId, mission.getId(), memberId) > 0);
        OfficialEventMissionEvidence priorEvidence = v2Mapper.selectEvidenceByDedupeKey(eventId + ":" + mission.getId() + ":" + memberId);
        if (priorEvidence != null) {
            String riskCode = OfficialEventMissionEvidence.STATUS_VALID.equals(priorEvidence.getStatus())
                    ? "MISSION_ALREADY_COMPLETED" : "MISSION_EVIDENCE_UNAVAILABLE";
            return persistRejectedArrival(result, event, mission, null, memberId, requestId, riskCode);
        }
        if (request.getAccuracyM() != null && (request.getAccuracyM() < 0
                || Double.isNaN(request.getAccuracyM()) || Double.isInfinite(request.getAccuracyM()))) {
            return persistRejectedArrival(result, event, mission, null, memberId, requestId, "LOCATION_ACCURACY_INVALID");
        }
        OfficialEventArrivalVerification previous = v2Mapper.selectLatestArrivalVerification(eventId, mission.getId(), memberId);
        if (previous != null && previous.getOccurredAt() != null
                && new Date().getTime() - previous.getOccurredAt().getTime() < ARRIVAL_MIN_INTERVAL_MILLIS) {
            return persistRejectedArrival(result, event, mission, null, memberId, requestId, "ARRIVAL_TOO_FREQUENT");
        }

        OfficialEventBinding binding = v2Mapper.selectBindingById(mission.getBindingId());
        if (isPaused(event) || event.getStatus() == null || event.getStatus() != OfficialEvent.ST_LIVE) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "ACTIVITY_NOT_LIVE");
        }
        OfficialEventParticipation participation = v2Mapper.selectParticipation(eventId, memberId);
        if (participation == null || !OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState())) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "NOT_SIGNED");
        }
        Date occurredAt = new Date();
        if (!evidenceWindowOpen(event, mission, occurredAt)) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "MISSION_NOT_AVAILABLE");
        }
        if (binding == null || !OfficialEventBinding.STATUS_ACTIVE.equals(binding.getStatus())) {
            return persistRejectedArrival(result, event, mission, null, memberId, requestId, "BINDING_UNAVAILABLE");
        }
        if (!bindingSupportsMission(mission, binding)) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "BINDING_SOURCE_INVALID");
        }
        if (request.getLatitude() == null || request.getLongitude() == null || !validCoordinate(request.getLatitude(), request.getLongitude())) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "LOCATION_INVALID");
        }
        int maxAccuracy = maxAccuracy(mission);
        if (request.getAccuracyM() == null || request.getAccuracyM() < 0 || Double.isNaN(request.getAccuracyM())
                || Double.isInfinite(request.getAccuracyM()) || request.getAccuracyM() > maxAccuracy) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "LOCATION_ACCURACY_TOO_LOW");
        }

        double targetLat;
        double targetLng;
        int radius;
        String verificationType;
        boolean untrustedRoamLocation = false;
        if (OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())) {
            // Gate 0 受控样本 + 负控回读见 OfficialEventV2ServiceTest 的 gate0_* 用例；
            // 开关保留为紧急停闸——关掉后同一合法样本也必须被拒（有负控钉住）。
            if (!roamPoiArrivalEnabled()) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "ROAM_POI_ADAPTER_DISABLED");
            }
            if (binding.getSourceRefId() == null || request.getSessionId() == null) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "ROAM_CONTEXT_REQUIRED");
            }
            RoamSession session = roamMapper.selectSessionById(request.getSessionId());
            if (session == null || !memberId.equals(session.getMemberId())) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "SESSION_NOT_OWNED");
            }
            if (!Integer.valueOf(0).equals(session.getStatus())) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "SESSION_NOT_ACTIVE");
            }
            List<RoamPoi> pois = roamMapper.selectPoisByIds(java.util.Collections.singletonList(binding.getSourceRefId()));
            if (pois == null || pois.isEmpty() || pois.get(0).getLat() == null || pois.get(0).getLng() == null) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "POI_UNAVAILABLE");
            }
            RoamPoi poi = pois.get(0);
            targetLat = poi.getLat().doubleValue();
            targetLng = poi.getLng().doubleValue();
            radius = poi.getRadiusM() != null && poi.getRadiusM() > 0 ? poi.getRadiusM() : 120;
            verificationType = OfficialEventArrivalVerification.TYPE_ROAM_POI;
            untrustedRoamLocation = true;
            if (movesTooFast(memberId, targetLat, targetLng)) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "MOVE_TOO_FAST");
            }
        } else if (OfficialEventMission.TYPE_EVENT_POINT_ARRIVAL.equals(mission.getMissionType())) {
            // Gate 0 未完成前，活动自有点只能展示，不允许把客户端坐标接入奖励链路。
            if (!eventPointArrivalEnabled()) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "EVENT_POINT_ADAPTER_DISABLED");
            }
            if (binding.getLatitude() == null || binding.getLongitude() == null || binding.getRadiusM() == null || binding.getRadiusM() <= 0) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "EVENT_POINT_CONFIG_INVALID");
            }
            targetLat = binding.getLatitude().doubleValue();
            targetLng = binding.getLongitude().doubleValue();
            radius = binding.getRadiusM();
            verificationType = OfficialEventArrivalVerification.TYPE_EVENT_POINT;
            if (movesTooFast(memberId, targetLat, targetLng)) {
                return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "MOVE_TOO_FAST");
            }
        } else {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "MISSION_TYPE_INVALID");
        }

        int distance = (int) Math.round(GeoDistanceUtils.haversineMeters(request.getLatitude(), request.getLongitude(), targetLat, targetLng));
        if (distance > radius + GPS_TOLERANCE_M) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId, "DISTANCE_TOO_FAR", distance, accuracyBucket(request.getAccuracyM()));
        }
        if (untrustedRoamLocation) {
            return persistRejectedArrival(result, event, mission, binding, memberId, requestId,
                    "ROAM_TRUSTED_LOCATION_REQUIRED", distance, accuracyBucket(request.getAccuracyM()));
        }
        OfficialEventArrivalVerification verification = new OfficialEventArrivalVerification();
        verification.setEventId(eventId);
        verification.setMissionId(mission.getId());
        verification.setBindingId(binding.getId());
        verification.setMemberId(memberId);
        verification.setSessionId(request.getSessionId());
        verification.setVerificationType(verificationType);
        verification.setRequestId(requestId);
        verification.setDistanceM(distance);
        verification.setAccuracyBucket(accuracyBucket(request.getAccuracyM()));
        verification.setRuleVersion(mission.getRuleVersion());
        verification.setResult(OfficialEventArrivalVerification.RESULT_ACCEPTED);
        verification.setOccurredAt(occurredAt);
        int verificationInserted = v2Mapper.insertArrivalVerificationIgnore(verification);
        if (verificationInserted != 1) {
            OfficialEventArrivalVerification existing = v2Mapper.selectArrivalVerificationByRequest(eventId, mission.getId(), memberId, requestId);
            return verificationResult(result, existing, v2Mapper.countValidEvidence(eventId, mission.getId(), memberId) > 0);
        }
        if (verification.getId() == null) throw new ServiceException("到达验证事实写入后未返回主键");

        OfficialEventMissionEvidence evidence = new OfficialEventMissionEvidence();
        evidence.setEventId(eventId);
        evidence.setMissionId(mission.getId());
        evidence.setMemberId(memberId);
        evidence.setSignupId(participation.getSignupId());
        evidence.setSourceType("ARRIVAL_VERIFICATION");
        evidence.setSourceFactId(String.valueOf(verification.getId()));
        evidence.setDedupeKey(eventId + ":" + mission.getId() + ":" + memberId);
        evidence.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        evidence.setSourceSnapshotJson(JSON.toJSONString(arrivalSnapshot(binding, distance, verification.getAccuracyBucket())));
        evidence.setRuleSnapshotJson(mission.getRuleJson());
        evidence.setOccurredAt(verification.getOccurredAt());
        evidence.setValidatedAt(new Date());
        if (v2Mapper.insertEvidenceIgnore(evidence) != 1) {
            // verification 与 evidence 必须同成同败；不能留下 ACCEPTED 却没有可调证 evidence 的半提交事实。
            throw new ServiceException("到达证据写入冲突，请重试");
        }
        boolean completed = v2Mapper.countValidEvidence(eventId, mission.getId(), memberId) > 0;
        v2Mapper.insertAuditLog(eventId, "ARRIVAL_VERIFIED", memberId, "服务端到达验证 " + mission.getMissionCode(), null, null);
        result.put("accepted", true);
        result.put("completed", completed);
        result.put("distanceM", distance);
        result.put("requestId", requestId);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordThemeCompletion(Long memberId, Long topicId, String sourceFactId, Date occurredAt) {
        if (memberId == null || topicId == null || StringUtils.isEmpty(sourceFactId) || occurredAt == null) return;
        List<OfficialEventMission> missions = v2Mapper.selectActiveThemeMissions(topicId);
        if (missions == null) return;
        List<OfficialEventMission> eligible = new ArrayList<>();
        for (OfficialEventMission mission : missions) {
            OfficialEvent event = eventMapper.selectOfficialEventById(mission.getEventId());
            if (!isV2(event) || !evidenceWindowOpen(event, mission, occurredAt)) continue;
            OfficialEventParticipation participation = v2Mapper.selectParticipation(mission.getEventId(), memberId);
            if (participation == null || !OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState())) continue;
            eligible.add(mission);
        }
        // P0 一项来源事实只可归因一项任务。多个活动/任务同时命中时拒绝自动选择，
        // 由后续 P1 显式分配能力处理，不能把同一次主题完成复制为多项 evidence。
        if (eligible.size() != 1) {
            if (eligible.size() > 1) {
                for (OfficialEventMission mission : eligible) {
                    v2Mapper.insertAuditLog(mission.getEventId(), "THEME_EVIDENCE_AMBIGUOUS", memberId,
                            "来源事实同时命中多项任务，P0 拒绝跨任务归因：" + sourceFactId, null, null);
                }
            }
            return;
        }
        OfficialEventMission mission = eligible.get(0);
        OfficialEvent lockedEvent = requireV2EventForUpdate(mission.getEventId());
        if (!evidenceWindowOpen(lockedEvent, mission, occurredAt)) return;
        OfficialEventParticipation participation = v2Mapper.selectParticipation(mission.getEventId(), memberId);
        if (participation == null || !OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState())) return;
        OfficialEventMissionEvidence evidence = new OfficialEventMissionEvidence();
        evidence.setEventId(mission.getEventId());
        evidence.setMissionId(mission.getId());
        evidence.setMemberId(memberId);
        evidence.setSignupId(participation == null ? null : participation.getSignupId());
        evidence.setSourceType(sourceFactId.startsWith("registration:") ? "THEME_EXPLORE" : "THEME_COMPLETION");
        evidence.setSourceFactId(sourceFactId);
        evidence.setDedupeKey(mission.getEventId() + ":" + mission.getId() + ":" + memberId);
        evidence.setStatus(OfficialEventMissionEvidence.STATUS_VALID);
        evidence.setSourceSnapshotJson(JSON.toJSONString(topicSnapshot(topicId, sourceFactId)));
        evidence.setRuleSnapshotJson(mission.getRuleJson());
        evidence.setOccurredAt(occurredAt);
        evidence.setValidatedAt(new Date());
        if (v2Mapper.insertEvidenceIgnore(evidence) != 1) {
            v2Mapper.insertAuditLog(mission.getEventId(), "THEME_EVIDENCE_ALREADY_CLAIMED", memberId,
                    "来源事实已归因其他 P0 任务：" + sourceFactId, null, null);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeThemeCompletion(Long registrationId, Long topicId, String reason, Long operatorId) {
        if (registrationId == null || topicId == null) return;
        String actualReason = StringUtils.isEmpty(reason) ? "主题核销已撤销" : reason;
        // 锁顺序固定为 event -> evidence；结算和到达适配器也先锁活动行，避免撤证遗漏刚生成的奖励流水。
        List<Long> candidateEventIds = v2Mapper.selectEventIdsByThemeExploreEvidence(registrationId, topicId);
        List<Long> orderedEventIds = uniqueSortedEventIds(candidateEventIds);
        if (orderedEventIds.isEmpty()) return;
        for (Long eventId : orderedEventIds) requireV2EventForUpdate(eventId);
        List<Long> affectedEventIds = v2Mapper.selectEventIdsByThemeExploreEvidenceForUpdate(registrationId, topicId);
        int revoked = v2Mapper.revokeThemeExploreEvidence(registrationId, topicId, actualReason, operatorId);
        if (revoked > 0) {
            // evidence 是资格与活动 360 的真源；读模型从当前 VALID 集聚合，不再保留过期 progress。
            v2Mapper.markRewardManualReviewByThemeExplore(registrationId, topicId,
                    StringUtils.isEmpty(reason) ? "主题完成证据已撤销" : reason);
            if (affectedEventIds != null) {
                for (Long eventId : affectedEventIds) {
                    if (eventId == null || eventId <= 0) continue;
                    v2Mapper.insertAuditLog(eventId, "THEME_EVIDENCE_REVOKED", operatorId,
                            "撤销主题完成 evidence registration=" + registrationId + ",topic=" + topicId, null, null);
                }
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String revokeEvidence(Long evidenceId, Long operatorId, String reason) {
        if (evidenceId == null || evidenceId <= 0) return "证据不存在";
        if (StringUtils.isEmpty(reason)) return "撤销证据必须填写原因";
        OfficialEventMissionEvidence evidence = v2Mapper.selectEvidenceById(evidenceId);
        if (evidence == null) return "证据不存在";
        requireV2EventForUpdate(evidence.getEventId());
        evidence = v2Mapper.selectEvidenceById(evidenceId);
        if (evidence == null) return "证据不存在";
        if (v2Mapper.revokeEvidenceById(evidenceId, reason, operatorId) != 1) return "该证据当前不可撤销";
        v2Mapper.markRewardManualReviewByEvidenceId(evidenceId, "证据已撤销：" + reason);
        v2Mapper.insertAuditLog(evidence.getEventId(), "EVIDENCE_REVOKED", operatorId,
                "撤销 evidence=" + evidenceId + "，原因：" + reason, "VALID", "REVOKED");
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String invalidateParticipation(Long eventId, Long memberId, String state, Long operatorId, String reason) {
        if (!OfficialEventParticipation.STATE_LEFT.equals(state) && !OfficialEventParticipation.STATE_REFUNDED.equals(state)
                && !OfficialEventParticipation.STATE_BANNED.equals(state)) return "参与状态不合法";
        if (StringUtils.isEmpty(reason)) return "参与资格失效必须填写原因";
        requireV2EventForUpdate(eventId);
        OfficialEventParticipation participation = v2Mapper.selectParticipation(eventId, memberId);
        if (participation == null) return "参与关系不存在";
        if (!OfficialEventParticipation.STATE_ACTIVE.equals(participation.getState())) return "参与资格已失效";
        v2Mapper.updateParticipationState(eventId, memberId, state, reason);
        // 活动锁与结算共用，资格失效后不得留下可继续投递或已发待处置的奖励。
        v2Mapper.markRewardManualReviewByEventAndMember(eventId, memberId, "参与资格失效（" + state + "）：" + reason);
        v2Mapper.insertAuditLog(eventId, "PARTICIPATION_" + state, operatorId, reason,
                OfficialEventParticipation.STATE_ACTIVE, state);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String respondParty(Long partyId, Long memberId, String action, String reason) {
        OfficialEventParty initial = v2Mapper.selectPartyById(partyId);
        if (initial == null) return "邀约不存在";
        OfficialEvent event = requireV2EventForUpdate(initial.getEventId());
        // 与草稿替换保持 event -> party 锁顺序；重读后才判断状态和写审计，避免双击或删除后的假审计。
        OfficialEventParty party = partyById(v2Mapper.selectPartiesByEventForUpdate(event.getId()), partyId);
        if (party == null) return "邀约不存在";
        if (isPaused(event)) return "活动已暂停，暂停期间不能变更外部履约";
        if (event.getStatus() != null && event.getStatus() >= OfficialEvent.ST_SETTLING) return "活动已进入结算或结束，不能变更承接";
        if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) return "官方主办关系只能由专用受控命令处理";
        if (!partyAvailable(party)) return "承接主体当前不可用";
        if (!partyOwnedBy(party, memberId)) return "无权处理该邀约";
        String next;
        if ("ACCEPT".equalsIgnoreCase(action)) {
            if (!OfficialEventParty.STATUS_INVITED.equals(party.getStatus())) return "当前邀约不可接受";
            next = OfficialEventParty.STATUS_ACCEPTED;
        } else if ("DECLINE".equalsIgnoreCase(action)) {
            if (!OfficialEventParty.STATUS_INVITED.equals(party.getStatus())) return "当前邀约不可拒绝";
            next = OfficialEventParty.STATUS_DECLINED;
        } else if ("WITHDRAW".equalsIgnoreCase(action)) {
            if (!OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus()) && !OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus())) {
                return "当前邀约不可退出";
            }
            next = OfficialEventParty.STATUS_WITHDRAWN;
        } else {
            return "未知邀约动作";
        }
        v2Mapper.updatePartyStatus(partyId, next, memberId, reason);
        v2Mapper.insertAuditLog(party.getEventId(), "PARTY_" + next, memberId, "承接方处理邀约", party.getStatus(), next);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String inviteOrganizer(Long eventId, Long operatorMemberId, Long invitedMemberId, String reason) {
        if (invitedMemberId == null || invitedMemberId <= 0) return "请选择被邀请的官方会员";
        if (StringUtils.isEmpty(reason)) return "邀请主办方必须填写授权说明";
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (event.getStatus() == null || event.getStatus() >= OfficialEvent.ST_SETTLING) {
            return "当前活动状态不能调整主办方";
        }
        if (!isEffectiveOfficialMember(invitedMemberId)) return "被邀请账号不是有效官方发布会员";
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEventForUpdate(eventId);
        if (parties == null) parties = new ArrayList<OfficialEventParty>();
        boolean currentOrganizer = isEffectiveOrganizerOperator(event, parties, operatorMemberId);
        boolean creatorRecovery = OfficialEventParty.IDENTITY_UMS_MEMBER.equals(event.getCreatorIdentitySource())
                && operatorMemberId != null && operatorMemberId.equals(event.getSenderAccount())
                && effectiveOrganizerCount(event, parties) == 0;
        if (!currentOrganizer && !creatorRecovery) return "仅本场有效主办方账号可执行该命令";
        for (OfficialEventParty party : parties) {
            if (OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole()) && invitedMemberId.equals(party.getPartyId())
                    && !OfficialEventParty.STATUS_WITHDRAWN.equals(party.getStatus())
                    && !OfficialEventParty.STATUS_DECLINED.equals(party.getStatus())) {
                return "该官方会员已有未关闭的主办关系";
            }
        }
        OfficialEventParty invited = new OfficialEventParty();
        invited.setEventId(eventId);
        invited.setPartyType(OfficialEventParty.TYPE_OFFICIAL);
        invited.setPartyId(invitedMemberId);
        invited.setIdentitySource(OfficialEventParty.IDENTITY_UMS_MEMBER);
        invited.setOrganizerAuthority(0);
        invited.setRole(OfficialEventParty.ROLE_ORGANIZER);
        invited.setStatus(OfficialEventParty.STATUS_INVITED);
        invited.setInvitedBy(operatorMemberId);
        invited.setInvitedAt(new Date());
        invited.setStatusReason(reason);
        v2Mapper.insertParty(invited);
        v2Mapper.insertAuditLog(eventId, creatorRecovery ? "ORGANIZER_RECOVERY_INVITED" : "ORGANIZER_INVITED", operatorMemberId,
                (creatorRecovery ? "原主办失效后的恢复邀请：" : "邀请") + "官方会员 " + invitedMemberId + " 成为候任主办方：" + reason, null, null);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String acceptOrganizerInvitation(Long partyId, Long memberId, String reason) {
        if (partyId == null || partyId <= 0 || memberId == null || memberId <= 0) return "主办邀约不存在";
        if (!isEffectiveOfficialMember(memberId)) return "当前账号不是有效官方发布会员";
        OfficialEventParty initial = v2Mapper.selectPartyById(partyId);
        if (initial == null) return "主办邀约不存在";
        OfficialEvent event = requireV2EventForUpdate(initial.getEventId());
        if (event.getStatus() == null || event.getStatus() >= OfficialEvent.ST_SETTLING) {
            return "当前活动状态不能接受主办邀约";
        }
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEventForUpdate(event.getId());
        OfficialEventParty party = partyById(parties, partyId);
        if (party == null || !OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                || !OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole()) || !usesOfficialMemberIdentity(party)) {
            return "该邀约不是可接受的官方主办关系";
        }
        if (!memberId.equals(party.getPartyId())) return "无权处理该主办邀约";
        if (!OfficialEventParty.STATUS_INVITED.equals(party.getStatus())) return "当前主办邀约不可接受";
        int effectiveCount = effectiveOrganizerCount(event, parties);
        if (effectiveCount > 1) return "活动主办关系异常，需先人工处置";
        int authority = effectiveCount == 0 ? 1 : 0;
        String nextStatus = isRunning(event) ? OfficialEventParty.STATUS_ACTIVE : OfficialEventParty.STATUS_ACCEPTED;
        v2Mapper.updatePartyStatusAndOrganizerAuthority(partyId, nextStatus, authority, memberId, reason);
        v2Mapper.insertAuditLog(event.getId(), authority == 1 ? "ORGANIZER_ACCEPTED" : "ORGANIZER_SUCCESSOR_ACCEPTED", memberId,
                authority == 1 ? "接受主办邀约并成为当前唯一主办方" : "接受候任主办邀约，等待受控转交", party.getStatus(), nextStatus);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String declineOrganizerInvitation(Long partyId, Long memberId, String reason) {
        if (partyId == null || partyId <= 0 || memberId == null || memberId <= 0) return "主办邀约不存在";
        if (!isEffectiveOfficialMember(memberId)) return "当前账号不是有效官方发布会员";
        OfficialEventParty initial = v2Mapper.selectPartyById(partyId);
        if (initial == null) return "主办邀约不存在";
        OfficialEvent event = requireV2EventForUpdate(initial.getEventId());
        if (event.getStatus() == null || event.getStatus() >= OfficialEvent.ST_SETTLING) {
            return "当前活动状态不能拒绝主办邀约";
        }
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEventForUpdate(event.getId());
        OfficialEventParty party = partyById(parties, partyId);
        if (party == null || !OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                || !OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole()) || !usesOfficialMemberIdentity(party)) {
            return "该邀约不是可拒绝的官方主办关系";
        }
        if (!memberId.equals(party.getPartyId())) return "无权处理该主办邀约";
        if (!OfficialEventParty.STATUS_INVITED.equals(party.getStatus())) return "当前主办邀约不可拒绝";
        String actualReason = StringUtils.isEmpty(reason) ? "受邀官方会员拒绝主办职责" : reason;
        v2Mapper.updatePartyStatusAndOrganizerAuthority(partyId, OfficialEventParty.STATUS_DECLINED, 0, memberId, actualReason);
        v2Mapper.insertAuditLog(event.getId(), "ORGANIZER_DECLINED", memberId, actualReason,
                party.getStatus(), OfficialEventParty.STATUS_DECLINED);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String transferOrganizer(Long eventId, Long operatorMemberId, Long successorPartyId, String reason) {
        if (successorPartyId == null || successorPartyId <= 0) return "请选择已接受的候任主办方";
        if (StringUtils.isEmpty(reason)) return "主办转交必须填写原因";
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (event.getStatus() == null || event.getStatus() >= OfficialEvent.ST_SETTLING) {
            return "当前活动状态不能转交主办方";
        }
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEventForUpdate(eventId);
        OfficialEventParty current = effectiveOrganizer(event, parties);
        if (current == null || !operatorMemberId.equals(current.getPartyId())) return "仅本场有效主办方账号可执行该命令";
        OfficialEventParty successor = partyById(parties, successorPartyId);
        if (successor == null || !OfficialEventParty.TYPE_OFFICIAL.equals(successor.getPartyType())
                || !OfficialEventParty.ROLE_ORGANIZER.equals(successor.getRole()) || !usesOfficialMemberIdentity(successor)
                || !Integer.valueOf(0).equals(successor.getOrganizerAuthority())
                || (!OfficialEventParty.STATUS_ACCEPTED.equals(successor.getStatus()) && !OfficialEventParty.STATUS_ACTIVE.equals(successor.getStatus()))
                || !isEffectiveOfficialMember(successor.getPartyId())) {
            return "候任主办方未完成有效接受";
        }
        String successorStatus = isRunning(event) ? OfficialEventParty.STATUS_ACTIVE : OfficialEventParty.STATUS_ACCEPTED;
        // 同一事务且先锁定同场 party；提交前外部不可见中间态，避免零主办或双主办。
        v2Mapper.updatePartyStatusAndOrganizerAuthority(current.getId(), OfficialEventParty.STATUS_WITHDRAWN, 0, operatorMemberId, reason);
        v2Mapper.updatePartyStatusAndOrganizerAuthority(successor.getId(), successorStatus, 1, operatorMemberId, reason);
        v2Mapper.insertAuditLog(eventId, "ORGANIZER_TRANSFERRED", operatorMemberId,
                "主办方从会员 " + current.getPartyId() + " 转交给会员 " + successor.getPartyId() + "：" + reason,
                String.valueOf(current.getPartyId()), String.valueOf(successor.getPartyId()));
        return null;
    }

    @Override
    public List<Map<String, Object>> partyInbox(Long memberId) {
        if (memberId == null || memberId <= 0) return new ArrayList<>();
        List<Map<String, Object>> rows = v2Mapper.selectPartyInboxForMember(memberId);
        return rows == null ? new ArrayList<Map<String, Object>>() : rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String pause(Long eventId, Long operatorId, String reason, String impactScope,
                        String alternativePlan, String notificationPlan) {
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (isPaused(event)) return null;
        String authorizationError = requireEffectiveOrganizerOperator(event, operatorId);
        if (authorizationError != null) return authorizationError;
        if (StringUtils.isEmpty(reason) || StringUtils.isEmpty(impactScope)
                || StringUtils.isEmpty(alternativePlan) || StringUtils.isEmpty(notificationPlan)) {
            return "暂停必须填写原因、影响范围、替代方案和通知策略";
        }
        // SETTLING 已可投递奖励，不能再写 PAUSED 造成“对外显示暂停、实际继续发奖”的矛盾状态。
        if (event.getStatus() == null || event.getStatus() < OfficialEvent.ST_SCHEDULED || event.getStatus() >= OfficialEvent.ST_SETTLING) {
            return "当前生命周期不能暂停";
        }
        event.setControlState(CONTROL_PAUSED);
        event.setPausedFromStatus(event.getStatus());
        event.setPausedAt(new Date());
        event.setPausedBy(operatorId);
        event.setPausedReason(reason);
        event.setPauseImpactScope(impactScope);
        event.setPauseAlternativePlan(alternativePlan);
        event.setPauseNotificationPlan(notificationPlan);
        event.setPauseNotificationStatus(PAUSE_NOTIFICATION_PENDING);
        event.setResumeNote(null);
        eventMapper.updateOfficialEvent(event);
        v2Mapper.updateV2ControlState(eventId, CONTROL_PAUSED, event.getPausedFromStatus(), event.getPausedAt(), operatorId, reason,
                impactScope, alternativePlan, notificationPlan, PAUSE_NOTIFICATION_PENDING, null);
        JSONObject pauseSnapshot = new JSONObject();
        pauseSnapshot.put("impactScope", impactScope);
        pauseSnapshot.put("alternativePlan", alternativePlan);
        pauseSnapshot.put("notificationPlan", notificationPlan);
        pauseSnapshot.put("notificationStatus", PAUSE_NOTIFICATION_PENDING);
        v2Mapper.insertAuditLog(eventId, "PAUSED", operatorId, reason, null, pauseSnapshot.toJSONString());
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resume(Long eventId, Long operatorId, String reason) {
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (!isPaused(event)) return "活动未暂停";
        String authorizationError = requireEffectiveOrganizerOperator(event, operatorId);
        if (authorizationError != null) return authorizationError;
        if (StringUtils.isEmpty(reason)) return "恢复必须填写风险处理说明";
        Date now = new Date();
        if (event.getActivityEnd() != null && !now.before(event.getActivityEnd())) {
            if (event.getStatus() != null && event.getStatus() < OfficialEvent.ST_SETTLING) {
                advanceLifecycle(event, event.getStatus(), OfficialEvent.ST_SETTLING);
            }
            return "活动已结束，已转入待结算，不能恢复运行";
        }
        OfficialEventV2PublishCheck check = checkPublishableInternal(eventId);
        if (!check.isPass()) return "恢复前检查未通过：" + join(check.getBlockers());
        event.setControlState(CONTROL_NORMAL);
        event.setResumeNote(reason);
        event.setStatus(statusByTime(event, now));
        eventMapper.updateOfficialEvent(event);
        // 跨开场恢复与自然开场一致，不能只推进活动而留下 ACCEPTED 的承接关系。
        if (event.getStatus() == OfficialEvent.ST_LIVE) v2Mapper.activateAcceptedParties(eventId);
        v2Mapper.updateV2ControlState(eventId, CONTROL_NORMAL, event.getPausedFromStatus(), event.getPausedAt(), event.getPausedBy(),
                event.getPausedReason(), event.getPauseImpactScope(), event.getPauseAlternativePlan(),
                event.getPauseNotificationPlan(), event.getPauseNotificationStatus(), reason);
        v2Mapper.insertAuditLog(eventId, "RESUMED", operatorId, reason, null, null);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int settle(Long eventId) {
        OfficialEvent event = requireV2EventForUpdate(eventId);
        if (event.getStatus() == null || (event.getStatus() != OfficialEvent.ST_SETTLING && event.getStatus() != OfficialEvent.ST_ENDED)) {
            throw new ServiceException("活动未进入结算窗口");
        }
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        JSONObject reward = parseObject(event.getRewardJson());
        String ruleVersion = reward == null || StringUtils.isEmpty(reward.getString("rewardRuleVersion"))
                ? "1" : reward.getString("rewardRuleVersion");
        Long couponId = reward == null ? null : reward.getLong("settleCouponId");
        Integer xp = reward == null ? null : reward.getInteger("settleXp");
        boolean badge = reward != null && reward.getBooleanValue("settleBadge");
        Long collectiveCouponId = reward == null ? null : reward.getLong("collectiveCouponId");
        boolean collectiveReached = collectiveReached(event);
        int issued = 0;
        for (Long memberId : v2Mapper.selectActiveParticipationMemberIds(eventId)) {
            boolean eligible = allMissionsComplete(eventId, memberId, missions);
            OfficialEventRewardIssuance issuance = v2Mapper.selectRewardIssuance(eventId, memberId, ruleVersion);
            if (!eligible) {
                if (issuance == null) {
                    issuance = newIssuance(event, memberId, ruleVersion, OfficialEventRewardIssuance.NOT_ELIGIBLE);
                    v2Mapper.insertRewardIssuanceIgnore(issuance);
                } else if (!OfficialEventRewardIssuance.ISSUED.equals(issuance.getStatus())
                        && !OfficialEventRewardIssuance.CLAIMED.equals(issuance.getStatus())
                        && !OfficialEventRewardIssuance.MANUAL_REVIEW.equals(issuance.getStatus())) {
                    v2Mapper.updateRewardIssuanceStatus(issuance.getId(), OfficialEventRewardIssuance.NOT_ELIGIBLE, "必做任务未完成");
                }
                continue;
            }
            if (issuance == null) {
                issuance = newIssuance(event, memberId, ruleVersion, OfficialEventRewardIssuance.PENDING_ISSUE);
                v2Mapper.insertRewardIssuanceIgnore(issuance);
                issuance = v2Mapper.selectRewardIssuance(eventId, memberId, ruleVersion);
            } else if (OfficialEventRewardIssuance.NOT_ELIGIBLE.equals(issuance.getStatus())
                    || OfficialEventRewardIssuance.FAILED.equals(issuance.getStatus())) {
                v2Mapper.updateRewardIssuanceStatus(issuance.getId(), OfficialEventRewardIssuance.PENDING_ISSUE, null);
                issuance = v2Mapper.selectRewardIssuance(eventId, memberId, ruleVersion);
            }
            if (issuance == null || !OfficialEventRewardIssuance.PENDING_ISSUE.equals(issuance.getStatus())) continue;
            try {
                if (settlementHelper.issue(issuance, couponId, xp, badge, collectiveCouponId, collectiveReached)) issued++;
            } catch (Exception ex) {
                // 锁冲突时 savepoint 已随整笔事务回滚，不能在隐式新事务里接着落 FAILED。
                com.chengyinhub.business.service.support.SavepointLockFailures.rethrowIfLockFailure(ex);
                // Helper 的独立事务已回滚 ISSUED → PENDING；这里落可读失败态，下次结算会重新置 PENDING 重试。
                v2Mapper.updateRewardIssuanceStatus(issuance.getId(), OfficialEventRewardIssuance.FAILED, safeMessage(ex));
            }
        }
        v2Mapper.insertAuditLog(eventId, "SETTLEMENT", null, "V2 资格结算，实际投递 " + issued + " 人", null, null);
        return issued;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void runLifecycle() {
        List<OfficialEvent> active = eventMapper.selectActiveForFlow();
        if (active == null) return;
        Date now = new Date();
        for (OfficialEvent listedEvent : active) {
            OfficialEvent event = eventMapper.selectOfficialEventByIdForUpdate(listedEvent.getId());
            if (!isV2(event)) continue;
            int next = statusByTime(event, now);
            int current = event.getStatus() == null ? OfficialEvent.ST_DRAFT : event.getStatus();
            // 暂停冻结运行中的报名、证据和外部履约，但不能冻结原定结束时间。
            // 到期后仍必须先进入 SETTLING，防止活动永久停在 LIVE + PAUSED。
            if (isPaused(event) && next < OfficialEvent.ST_SETTLING) continue;

            // 必须先把 LIVE 持久化为 SETTLING，后续结算才会读取到合法窗口；不能把两者倒置后用回滚掩盖。
            if (current < OfficialEvent.ST_SETTLING && next >= OfficialEvent.ST_SETTLING) {
                advanceLifecycle(event, current, OfficialEvent.ST_SETTLING);
                current = OfficialEvent.ST_SETTLING;
            }
            if (current == OfficialEvent.ST_SETTLING) {
                try {
                    settle(event.getId());
                } catch (Exception ex) {
                    // 状态已先落库；失败保留在 SETTLING，下一轮可安全重试，不能重新开放 evidence。
                    v2Mapper.insertAuditLog(event.getId(), "SETTLEMENT_FAILED", null, safeMessage(ex), null, null);
                    continue;
                }
                if (next == OfficialEvent.ST_ENDED) {
                    advanceLifecycle(event, OfficialEvent.ST_SETTLING, OfficialEvent.ST_ENDED);
                }
                continue;
            }
            if (next != current) advanceLifecycle(event, current, next);
        }
    }

    private void advanceLifecycle(OfficialEvent event, int before, int after) {
        event.setStatus(after);
        eventMapper.updateOfficialEvent(event);
        if (after == OfficialEvent.ST_LIVE) v2Mapper.activateAcceptedParties(event.getId());
        if (after == OfficialEvent.ST_ENDED) v2Mapper.archiveActiveParties(event.getId());
        v2Mapper.insertAuditLog(event.getId(), "LIFECYCLE_" + after, null, "定时生命周期推进", String.valueOf(before), String.valueOf(after));
    }

    @Override
    public List<Map<String, Object>> activeForRoam(String city, Long memberId) {
        return v2Mapper.selectActiveV2RoamEvents(city, memberId);
    }

    private OfficialEvent requireV2Event(Long eventId) {
        OfficialEvent event = eventMapper.selectOfficialEventById(eventId);
        if (event == null) throw new ServiceException("活动不存在");
        if (!isV2(event)) throw new ServiceException("该活动仍是历史 V1 合同");
        return event;
    }

    /** 玩家公开端只能读取已排期及之后的非下线 V2 活动；草稿仅能走受权的后台草稿接口。 */
    private OfficialEvent requirePublicV2Event(Long eventId) {
        OfficialEvent event = requireV2Event(eventId);
        if (event.getStatus() == null || event.getStatus() < OfficialEvent.ST_SCHEDULED
                || event.getStatus() == OfficialEvent.ST_OFFLINE) {
            throw new ServiceException("活动当前不可见");
        }
        return event;
    }

    private OfficialEvent requireV2EventForUpdate(Long eventId) {
        OfficialEvent event = eventMapper.selectOfficialEventByIdForUpdate(eventId);
        if (event == null) throw new ServiceException("活动不存在");
        if (!isV2(event)) throw new ServiceException("该活动仍是历史 V1 合同");
        return event;
    }

    private boolean isV2(OfficialEvent event) {
        return event != null && event.getContractVersion() != null && event.getContractVersion() >= 2;
    }

    private String defaultPolicy(String policy) {
        return StringUtils.isEmpty(policy) ? POLICY_SELF_RUN : policy;
    }

    /**
     * 整体保存只能替换尚未响应的邀约。已接受、拒绝或转交过的关系是审计事实，
     * 必须保留；主办转交只能走 transferOrganizer，不能借草稿回写绕过。
     */
    private OfficialEventParty validateDraftOverwrite(OfficialEvent event, List<OfficialEventParty> currentParties,
                                                       List<OfficialEventParty> requestedParties, Long operatorId) {
        int effectiveCount = effectiveOrganizerCount(event, currentParties);
        if (effectiveCount > 1) throw new ServiceException("活动存在多个有效主办方，需先人工修复后再保存草稿");
        OfficialEventParty effective = effectiveOrganizer(event, currentParties);
        if (effective == null) return null;
        if (!operatorId.equals(effective.getPartyId())) {
            throw new ServiceException("主办方已转交，仅现任有效主办方可修改草稿");
        }
        Long requestedOrganizerId = requestedOrganizerMemberId(requestedParties, operatorId);
        if (!effective.getPartyId().equals(requestedOrganizerId)) {
            throw new ServiceException("草稿保存不能变更现任主办方；请使用受控转交命令");
        }
        return effective;
    }

    private Long requestedOrganizerMemberId(List<OfficialEventParty> parties, Long defaultMemberId) {
        if (parties == null) return defaultMemberId;
        for (OfficialEventParty party : parties) {
            if (party != null && OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())) return party.getPartyId();
        }
        return defaultMemberId;
    }

    private boolean isPreservedParty(OfficialEventParty requested, List<OfficialEventParty> preservedParties) {
        if (requested == null || preservedParties == null) return false;
        for (OfficialEventParty persisted : preservedParties) {
            if (persisted == null || OfficialEventParty.STATUS_INVITED.equals(persisted.getStatus())) continue;
            if (equalsText(requested.getPartyType(), persisted.getPartyType())
                    && equalsLong(requested.getPartyId(), persisted.getPartyId())
                    && equalsText(requested.getRole(), persisted.getRole())) return true;
        }
        return false;
    }

    /**
     * OFFICIAL 的 party_id 在 V2 中只允许是 ums_member.id。创建人可与主办方分离，
     * 但服务端只会让当前创建人自动成为有效主办；其他官方主办只能以邀约等待本人接受。
     */
    private List<OfficialEventParty> normalizeDraftParties(List<OfficialEventParty> requested, Long officialMemberId) {
        List<OfficialEventParty> parties = requested == null ? new ArrayList<OfficialEventParty>()
                : new ArrayList<OfficialEventParty>(requested);
        OfficialEventParty organizer = null;
        for (OfficialEventParty party : parties) {
            if (!OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())) continue;
            if (!OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) {
                throw new ServiceException("主办方必须使用 OFFICIAL 主体");
            }
            if (organizer != null) {
                throw new ServiceException("一场活动只能配置一位主办方");
            }
            if (!isEffectiveOfficialMember(party.getPartyId())) {
                throw new ServiceException("主办方必须是启用中的白名单官方会员");
            }
            organizer = party;
        }
        if (organizer == null) {
            organizer = new OfficialEventParty();
            organizer.setPartyType(OfficialEventParty.TYPE_OFFICIAL);
            organizer.setPartyId(officialMemberId);
            organizer.setRole(OfficialEventParty.ROLE_ORGANIZER);
            parties.add(organizer);
        }
        for (OfficialEventParty party : parties) {
            // identitySource 是服务端写入的身份域标记，不能信任请求体。
            party.setIdentitySource(OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                    ? OfficialEventParty.IDENTITY_UMS_MEMBER : OfficialEventParty.IDENTITY_DOMAIN_ENTITY);
            party.setOrganizerAuthority(OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                    && officialMemberId.equals(party.getPartyId()) ? 1 : 0);
        }
        return parties;
    }

    private String defaultPartyStatus(OfficialEventParty party, Long operatorId) {
        // 只有显式写入当前白名单会员的 OFFICIAL/ORGANIZER 活动关系可初始 ACCEPTED；
        // sender_account 只是草稿创建审计，绝不自动变成主办授权。
        return OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                && OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                && OfficialEventParty.IDENTITY_UMS_MEMBER.equals(party.getIdentitySource())
                && operatorId != null && operatorId.equals(party.getPartyId()) && isEffectiveOfficialMember(party.getPartyId())
                ? OfficialEventParty.STATUS_ACCEPTED : OfficialEventParty.STATUS_INVITED;
    }

    private void validateDraftBinding(OfficialEventBinding binding) {
        if (binding == null || StringUtils.isEmpty(binding.getBindingCode()) || StringUtils.isEmpty(binding.getBindingType()) || StringUtils.isEmpty(binding.getSourceMode())) {
            throw new ServiceException("绑定缺少编码、类型或来源模式");
        }
        if (!OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode())
                && !OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode())) {
            throw new ServiceException("绑定来源模式不合法");
        }
        if (!bindingSourceModeMatchesBindingType(binding)) {
            throw new ServiceException("绑定 " + binding.getBindingCode() + " 的来源模式与绑定类型不兼容");
        }
        if (OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode()) && binding.getSourceRefId() == null) {
            throw new ServiceException("引用绑定必须填写来源对象");
        }
        if (OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode()) && !hasEventOwnedCoordinates(binding)) {
            throw new ServiceException("活动自有点必须填写坐标与半径");
        }
    }

    private void validateDraftMission(OfficialEventMission mission) {
        if (mission == null || StringUtils.isEmpty(mission.getMissionCode()) || StringUtils.isEmpty(mission.getMissionType())
                || StringUtils.isEmpty(mission.getTitle()) || StringUtils.isEmpty(mission.getRuleJson())) {
            throw new ServiceException("任务缺少编码、类型、标题或结构化规则");
        }
        if (mission.getRequiredCount() == null || mission.getRequiredCount() != 1) {
            throw new ServiceException("P0 任务 required_count 必须为 1");
        }
        if (!validJsonObject(mission.getRuleJson())) throw new ServiceException("任务规则必须是 JSON 对象");
        // 前置关系的格式校验随「顺序与前置」能力一并移除(见 missionComplete 的注释)。
        // 校验留着反而是个诱饵:它意味着还有人能写这个字段。
    }

    private void validateDraftRequirement(OfficialEventFulfillmentRequirement requirement) {
        if (requirement == null || StringUtils.isEmpty(requirement.getTargetType()) || requirement.getTargetId() == null
                || StringUtils.isEmpty(requirement.getRequiredRole()) || requirement.getRequiredCapacity() == null
                || requirement.getRequiredCapacity() <= 0 || requirement.getWindowStart() == null || requirement.getWindowEnd() == null
                || !requirement.getWindowStart().before(requirement.getWindowEnd())) {
            throw new ServiceException("承接需求缺少目标、角色或容量定义");
        }
        if (!OfficialEventParty.ROLE_FULFILLMENT_MERCHANT.equals(requirement.getRequiredRole())
                && !OfficialEventParty.ROLE_FULFILLMENT_CLUB.equals(requirement.getRequiredRole())) {
            throw new ServiceException("P0 履约需求只能要求商家或俱乐部承接");
        }
    }

    private void validateDraftParty(OfficialEventParty party) {
        if (party == null || StringUtils.isEmpty(party.getPartyType()) || party.getPartyId() == null || StringUtils.isEmpty(party.getRole())) {
            throw new ServiceException("承接关系缺少主体或角色");
        }
        if (OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole()) && !OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) {
            throw new ServiceException("主办方必须使用 OFFICIAL 主体");
        }
        if (OfficialEventParty.ROLE_FULFILLMENT_MERCHANT.equals(party.getRole())
                && !OfficialEventParty.TYPE_MERCHANT.equals(party.getPartyType())) {
            throw new ServiceException("商家履约只能由 MERCHANT 主体承接");
        }
        if (OfficialEventParty.ROLE_FULFILLMENT_CLUB.equals(party.getRole())
                && !OfficialEventParty.TYPE_CLUB.equals(party.getPartyType())) {
            throw new ServiceException("俱乐部履约只能由 CLUB 主体承接");
        }
        if ((party.getWindowStart() == null) != (party.getWindowEnd() == null)
                || (party.getWindowStart() != null && !party.getWindowStart().before(party.getWindowEnd()))) {
            throw new ServiceException("承接服务时段必须完整且开始早于结束");
        }
        if (party.getCapacity() != null && party.getCapacity() < 0) {
            throw new ServiceException("承接容量不能小于 0");
        }
    }

    private void validateTimeline(OfficialEvent event, OfficialEventV2PublishCheck check) {
        if (event.getSignupStart() == null || event.getActivityStart() == null || event.getActivityEnd() == null || event.getSettleTime() == null) {
            check.block("报名、开始、结束和结算完成时间均不能为空");
            return;
        }
        if (!event.getSignupStart().before(event.getActivityStart()) || !event.getActivityStart().before(event.getActivityEnd())
                || !event.getActivityEnd().before(event.getSettleTime())) {
            check.block("时间必须满足 报名开始 < 活动开始 < 活动结束 < 结算完成");
        }
    }

    private void validateOrganizer(OfficialEvent event, List<OfficialEventParty> parties, OfficialEventV2PublishCheck check) {
        int valid = 0;
        for (OfficialEventParty party : parties) {
            if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType()) && OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                    && organizerStatusEffective(event, party)
                    && usesOfficialMemberIdentity(party)
                    && isEffectiveOfficialMember(party.getPartyId())) {
                valid++;
            }
        }
        if (valid != 1) check.block("参与型活动必须恰有一位已接受的有效主办方");
    }

    private void validatePublishMission(OfficialEvent event, OfficialEventMission mission, OfficialEventBinding binding,
                                        OfficialEventV2PublishCheck check) {
        if (binding == null) {
            check.block("任务 " + mission.getMissionCode() + " 缺少绑定");
            return;
        }
        if (mission.getRequiredCount() == null || mission.getRequiredCount() != 1) check.block("任务 " + mission.getMissionCode() + " 的 required_count 必须为 1");
        if (!isCompatible(mission.getMissionType(), binding.getBindingType())) {
            check.block("任务 " + mission.getMissionCode() + " 与绑定类型不兼容");
        } else if (!bindingSupportsMission(mission, binding)) {
            check.block("任务 " + mission.getMissionCode() + " 的绑定来源模式不兼容，无法由服务端证据适配器核验");
        }
        if (OfficialEventMission.TYPE_ROAM_SESSION_AREA.equals(mission.getMissionType())) {
            check.block("ROAM_SESSION_AREA 是 P1，P0 不允许发布");
        }
        if (OfficialEventMission.TYPE_EVENT_POINT_ARRIVAL.equals(mission.getMissionType()) && !eventPointArrivalEnabled()) {
            check.block("EVENT_POINT_ARRIVAL 尚未完成 Gate 0 受控定位验证，不能发布或计奖");
        }
        if (OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())) {
            check.block("ROAM_POI_ARRIVAL 尚未完成 Gate 0 抗伪造到达事实验证，不能发布或自动计奖");
        }
        if (OfficialEventBinding.TYPE_ROAM_AREA.equals(binding.getBindingType()) && !OfficialEventMission.TYPE_ROAM_SESSION_AREA.equals(mission.getMissionType())) {
            check.block("ROAM_AREA 在 P0 仅可展示，不能作为任务目标");
        }
        if (OfficialEventBinding.TYPE_DISPLAY_ONLY.equals(binding.getBindingType())) check.block("DISPLAY_ONLY 不能作为任务目标");
        if (OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode()) && binding.getSourceRefId() == null) check.block("引用绑定缺少来源对象");
        if (mission.getAvailableStart() != null && mission.getAvailableEnd() != null
                && !mission.getAvailableStart().before(mission.getAvailableEnd())) {
            check.block("任务 " + mission.getMissionCode() + " 的可用开始必须早于结束");
        }
        if (mission.getAvailableStart() != null && event.getActivityStart() != null
                && mission.getAvailableStart().before(event.getActivityStart())) {
            check.block("任务 " + mission.getMissionCode() + " 的可用开始不能早于活动开始");
        }
        if (mission.getAvailableEnd() != null && event.getActivityEnd() != null
                && mission.getAvailableEnd().after(event.getActivityEnd())) {
            check.block("任务 " + mission.getMissionCode() + " 的可用结束不能晚于活动结束");
        }
    }

    /**
     * P0：一项来源事实只能归因一项可计奖任务。
     *
     * <p>这里原先还校验前置任务引用与前置循环 —— 随「顺序与前置」能力整组移除
     * （裁决表 §1.5 漫游免费层红线；求值侧见 {@link #missionComplete}）。
     * 校验没了不留后门：后台的前置输入框也已删除，字段不再有写入口。
     */
    private void validateMissionBindingUniqueness(List<OfficialEventMission> missions, OfficialEventV2PublishCheck check) {
        Set<Long> usedBindings = new HashSet<>();
        for (OfficialEventMission mission : missions) {
            if (mission == null || mission.getBindingId() == null || !isEvidenceMission(mission)) continue;
            if (!usedBindings.add(mission.getBindingId())) {
                check.block("P0 同一绑定只能对应一个可计奖任务，避免来源事实跨任务复用");
                break;
            }
        }
    }


    private void validateReferenceSources(OfficialEvent event, List<OfficialEventBinding> bindings, OfficialEventV2PublishCheck check) {
        for (String risk : sourceRisks(event, bindings)) check.block(risk);
    }

    private List<String> sourceRisks(OfficialEvent event, List<OfficialEventBinding> bindings) {
        List<String> risks = new ArrayList<>();
        for (OfficialEventBinding binding : bindings) {
            if (binding == null) {
                risks.add("存在空绑定");
                continue;
            }
            if (!bindingSourceModeMatchesBindingType(binding)) {
                risks.add("绑定 " + binding.getBindingCode() + " 的来源模式与绑定类型不兼容");
                continue;
            }
            if (!OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode())) {
                if (!OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode()) || !hasEventOwnedCoordinates(binding)) {
                    risks.add("绑定 " + binding.getBindingCode() + " 的活动自有点坐标或半径不合法");
                }
                continue;
            }
            if (binding.getSourceRefId() == null) {
                risks.add("绑定 " + binding.getBindingCode() + " 缺少来源对象");
                continue;
            }
            if (StringUtils.isNotEmpty(binding.getCity()) && StringUtils.isNotEmpty(event.getCity())
                    && !binding.getCity().equals(event.getCity())) {
                risks.add("绑定 " + binding.getBindingCode() + " 与活动城市不一致");
            }
            if (OfficialEventBinding.TYPE_ROAM_POI.equals(binding.getBindingType())) {
                List<RoamPoi> pois = roamMapper.selectPoisByIds(java.util.Collections.singletonList(binding.getSourceRefId()));
                if (pois == null || pois.isEmpty()) risks.add("引用 POI 不可用：" + binding.getSourceRefId());
            } else if (OfficialEventBinding.TYPE_THEME.equals(binding.getBindingType())) {
                CmsTopic topic = cmsTopicMapper == null ? null : cmsTopicMapper.selectCmsTopicById(binding.getSourceRefId());
                if (topic == null || topic.getStatus() == null || topic.getStatus() != 1
                        || topic.getUserStatus() == null || topic.getUserStatus() != 1
                        || (topic.getDelFlag() != null && topic.getDelFlag() != 0)) {
                    risks.add("引用主题不可用：" + binding.getSourceRefId());
                }
            }
        }
        return risks;
    }

    private void validatePartyAvailability(List<OfficialEventParty> parties, OfficialEventV2PublishCheck check) {
        for (OfficialEventParty party : parties) {
            if (partyAvailable(party)) continue;
            if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) check.block("官方主办账号不可用：" + party.getPartyId());
            else if (OfficialEventParty.TYPE_MERCHANT.equals(party.getPartyType())) check.block("商家承接主体不可用：" + party.getPartyId());
            else if (OfficialEventParty.TYPE_CLUB.equals(party.getPartyType())) check.block("俱乐部承接主体不可用：" + party.getPartyId());
            else check.block("承接主体类型不合法：" + party.getPartyType());
        }
    }

    private void validateCoverage(OfficialEvent event, List<OfficialEventFulfillmentRequirement> requirements,
                                  List<OfficialEventParty> parties, OfficialEventV2PublishCheck check) {
        String policy = defaultPolicy(event.getFulfillmentPolicy());
        if (POLICY_SELF_RUN.equals(policy) && !requirements.isEmpty()) {
            check.block("SELF_RUN 不能配置外部履约需求");
            return;
        }
        for (OfficialEventFulfillmentRequirement requirement : requirements) {
            if (requirement.getCritical() == null || requirement.getCritical() != 1) continue;
            if (coverageSatisfied(requirement, parties)) continue;
            String message = "关键履约缺口：" + requirement.getRequiredRole() + " / " + requirement.getTargetType() + "#" + requirement.getTargetId();
            if (POLICY_REQUIRED_FULFILLMENT.equals(policy)) check.block(message);
            else if (POLICY_OPTIONAL_PARTNERS.equals(policy)) check.risk(message);
        }
        if (!POLICY_SELF_RUN.equals(policy) && !POLICY_OPTIONAL_PARTNERS.equals(policy) && !POLICY_REQUIRED_FULFILLMENT.equals(policy)) {
            check.block("未知承接策略");
        }
    }

    private boolean coverageSatisfied(OfficialEventFulfillmentRequirement requirement, List<OfficialEventParty> parties) {
        // P0 覆盖必须是完整的单方时段覆盖；空时段绝不能被当作“全天可用”。
        if (requirement.getWindowStart() == null || requirement.getWindowEnd() == null || parties == null) return false;
        for (OfficialEventParty party : parties) {
            if (!OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus()) && !OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus())) continue;
            if (!equalsText(requirement.getRequiredRole(), party.getRole())) continue;
            if (!partyTypeMatchesRole(party.getPartyType(), party.getRole())) continue;
            if (!partyAvailable(party)) continue;
            if (!equalsText(requirement.getTargetType(), party.getTargetType()) || !equalsLong(requirement.getTargetId(), party.getTargetId())) continue;
            if ((party.getCapacity() == null ? 0 : party.getCapacity()) < (requirement.getRequiredCapacity() == null ? 0 : requirement.getRequiredCapacity())) continue;
            if (party.getWindowStart() == null || party.getWindowEnd() == null) continue;
            if (party.getWindowStart().after(requirement.getWindowStart())) continue;
            if (party.getWindowEnd().before(requirement.getWindowEnd())) continue;
            return true;
        }
        return false;
    }

    private boolean isCompatible(String missionType, String bindingType) {
        if (OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(missionType)) return OfficialEventBinding.TYPE_ROAM_POI.equals(bindingType);
        if (OfficialEventMission.TYPE_EVENT_POINT_ARRIVAL.equals(missionType)) return OfficialEventBinding.TYPE_EVENT_OWNED_POINT.equals(bindingType);
        if (OfficialEventMission.TYPE_THEME_VERIFIED_FINISH.equals(missionType)) return OfficialEventBinding.TYPE_THEME.equals(bindingType);
        if (OfficialEventMission.TYPE_ROAM_SESSION_AREA.equals(missionType)) return OfficialEventBinding.TYPE_ROAM_AREA.equals(bindingType);
        return false;
    }

    private boolean bindingSupportsMission(OfficialEventMission mission, OfficialEventBinding binding) {
        if (mission == null || binding == null || !isCompatible(mission.getMissionType(), binding.getBindingType())
                || !bindingSourceModeMatchesBindingType(binding)) return false;
        if (OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode())) return binding.getSourceRefId() != null;
        return OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode()) && hasEventOwnedCoordinates(binding);
    }

    private boolean bindingSourceModeMatchesBindingType(OfficialEventBinding binding) {
        if (binding == null) return false;
        if (OfficialEventBinding.TYPE_ROAM_POI.equals(binding.getBindingType())
                || OfficialEventBinding.TYPE_THEME.equals(binding.getBindingType())) {
            return OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode());
        }
        if (OfficialEventBinding.TYPE_EVENT_OWNED_POINT.equals(binding.getBindingType())) {
            return OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode());
        }
        return OfficialEventBinding.SOURCE_REFERENCE.equals(binding.getSourceMode())
                || OfficialEventBinding.SOURCE_EVENT_OWNED.equals(binding.getSourceMode());
    }

    private boolean hasEventOwnedCoordinates(OfficialEventBinding binding) {
        return binding != null && binding.getLatitude() != null && binding.getLongitude() != null
                && binding.getRadiusM() != null && binding.getRadiusM() > 0
                && validCoordinate(binding.getLatitude().doubleValue(), binding.getLongitude().doubleValue());
    }

    private boolean evidenceWindowOpen(OfficialEvent event, OfficialEventMission mission, Date occurredAt) {
        if (event == null || mission == null || occurredAt == null || isPaused(event)
                || event.getStatus() == null || event.getStatus() != OfficialEvent.ST_LIVE
                || event.getActivityStart() == null || event.getActivityEnd() == null
                || !OfficialEventMission.STATUS_ACTIVE.equals(mission.getStatus())) return false;
        if (occurredAt.before(event.getActivityStart()) || !occurredAt.before(event.getActivityEnd())) return false;
        if (mission.getAvailableStart() != null && occurredAt.before(mission.getAvailableStart())) return false;
        return mission.getAvailableEnd() == null || occurredAt.before(mission.getAvailableEnd());
    }

    /**
     * 任务是否完成 = 有一条有效 evidence，<b>仅此一条判据</b>。
     *
     * <p>这里曾经还递归求值 {@code prerequisite_json}：前置任务必须先完成、且 evidence
     * 时间必须严格早于本任务。那是「顺序与前置」能力，被裁决表 §1.5 的漫游免费层红线整组作废
     * （免费层里 POI 的可见/发现/完成不得取决于另一个 POI 或任务），而它一直是通电的 ——
     * 只要后台存量有值，免费层就会真的出现任务先后关系，且玩家看到的只是「PREREQUISITE_NOT_COMPLETE」。
     * 运行期求值、后台输入、发布期校验三处已一并移除；本文件现由
     * {@code chengyinhub-xcx/scripts/roam-redline-gate.js} 的 R3 持续拦住（重新引入即红）。
     */
    private boolean missionComplete(OfficialEventMission mission, Map<Long, Date> evidenceTimes) {
        return mission != null
                && !OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())
                && evidenceTimes.get(mission.getId()) != null;
    }

    private boolean allMissionsComplete(Long eventId, Long memberId, List<OfficialEventMission> missions) {
        if (missions == null || missions.isEmpty()) return false;
        Map<Long, Date> evidenceTimes = evidenceTimesByMission(eventId, memberId);
        for (OfficialEventMission mission : missions) {
            if (OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())) return false;
            if (!OfficialEventMission.STATUS_ACTIVE.equals(mission.getStatus())) continue;
            if (!missionComplete(mission, evidenceTimes)) return false;
        }
        return true;
    }

    private Map<Long, Date> evidenceTimesByMission(Long eventId, Long memberId) {
        Map<Long, Date> result = new HashMap<>();
        List<OfficialEventMissionEvidence> evidenceRows = v2Mapper.selectEvidenceByEventAndMember(eventId, memberId);
        if (evidenceRows == null) return result;
        for (OfficialEventMissionEvidence evidence : evidenceRows) {
            if (evidence == null || !OfficialEventMissionEvidence.STATUS_VALID.equals(evidence.getStatus())
                    || evidence.getMissionId() == null || evidence.getOccurredAt() == null) continue;
            Date existing = result.get(evidence.getMissionId());
            if (existing == null || evidence.getOccurredAt().after(existing)) {
                result.put(evidence.getMissionId(), evidence.getOccurredAt());
            }
        }
        return result;
    }

    private int completedParticipantCount(Long eventId, List<OfficialEventMission> missions) {
        List<Long> memberIds = v2Mapper.selectActiveParticipationMemberIds(eventId);
        if (memberIds == null) return 0;
        int completed = 0;
        for (Long memberId : memberIds) {
            if (memberId != null && allMissionsComplete(eventId, memberId, missions)) completed++;
        }
        return completed;
    }

    private int completedParticipantCount(Long eventId) {
        List<OfficialEventMission> missions = v2Mapper.selectMissionsByEvent(eventId);
        return completedParticipantCount(eventId, missions);
    }

    private int completedMissionCount(Long eventId, Long memberId, List<OfficialEventMission> missions) {
        if (missions == null) return 0;
        Map<Long, Date> evidenceTimes = evidenceTimesByMission(eventId, memberId);
        int completed = 0;
        for (OfficialEventMission mission : missions) {
            if (OfficialEventMission.STATUS_ACTIVE.equals(mission.getStatus())
                    && missionComplete(mission, evidenceTimes)) completed++;
        }
        return completed;
    }

    private boolean isArrivalMission(OfficialEventMission mission) {
        return OfficialEventMission.TYPE_ROAM_POI_ARRIVAL.equals(mission.getMissionType())
                || OfficialEventMission.TYPE_EVENT_POINT_ARRIVAL.equals(mission.getMissionType());
    }

    private boolean isEvidenceMission(OfficialEventMission mission) {
        return isArrivalMission(mission) || OfficialEventMission.TYPE_THEME_VERIFIED_FINISH.equals(mission.getMissionType());
    }

    private boolean organizerStatusEffective(OfficialEvent event, OfficialEventParty party) {
        if (event == null || party == null) return false;
        if (!OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                || !Integer.valueOf(1).equals(party.getOrganizerAuthority())) return false;
        boolean running = isRunning(event);
        return running ? OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus())
                : (OfficialEventParty.STATUS_ACCEPTED.equals(party.getStatus()) || OfficialEventParty.STATUS_ACTIVE.equals(party.getStatus()));
    }

    private boolean isRunning(OfficialEvent event) {
        return event != null && event.getStatus() != null && event.getStatus() >= OfficialEvent.ST_LIVE
                && event.getStatus() < OfficialEvent.ST_SETTLING;
    }

    private OfficialEventParty partyById(List<OfficialEventParty> parties, Long partyId) {
        if (parties == null || partyId == null) return null;
        for (OfficialEventParty party : parties) {
            if (partyId.equals(party.getId())) return party;
        }
        return null;
    }

    private OfficialEventParty effectiveOrganizer(OfficialEvent event, List<OfficialEventParty> parties) {
        OfficialEventParty effective = null;
        if (parties == null) return null;
        for (OfficialEventParty party : parties) {
            if (!OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                    || !usesOfficialMemberIdentity(party) || !organizerStatusEffective(event, party)
                    || !isEffectiveOfficialMember(party.getPartyId())) continue;
            if (effective != null) return null;
            effective = party;
        }
        return effective;
    }

    private int effectiveOrganizerCount(OfficialEvent event, List<OfficialEventParty> parties) {
        int count = 0;
        if (parties == null) return 0;
        for (OfficialEventParty party : parties) {
            if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType()) && usesOfficialMemberIdentity(party)
                    && organizerStatusEffective(event, party) && isEffectiveOfficialMember(party.getPartyId())) {
                count++;
            }
        }
        return count;
    }

    private boolean isEffectiveOrganizerOperator(OfficialEvent event, List<OfficialEventParty> parties, Long operatorId) {
        if (operatorId == null || operatorId <= 0) return false;
        OfficialEventParty organizer = effectiveOrganizer(event, parties);
        return organizer != null && operatorId.equals(organizer.getPartyId());
    }

    /** Attendance writes require the existing sole effective organizer, never a co-organizer. Caller locks event first. */
    public boolean canOperateAttendance(OfficialEvent event, Long operatorId) {
        return isEffectiveOrganizerOperator(event, v2Mapper.selectPartiesByEventForUpdate(event.getId()), operatorId);
    }

    private String requireEffectiveOrganizerOperator(OfficialEvent event, Long operatorId) {
        if (operatorId == null || operatorId <= 0) return "未识别主办方操作账号";
        List<OfficialEventParty> parties = v2Mapper.selectPartiesByEventForUpdate(event.getId());
        if (parties != null) {
            for (OfficialEventParty party : parties) {
                if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                        && OfficialEventParty.ROLE_ORGANIZER.equals(party.getRole())
                        && operatorId.equals(party.getPartyId())
                        && organizerStatusEffective(event, party)
                        && usesOfficialMemberIdentity(party)
                        && isEffectiveOfficialMember(operatorId)) {
                    return null;
                }
            }
        }
        return "仅本场有效主办方账号可执行该命令";
    }

    private List<Long> uniqueSortedEventIds(List<Long> eventIds) {
        Set<Long> unique = new HashSet<Long>();
        if (eventIds != null) {
            for (Long eventId : eventIds) {
                if (eventId != null && eventId > 0) unique.add(eventId);
            }
        }
        List<Long> ordered = new ArrayList<Long>(unique);
        Collections.sort(ordered);
        return ordered;
    }

    private boolean partyTypeMatchesRole(String partyType, String role) {
        if (OfficialEventParty.ROLE_FULFILLMENT_MERCHANT.equals(role)) return OfficialEventParty.TYPE_MERCHANT.equals(partyType);
        if (OfficialEventParty.ROLE_FULFILLMENT_CLUB.equals(role)) return OfficialEventParty.TYPE_CLUB.equals(partyType);
        if (OfficialEventParty.ROLE_ORGANIZER.equals(role)) return OfficialEventParty.TYPE_OFFICIAL.equals(partyType);
        return true;
    }

    private boolean partyAvailable(OfficialEventParty party) {
        if (party == null || party.getPartyId() == null) return false;
        try {
            if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) {
                return usesOfficialMemberIdentity(party) && isEffectiveOfficialMember(party.getPartyId());
            }
            if (OfficialEventParty.TYPE_MERCHANT.equals(party.getPartyType())) {
                MmsMerchant merchant = merchantMapper.selectMmsMerchantById(party.getPartyId());
                return merchant != null && merchant.getStatus() != null && merchant.getStatus() == 1L
                        && (merchant.getAccountStatus() == null || merchant.getAccountStatus() != 2L)
                        && (merchant.getDelFlag() == null || merchant.getDelFlag() == 0);
            }
            if (OfficialEventParty.TYPE_CLUB.equals(party.getPartyType())) {
                Club club = clubMapper.selectClubById(party.getPartyId());
                return club != null && club.getStatus() != null && club.getStatus() == 1L
                        && (club.getDelFlag() == null || club.getDelFlag() == 0);
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean usesOfficialMemberIdentity(OfficialEventParty party) {
        return party != null && OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())
                && OfficialEventParty.IDENTITY_UMS_MEMBER.equals(party.getIdentitySource());
    }

    /** 后台代理授权只在 sys_user 仍启用且未删除时生效。 */
    private boolean isActiveBackendUser(Long sysUserId) {
        if (sysUserId == null || sysUserId <= 0 || sysUserService == null) return false;
        try {
            SysUser user = sysUserService.selectUserById(sysUserId);
            return user != null && "0".equals(user.getStatus()) && !"2".equals(user.getDelFlag());
        } catch (Exception ex) {
            return false;
        }
    }

    /** OFFICIAL.party_id 的唯一身份域是 ums_member.id，且必须仍在发布白名单中。 */
    private boolean isEffectiveOfficialMember(Long memberId) {
        if (memberId == null || memberId <= 0 || umsMemberService == null || configService == null) return false;
        try {
            UmsMember member = umsMemberService.selectUmsMemberById(memberId);
            if (member == null || !Long.valueOf(1L).equals(member.getStatus())
                    || Integer.valueOf(2).equals(member.getDelFlag())) {
                return false;
            }
            String whitelist = configService.selectConfigByKey("official_publish_whitelist");
            if (StringUtils.isEmpty(whitelist)) return false;
            String expected = String.valueOf(memberId);
            for (String value : whitelist.split(",")) {
                if (expected.equals(value.trim())) return true;
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean partyOwnedBy(OfficialEventParty party, Long memberId) {
        if (memberId == null || memberId <= 0) return false;
        if (OfficialEventParty.TYPE_OFFICIAL.equals(party.getPartyType())) {
            return usesOfficialMemberIdentity(party) && memberId.equals(party.getPartyId());
        }
        if (OfficialEventParty.TYPE_MERCHANT.equals(party.getPartyType())) {
            MmsMerchant merchant = merchantMapper.selectMmsMerchantById(party.getPartyId());
            return merchant != null && memberId.equals(merchant.getMemberId());
        }
        if (OfficialEventParty.TYPE_CLUB.equals(party.getPartyType())) {
            Club club = clubMapper.selectClubById(party.getPartyId());
            return club != null && memberId.equals(club.getMemberId());
        }
        return false;
    }

    private Map<String, Object> persistRejectedArrival(Map<String, Object> result, OfficialEvent event, OfficialEventMission mission,
                                                        OfficialEventBinding binding, Long memberId, String requestId, String riskCode) {
        return persistRejectedArrival(result, event, mission, binding, memberId, requestId, riskCode, null, null);
    }

    private Map<String, Object> persistRejectedArrival(Map<String, Object> result, OfficialEvent event, OfficialEventMission mission,
                                                        OfficialEventBinding binding, Long memberId, String requestId, String riskCode,
                                                        Integer distanceM, String accuracyBucket) {
        OfficialEventArrivalVerification verification = new OfficialEventArrivalVerification();
        verification.setEventId(event.getId());
        verification.setMissionId(mission.getId());
        verification.setBindingId(binding == null ? mission.getBindingId() : binding.getId());
        verification.setMemberId(memberId);
        verification.setVerificationType(OfficialEventMission.TYPE_EVENT_POINT_ARRIVAL.equals(mission.getMissionType())
                ? OfficialEventArrivalVerification.TYPE_EVENT_POINT : OfficialEventArrivalVerification.TYPE_ROAM_POI);
        verification.setRequestId(requestId);
        verification.setDistanceM(distanceM);
        verification.setAccuracyBucket(accuracyBucket);
        verification.setRuleVersion(mission.getRuleVersion());
        verification.setResult(OfficialEventArrivalVerification.RESULT_REJECTED);
        verification.setRiskCode(riskCode);
        verification.setOccurredAt(new Date());
        v2Mapper.insertArrivalVerificationIgnore(verification);
        result.put("accepted", false);
        result.put("completed", false);
        result.put("reason", riskCode);
        result.put("requestId", requestId);
        return result;
    }

    private Map<String, Object> verificationResult(Map<String, Object> result, OfficialEventArrivalVerification verification, boolean completed) {
        result.put("accepted", verification != null && OfficialEventArrivalVerification.RESULT_ACCEPTED.equals(verification.getResult()));
        result.put("completed", completed);
        result.put("reason", verification == null ? "VERIFY_NOT_FOUND" : verification.getRiskCode());
        result.put("distanceM", verification == null ? null : verification.getDistanceM());
        result.put("requestId", verification == null ? null : verification.getRequestId());
        return result;
    }

    private Map<String, Object> rejected(Map<String, Object> result, String message) {
        result.put("accepted", false);
        result.put("completed", false);
        result.put("reason", message);
        return result;
    }

    private boolean movesTooFast(Long memberId, double targetLat, double targetLng) {
        // 两个锚点都是服务端事实：最近一次 POI 发现、最近一次被接受到达（后者不查则两次到达之间可瞬移）。
        return anchorTooFast(roamMapper.selectLastPoiFoundLoc(memberId), targetLat, targetLng)
                || anchorTooFast(v2Mapper.selectLastAcceptedArrivalLoc(memberId), targetLat, targetLng);
    }

    private boolean anchorTooFast(Map<String, Object> anchor, double targetLat, double targetLng) {
        if (anchor == null || !(anchor.get("foundAt") instanceof Date) || !(anchor.get("lat") instanceof Number) || !(anchor.get("lng") instanceof Number)) return false;
        long elapsed = Math.max(1L, (System.currentTimeMillis() - ((Date) anchor.get("foundAt")).getTime()) / 1000L);
        double distance = GeoDistanceUtils.haversineMeters(((Number) anchor.get("lat")).doubleValue(), ((Number) anchor.get("lng")).doubleValue(), targetLat, targetLng);
        return distance > MIN_SPEED_DISTANCE_M && distance / elapsed > MAX_MOVE_SPEED_MPS;
    }

    private int maxAccuracy(OfficialEventMission mission) {
        try {
            JSONObject rule = JSON.parseObject(mission.getRuleJson());
            Integer configured = rule.getInteger("maxAccuracyM");
            return configured != null && configured > 0 ? configured : DEFAULT_MAX_ACCURACY_M;
        } catch (Exception ex) {
            return DEFAULT_MAX_ACCURACY_M;
        }
    }

    /** 发布检查 WARN 分级开关，默认关闭：关闭时发布检查响应与旧版一致。 */
    private boolean publishCheckWarnEnabled() {
        try {
            return configService != null
                    && "true".equalsIgnoreCase(configService.selectConfigByKey("official_event.publish_check_warn_enabled"));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean eventPointArrivalEnabled() {
        try {
            return "true".equalsIgnoreCase(configService.selectConfigByKey("official_event.event_point_arrival_enabled"));
        } catch (Exception ex) {
            return false;
        }
    }

    /** 历史开关仅用于识别旧配置；缺少可信设备位置事实时，verify/publish/settle 均保持关闭。 */
    private boolean roamPoiArrivalEnabled() {
        try {
            return "true".equalsIgnoreCase(configService.selectConfigByKey("official_event.roam_poi_arrival_enabled"));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isPaused(OfficialEvent event) {
        return CONTROL_PAUSED.equals(event.getControlState());
    }

    private int statusByTime(OfficialEvent event, Date now) {
        if (event.getSettleTime() != null && !now.before(event.getSettleTime())) return OfficialEvent.ST_ENDED;
        if (event.getActivityEnd() != null && !now.before(event.getActivityEnd())) return OfficialEvent.ST_SETTLING;
        if (event.getActivityStart() != null && !now.before(event.getActivityStart())) return OfficialEvent.ST_LIVE;
        if (event.getSignupStart() != null && !now.before(event.getSignupStart())) return OfficialEvent.ST_SIGNUP;
        return OfficialEvent.ST_SCHEDULED;
    }

    private Map<String, Object> eventBase(OfficialEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", event.getId());
        data.put("title", event.getTitle());
        data.put("subtitle", event.getSubtitle());
        data.put("category", event.getCategory());
        data.put("coverImg", event.getCoverImg());
        data.put("story", event.getStory());
        data.put("city", event.getCity());
        data.put("status", event.getStatus());
        data.put("contractVersion", event.getContractVersion());
        data.put("fulfillmentPolicy", event.getFulfillmentPolicy());
        data.put("controlState", event.getControlState());
        data.put("pausedReason", event.getPausedReason());
        data.put("pausedAt", event.getPausedAt());
        data.put("resumeNote", event.getResumeNote());
        data.put("signupStart", event.getSignupStart());
        data.put("activityStart", event.getActivityStart());
        data.put("activityEnd", event.getActivityEnd());
        data.put("settleTime", event.getSettleTime());
        data.put("rewardJson", event.getRewardJson());
        data.put("bannerEnabled", event.getBannerEnabled());
        data.put("collective", collectiveProgress(event));
        return data;
    }

    private List<Map<String, Object>> buildRoamOverlay(List<OfficialEventBinding> bindings, List<OfficialEventMission> missions) {
        Map<Long, List<String>> missionCodesByBinding = new HashMap<>();
        for (OfficialEventMission mission : missions) {
            List<String> codes = missionCodesByBinding.get(mission.getBindingId());
            if (codes == null) {
                codes = new ArrayList<>();
                missionCodesByBinding.put(mission.getBindingId(), codes);
            }
            codes.add(mission.getMissionCode());
        }
        List<Map<String, Object>> points = new ArrayList<>();
        for (OfficialEventBinding binding : bindings) {
            if (!missionCodesByBinding.containsKey(binding.getId())) continue;
            Double latitude = binding.getLatitude() == null ? null : binding.getLatitude().doubleValue();
            Double longitude = binding.getLongitude() == null ? null : binding.getLongitude().doubleValue();
            Integer radius = binding.getRadiusM();
            String name = binding.getBindingCode();
            if (OfficialEventBinding.TYPE_ROAM_POI.equals(binding.getBindingType()) && binding.getSourceRefId() != null) {
                List<RoamPoi> pois = roamMapper.selectPoisByIds(java.util.Collections.singletonList(binding.getSourceRefId()));
                if (pois != null && !pois.isEmpty()) {
                    RoamPoi poi = pois.get(0);
                    latitude = poi.getLat() == null ? null : poi.getLat().doubleValue();
                    longitude = poi.getLng() == null ? null : poi.getLng().doubleValue();
                    radius = poi.getRadiusM();
                    name = StringUtils.isEmpty(poi.getName()) ? name : poi.getName();
                }
            }
            if (latitude == null || longitude == null) continue;
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("bindingCode", binding.getBindingCode());
            point.put("bindingType", binding.getBindingType());
            point.put("name", name);
            point.put("latitude", latitude);
            point.put("longitude", longitude);
            point.put("radiusM", radius == null || radius <= 0 ? 120 : radius);
            point.put("missionCodes", missionCodesByBinding.get(binding.getId()));
            points.add(point);
        }
        return points;
    }

    private OfficialEventRewardIssuance newIssuance(OfficialEvent event, Long memberId, String ruleVersion, String status) {
        OfficialEventRewardIssuance issuance = new OfficialEventRewardIssuance();
        issuance.setEventId(event.getId());
        issuance.setMemberId(memberId);
        issuance.setRewardRuleVersion(ruleVersion);
        issuance.setStatus(status);
        issuance.setIssuanceKey(event.getId() + ":" + memberId + ":" + ruleVersion);
        issuance.setPayloadJson(event.getRewardJson());
        issuance.setSourceSnapshotJson(JSON.toJSONString(eventBase(event)));
        issuance.setFailureReason(OfficialEventRewardIssuance.NOT_ELIGIBLE.equals(status) ? "必做任务未完成" : null);
        return issuance;
    }

    private boolean collectiveReached(OfficialEvent event) {
        if (event.getCollectiveEnabled() == null || event.getCollectiveEnabled() == 0) return false;
        int threshold = event.getCollectiveThreshold() == null ? 0 : event.getCollectiveThreshold();
        if (threshold <= 0) return false;
        String metric = StringUtils.isEmpty(event.getCollectiveMetric()) ? "complete_count" : event.getCollectiveMetric();
        int current;
        if ("signup_count".equals(metric)) current = v2Mapper.countActiveParticipations(event.getId());
        else if ("light_count".equals(metric)) current = v2Mapper.countValidEvidenceByEvent(event.getId());
        else current = completedParticipantCount(event.getId());
        return current >= threshold;
    }

    private Map<String, Object> collectiveProgress(OfficialEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (event.getCollectiveEnabled() == null || event.getCollectiveEnabled() == 0) {
            result.put("enabled", false);
            return result;
        }
        String metric = StringUtils.isEmpty(event.getCollectiveMetric()) ? "complete_count" : event.getCollectiveMetric();
        int current;
        if ("signup_count".equals(metric)) current = v2Mapper.countActiveParticipations(event.getId());
        else if ("light_count".equals(metric)) current = v2Mapper.countValidEvidenceByEvent(event.getId());
        else current = completedParticipantCount(event.getId());
        int threshold = event.getCollectiveThreshold() == null ? 0 : event.getCollectiveThreshold();
        result.put("enabled", true);
        result.put("metric", metric);
        result.put("current", current);
        result.put("threshold", threshold);
        result.put("pct", threshold > 0 ? (int) Math.min(100L, (long) current * 100L / threshold) : 0);
        result.put("reached", threshold > 0 && current >= threshold);
        return result;
    }

    private JSONObject parseObject(String json) {
        if (StringUtils.isEmpty(json)) return null;
        try { return JSON.parseObject(json); } catch (Exception ex) { return null; }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return StringUtils.isEmpty(message) ? exception.getClass().getSimpleName() : message.substring(0, Math.min(480, message.length()));
    }

    private Map<String, Object> arrivalSnapshot(OfficialEventBinding binding, Integer distanceM, String accuracyBucket) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bindingId", binding.getId());
        snapshot.put("bindingCode", binding.getBindingCode());
        snapshot.put("distanceM", distanceM);
        snapshot.put("accuracyBucket", accuracyBucket);
        return snapshot;
    }

    private Map<String, Object> topicSnapshot(Long topicId, String sourceFactId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("topicId", topicId);
        snapshot.put("sourceFactId", sourceFactId);
        return snapshot;
    }

    private boolean validCoordinate(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && !Double.isInfinite(lat) && !Double.isInfinite(lng)
                && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180;
    }

    private String accuracyBucket(Double accuracyM) {
        if (accuracyM == null) return "UNKNOWN";
        if (accuracyM <= 20) return "LE_20";
        if (accuracyM <= 50) return "LE_50";
        if (accuracyM <= 80) return "LE_80";
        return "GT_80";
    }

    private boolean validJsonObject(String value) {
        try { return JSON.parseObject(value) != null; } catch (Exception ex) { return false; }
    }

    private boolean equalsText(String a, String b) { return a == null ? b == null : a.equals(b); }
    private boolean equalsLong(Long a, Long b) { return a == null ? b == null : a.equals(b); }
    private Long asLong(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private String join(List<String> values) { return String.join("；", values); }
}
