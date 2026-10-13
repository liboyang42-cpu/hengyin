package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CircleExploreAnswer;
import com.chengyinhub.business.domain.CircleExploreRecord;
import com.chengyinhub.business.domain.CircleExploreSession;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.CircleExploreCardVO;
import com.chengyinhub.business.mapper.CircleExploreMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.ICircleExploreService;
import com.chengyinhub.business.service.ICircleThemeSupplyService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.support.CircleInteractionPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.UUID;
import java.util.List;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** 跨天、任意顺序的玩家自助记录；不写任何商家履约事实。 */
@Service
public class CircleExploreServiceImpl implements ICircleExploreService {
    @Autowired private CircleExploreMapper exploreMapper;
    @Autowired private CmsTopicMapper topicMapper;
    @Autowired private ICircleThemeSupplyService supplyService;
    @Autowired private IUmsMemberService memberService;
    @Autowired private ICmsRegistrationService registrationService;

    @Override
    public void assertEligible(Long topicId, Long memberId) {
        requireLogin(memberId);
        assertValidThemePass(topicId, memberId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CircleExploreSession create(Long topicId, Long memberId) {
        assertEligible(topicId, memberId);
        List<MerchantCircleSupplyProfile> supplies = topicId == null
                ? Collections.emptyList() : supplyService.listPublicSupplies(topicId);
        if (supplies.isEmpty()) {
            throw new IllegalStateException("该城市实例当前不是3—4家有效商户，暂不开放");
        }
        assertNightEligible(topicMapper.selectCmsTopicById(topicId), memberId, supplies);
        CircleExploreSession session = new CircleExploreSession();
        session.setTopicId(topicId);
        session.setOwnerMemberId(memberId);
        session.setInviteCode(UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase());
        session.setStatus("ACTIVE");
        if (exploreMapper.insertSession(session) != 1 || session.getId() == null) {
            throw new IllegalStateException("创建探索会话失败");
        }
        exploreMapper.insertMember(session.getId(), memberId);
        return session;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CircleExploreSession join(String inviteCode, Long memberId) {
        requireLogin(memberId);
        if (blank(inviteCode)) throw new IllegalStateException("缺少同行邀请码");
        CircleExploreSession session = exploreMapper.selectActiveSessionByInvite(
                inviteCode.trim().toUpperCase());
        if (session == null) throw new IllegalStateException("同行邀请码不存在或会话已结束");
        assertValidThemePass(session.getTopicId(), memberId);
        if (exploreMapper.countMember(session.getId(), memberId) == 1) {
            return session;
        }
        List<MerchantCircleSupplyProfile> supplies = supplyService.listPublicSupplies(session.getTopicId());
        if (supplies.isEmpty()) throw new IllegalStateException("该城市实例当前暂不开放");
        CmsTopic topic = topicMapper.selectCmsTopicById(session.getTopicId());
        if (topic != null && hasDepartureChoice(topic.getCircleThemeCode())
                && exploreMapper.countDistinctRecordedMerchants(session.getId()) > 0) {
            throw new IllegalStateException("同行探索已开始记录，不能再加入新成员");
        }
        assertJoinCapacity(session, topic, supplies);
        assertNightEligible(topic, memberId, supplies);
        exploreMapper.insertMember(session.getId(), memberId);
        return session;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void record(Long sessionId, Long offerId, String note, Long memberId) {
        CircleExploreSession session = activeMemberSession(sessionId, memberId);
        List<MerchantCircleSupplyProfile> publicSupplies = requireOpenSupplies(session.getTopicId());
        MerchantCircleSupplyProfile supply = null;
        for (MerchantCircleSupplyProfile candidate : publicSupplies) {
            if (candidate != null && offerId != null && offerId.equals(candidate.getOfferId())) {
                supply = candidate;
                break;
            }
        }
        if (supply == null) {
            throw new IllegalStateException("该商家供给当前不可用于本次探索");
        }
        if (supply.getId() == null || !Integer.valueOf(1).equals(supply.getCurrent())) {
            throw new IllegalStateException("该商家供给声明当前不可用");
        }
        CmsTopic topic = topicMapper.selectCmsTopicById(session.getTopicId());
        assertDepartureReady(session, topic);
        assertNightEligible(topic, memberId, Collections.singletonList(supply));
        String cleanNote = note == null ? null : note.trim();
        if (cleanNote != null && cleanNote.length() > 500) {
            throw new IllegalStateException("记录不能超过500字");
        }
        CircleExploreRecord record = new CircleExploreRecord();
        record.setSessionId(sessionId);
        record.setMemberId(memberId);
        record.setOfferId(offerId);
        record.setSupplyProfileId(supply.getId());
        record.setNote(cleanNote);
        try {
            exploreMapper.insertRecord(record);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("你已经记录过这家供给");
        }
        completeIfReady(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void answer(Long sessionId, String stage, String value, Long memberId) {
        CircleExploreSession session = activeMemberSession(sessionId, memberId);
        CmsTopic topic = topicMapper.selectCmsTopicById(session.getTopicId());
        if (topic == null) throw new IllegalStateException("圈层主题不存在");
        List<MerchantCircleSupplyProfile> publicSupplies = requireOpenSupplies(session.getTopicId());
        int recordedMerchantCount = exploreMapper.countDistinctRecordedMerchants(sessionId);
        if ("DATE".equals(topic.getCircleThemeCode()) && "QUESTION_CARD".equals(stage)
                && exploreMapper.countSessionMembers(sessionId) < 2) {
            throw new IllegalStateException("请先邀请另一位约会成员，再共同选择问题卡");
        }
        if (isDepartureStage(stage) && recordedMerchantCount > 0) {
            throw new IllegalStateException("出发前选择已随首次商家记录锁定");
        }
        if (("POST_CHOICE".equals(stage) || "NEXT_PICK".equals(stage))
                && recordedMerchantCount < 2) {
            throw new IllegalStateException("请先自助记录任意2家，再完成结束后的选择");
        }
        Set<String> candidateCodes = candidateCodes(publicSupplies);
        if ("SHANGHAI".equals(topic.getCircleThemeCode())) {
            candidateCodes.retainAll(recordedCandidateCodes(sessionId));
        }
        CircleInteractionPolicy.assertAnswer(topic.getCircleThemeCode(), stage, value, candidateCodes);
        CircleExploreAnswer answer = new CircleExploreAnswer();
        answer.setSessionId(sessionId);
        answer.setMemberId(memberId);
        answer.setAnswerStage(stage);
        answer.setAnswerValue(value.trim());
        exploreMapper.upsertAnswer(answer);
        completeIfReady(session);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CircleExploreCardVO card(Long sessionId, Long memberId) {
        CircleExploreSession session = memberSession(sessionId, memberId);
        CmsTopic topic = topicMapper.selectCmsTopicById(session.getTopicId());
        if (topic == null || blank(topic.getCircleThemeCode())) {
            throw new IllegalStateException("圈层主题不存在");
        }
        int merchantCount = exploreMapper.countDistinctRecordedMerchants(sessionId);
        int memberCount = exploreMapper.countSessionMembers(sessionId);
        boolean interactionComplete = interactionComplete(
                sessionId, topic.getCircleThemeCode(), memberCount);
        boolean completed = "COMPLETED".equals(session.getStatus())
                || (merchantCount >= 2 && interactionComplete
                && CircleInteractionPolicy.hasRequiredMembers(topic.getCircleThemeCode(), memberCount));
        CircleExploreCardVO card = new CircleExploreCardVO();
        card.setSessionId(sessionId);
        card.setTopicId(session.getTopicId());
        card.setThemeCode(topic.getCircleThemeCode());
        card.setStatus(completed ? "COMPLETED" : session.getStatus());
        card.setInviteCode(session.getInviteCode());
        card.setRecordedMerchantCount(merchantCount);
        card.setInteractionComplete(interactionComplete);
        card.setCompleted(completed);
        List<CircleExploreRecord> records = orEmpty(exploreMapper.selectRecords(sessionId));
        for (CircleExploreRecord record : records) {
            if (!memberId.equals(record.getMemberId())) record.setNote(null);
            record.setMemberId(null);
        }
        List<CircleExploreAnswer> answers = new java.util.ArrayList<>();
        java.util.Map<Long, String> memberLabels = new java.util.LinkedHashMap<>();
        int otherMemberIndex = 0;
        for (CircleExploreAnswer answer : orEmpty(exploreMapper.selectAnswers(sessionId))) {
            if (memberId.equals(answer.getMemberId())
                    || CircleInteractionPolicy.isSharedStage(topic.getCircleThemeCode(), answer.getAnswerStage())) {
                Long answerMemberId = answer.getMemberId();
                if (answerMemberId != null) {
                    String label = memberLabels.get(answerMemberId);
                    if (label == null) {
                        if (memberId.equals(answerMemberId)) {
                            label = "我";
                        } else {
                            label = "同行" + (++otherMemberIndex);
                        }
                        memberLabels.put(answerMemberId, label);
                    }
                    answer.setMemberLabel(label);
                }
                answer.setMemberId(null);
                answers.add(answer);
            }
        }
        card.setRecords(records);
        card.setAnswers(answers);
        return card;
    }

    private boolean interactionComplete(Long sessionId, String themeCode, int memberCount) {
        if ("SHANGHAI".equals(themeCode)) {
            Set<String> taggedCandidates = new HashSet<>();
            Set<String> recordedCandidates = recordedCandidateCodes(sessionId);
            for (CircleExploreAnswer answer : orEmpty(exploreMapper.selectAnswers(sessionId))) {
                if ("CITY_KEYWORDS".equals(answer.getAnswerStage())) {
                    taggedCandidates.addAll(CircleInteractionPolicy.shanghaiCandidateCodes(answer.getAnswerValue()));
                }
            }
            return !recordedCandidates.isEmpty() && taggedCandidates.containsAll(recordedCandidates);
        }
        return CircleInteractionPolicy.isComplete(themeCode, memberCount,
                stage -> exploreMapper.countAnswersByStage(sessionId, stage));
    }

    private void completeIfReady(CircleExploreSession session) {
        int merchantCount = exploreMapper.countDistinctRecordedMerchants(session.getId());
        CmsTopic topic = topicMapper.selectCmsTopicById(session.getTopicId());
        int memberCount = exploreMapper.countSessionMembers(session.getId());
        if (topic != null && merchantCount >= 2
                && CircleInteractionPolicy.hasRequiredMembers(topic.getCircleThemeCode(), memberCount)
                && interactionComplete(session.getId(), topic.getCircleThemeCode(), memberCount)) {
            exploreMapper.completeSession(session.getId());
        }
    }

    private Set<String> candidateCodes(List<MerchantCircleSupplyProfile> supplies) {
        Set<String> result = new HashSet<>();
        for (MerchantCircleSupplyProfile supply : orEmpty(supplies)) {
            if (!blank(supply.getCandidateCode())) result.add(supply.getCandidateCode());
        }
        return result;
    }

    private List<MerchantCircleSupplyProfile> requireOpenSupplies(Long topicId) {
        List<MerchantCircleSupplyProfile> supplies = supplyService.listPublicSupplies(topicId);
        if (supplies == null || supplies.size() < 3 || supplies.size() > 4) {
            throw new IllegalStateException("该城市实例当前暂不开放，已有记录仍可查看");
        }
        return supplies;
    }

    private Set<String> recordedCandidateCodes(Long sessionId) {
        Set<String> result = new HashSet<>();
        for (CircleExploreRecord record : orEmpty(exploreMapper.selectRecords(sessionId))) {
            if (!blank(record.getCandidateCode())) result.add(record.getCandidateCode());
        }
        return result;
    }

    private void assertDepartureReady(CircleExploreSession session, CmsTopic topic) {
        String themeCode = topic == null ? null : topic.getCircleThemeCode();
        String stage = "FITNESS".equals(themeCode) ? "PRE_CHOICE"
                : "FRIENDS".equals(themeCode) ? "PRE_WISH" : null;
        if (stage == null) return;
        int memberCount = Math.max(1, exploreMapper.countSessionMembers(session.getId()));
        if ("FRIENDS".equals(themeCode) && memberCount < 2) {
            throw new IllegalStateException("朋友主题请先邀请至少1位同行成员");
        }
        if (exploreMapper.countAnswersByStage(session.getId(), stage) < memberCount) {
            throw new IllegalStateException("请让每位同行先完成出发前选择，再开始记录商家");
        }
    }

    private static boolean hasDepartureChoice(String themeCode) {
        return "FITNESS".equals(themeCode) || "FRIENDS".equals(themeCode);
    }

    private static boolean isDepartureStage(String stage) {
        return "PRE_CHOICE".equals(stage) || "PRE_WISH".equals(stage);
    }

    private void assertValidThemePass(Long topicId, Long memberId) {
        if (topicId == null) throw new IllegalStateException("缺少城市实例");
        CmsRegistration probe = new CmsRegistration();
        probe.setMemberId(memberId);
        probe.setOwnerType(1L);
        probe.setOwnerId(topicId);
        probe.setRegistrationStatus(2);
        Date now = new Date();
        for (CmsRegistration registration : orEmpty(registrationService.selectCmsRegistrationList(probe))) {
            if (registration.getExpiresAt() == null || registration.getExpiresAt().after(now)) return;
        }
        throw new IllegalStateException("请先购买或领取该主题");
    }

    private void assertJoinCapacity(CircleExploreSession session, CmsTopic topic,
                                    List<MerchantCircleSupplyProfile> supplies) {
        int maximum = topic != null && "DATE".equals(topic.getCircleThemeCode()) ? 2 : 5;
        for (MerchantCircleSupplyProfile supply : supplies) {
            if (!Integer.valueOf(1).equals(supply.getSplitBookingAllowed())
                    && supply.getMaxPeople() != null) {
                maximum = Math.min(maximum, supply.getMaxPeople());
            }
        }
        if (exploreMapper.countSessionMembers(session.getId()) >= maximum) {
            throw new IllegalStateException("同行人数已达到当前主题或商家接待上限");
        }
    }

    private CircleExploreSession activeMemberSession(Long sessionId, Long memberId) {
        CircleExploreSession session = memberSession(sessionId, memberId);
        if (!"ACTIVE".equals(session.getStatus())) throw new IllegalStateException("探索会话已结束");
        return session;
    }

    private CircleExploreSession memberSession(Long sessionId, Long memberId) {
        requireLogin(memberId);
        if (sessionId == null) throw new IllegalStateException("缺少探索会话");
        CircleExploreSession session = exploreMapper.selectSessionById(sessionId);
        if (session == null) throw new IllegalStateException("探索会话不存在");
        if (exploreMapper.countMember(sessionId, memberId) != 1) {
            throw new IllegalStateException("你不是本次探索成员");
        }
        return session;
    }

    private void assertNightEligible(CmsTopic topic, Long memberId,
                                     List<MerchantCircleSupplyProfile> supplies) {
        if (topic == null || !"NIGHT".equals(topic.getCircleThemeCode())) return;
        int minimumAge = 18;
        for (MerchantCircleSupplyProfile supply : supplies) {
            if (Integer.valueOf(1).equals(supply.getAlcoholInvolved())) {
                if (supply.getLegalDrinkingAge() == null) {
                    throw new IllegalStateException("酒精供给缺少当地法定饮酒年龄，实例暂不开放");
                }
                minimumAge = Math.max(minimumAge, supply.getLegalDrinkingAge());
            }
        }
        UmsMember member = memberService.selectUmsMemberById(memberId);
        Date birthday = member == null ? null : member.getBirthday();
        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.YEAR, -minimumAge);
        if (birthday == null || birthday.after(cutoff.getTime())) {
            throw new IllegalStateException("夜间主题仅面向已确认达到参与年龄的成员");
        }
    }

    private static void requireLogin(Long memberId) {
        if (memberId == null || memberId <= 0) throw new IllegalStateException("请先登录");
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static <T> java.util.List<T> orEmpty(java.util.List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
