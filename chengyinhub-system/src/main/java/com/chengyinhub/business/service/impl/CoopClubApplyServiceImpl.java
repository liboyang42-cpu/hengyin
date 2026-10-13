package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CoopClubApply;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CoopClubApplyMapper;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.service.ICoopClubApplyService;
import com.chengyinhub.business.service.support.ClubCoopWindowPolicy;
import com.chengyinhub.common.utils.DateUtils;

/**
 * 俱乐部承接申请 Service(P0-4 开放范围)。见接口注释:申请只表意向,不生成合作单。
 */
@Service
public class CoopClubApplyServiceImpl implements ICoopClubApplyService {

    @Autowired
    private CoopClubApplyMapper coopClubApplyMapper;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private CoopInviteMapper coopInviteMapper;

    @Autowired
    private ClubCoopWindowPolicy clubCoopWindowPolicy;

    @Override
    public CoopClubApply selectById(Long applyId) {
        return coopClubApplyMapper.selectCoopClubApplyById(applyId);
    }

    @Override
    public CoopClubApply apply(Long topicId, Long memberId, Long clubId, String message) {
        Club club = resolveMyActiveClub(memberId, clubId);

        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) throw new IllegalStateException("主题不存在");
        // 归属不明(存量脏数据 member_id 为空)= 拒:判不出归属就别放行(与 invite 三类统一的闸同理)
        if (topic.getMemberId() == null) throw new IllegalStateException("主题归属不明,暂不能申请承接");
        if (topic.getMemberId().equals(memberId)) throw new IllegalStateException("不能承接自己发布的主题");
        // §9.2/9.3 开放报名只覆盖玩家/商家发布的主题;俱乐部主题三态按对手方=商家派生,放行=哑巴状态
        if (topic.getClubId() != null) throw new IllegalStateException("俱乐部发布的主题不开放俱乐部承接");
        if (topic.getOpenClubPool() == null || topic.getOpenClubPool() != 1) {
            throw new IllegalStateException("该主题未开放俱乐部承接");
        }
        if (topic.getStatus() == null || topic.getStatus() != 1
                || topic.getUserStatus() == null || topic.getUserStatus() != 1) {
            throw new IllegalStateException("主题未上架或未过审,暂不能申请承接");
        }
        // 与 acceptInviteWithLock 的冻结阵容闸同向:锁价后不再纳新合作方
        if (com.chengyinhub.business.domain.enums.TopicLifecycleState
                .commercialTermsFrozen(topic.getLifecycle())) {
            throw new IllegalStateException("主题已锁价,不能再申请承接");
        }
        // F04 与商家邀约同一招商期口径:申请得了就一定能被邀约接续
        clubCoopWindowPolicy.assertOpen(topic, null, DateUtils.getNowDate());
        // 开放报名路径按「单俱乐部承接」收口:已有已接受的俱乐部合作即关门(商家邀约路径的容量闸不在此)
        if (hasAcceptedClubCoop(topicId)) {
            throw new IllegalStateException("该主题已有俱乐部承接");
        }

        CoopClubApply existing = coopClubApplyMapper.selectByTopicAndClub(topicId, club.getId());
        if (existing != null) {
            if (existing.getStatus() != null && existing.getStatus() == 0) {
                throw new IllegalStateException("已申请,等待商家处理");
            }
            // 婉拒/撤回后重新申请:同行复位(status=0、换留言、清 handle_time)
            coopClubApplyMapper.reapplyCoopClubApply(existing.getId(), message);
            existing.setStatus(0);
            existing.setMessage(message);
            existing.setHandleTime(null);
            return existing;
        }

        CoopClubApply row = new CoopClubApply();
        row.setTopicId(topicId);
        row.setClubId(club.getId());
        row.setMemberId(memberId);
        row.setStatus(0);
        row.setMessage(message);
        row.setDelFlag(0);
        row.setCreateTime(DateUtils.getNowDate());
        coopClubApplyMapper.insertCoopClubApply(row);
        return row;
    }

    @Override
    public CoopClubApply withdraw(Long topicId, Long memberId) {
        Club club = resolveMyActiveClub(memberId, null);
        CoopClubApply existing = coopClubApplyMapper.selectByTopicAndClub(topicId, club.getId());
        if (existing == null || existing.getStatus() == null || existing.getStatus() != 0) {
            throw new IllegalStateException("没有待处理的申请可撤回");
        }
        CoopClubApply upd = new CoopClubApply();
        upd.setId(existing.getId());
        upd.setStatus(2);
        upd.setHandleTime(DateUtils.getNowDate());
        coopClubApplyMapper.updateCoopClubApply(upd);
        return upd;
    }

    @Override
    public CoopClubApply decline(Long applyId, Long memberId) {
        CoopClubApply apply = coopClubApplyMapper.selectCoopClubApplyById(applyId);
        if (apply == null) throw new IllegalStateException("申请不存在");
        CmsTopic topic = apply.getTopicId() == null ? null : cmsTopicMapper.selectCmsTopicById(apply.getTopicId());
        if (topic == null || topic.getMemberId() == null || !topic.getMemberId().equals(memberId)) {
            throw new IllegalStateException("仅主题发布者可处理承接申请");
        }
        if (apply.getStatus() == null || apply.getStatus() != 0) {
            throw new IllegalStateException("该申请已处理");
        }
        CoopClubApply upd = new CoopClubApply();
        upd.setId(apply.getId());
        upd.setStatus(1);
        upd.setHandleTime(DateUtils.getNowDate());
        coopClubApplyMapper.updateCoopClubApply(upd);
        return upd;
    }

    @Override
    public int markConverted(Long applyId) {
        if (applyId == null) return 0;
        CoopClubApply apply = coopClubApplyMapper.selectCoopClubApplyById(applyId);
        // 只从「待处理」翻:已婉拒/已撤回的不能被一张邀约翻回来
        if (apply == null || apply.getStatus() == null
                || apply.getStatus() != CoopClubApply.STATUS_PENDING) {
            return 0;
        }
        CoopClubApply upd = new CoopClubApply();
        upd.setId(apply.getId());
        upd.setStatus(CoopClubApply.STATUS_CONVERTED);
        upd.setHandleTime(DateUtils.getNowDate());
        return coopClubApplyMapper.updateCoopClubApply(upd);
    }

    @Override
    public List<CoopClubApply> selectByTopic(Long topicId) {
        CoopClubApply q = new CoopClubApply();
        q.setTopicId(topicId);
        return coopClubApplyMapper.selectCoopClubApplyList(q);
    }

    @Override
    public List<CoopClubApply> selectByClubIds(List<Long> clubIds) {
        if (clubIds == null || clubIds.isEmpty()) return new ArrayList<>();
        return coopClubApplyMapper.selectByClubIds(clubIds);
    }

    @Override
    public List<CoopClubApply> selectByTopicOwner(Long memberId) {
        if (memberId == null) return new ArrayList<>();
        return coopClubApplyMapper.selectByTopicOwner(memberId);
    }

    @Override
    public List<CoopClubApply> selectByApplicant(Long memberId) {
        if (memberId == null) return new ArrayList<>();
        CoopClubApply q = new CoopClubApply();
        q.setMemberId(memberId);
        return coopClubApplyMapper.selectCoopClubApplyList(q);
    }

    /** 申请人生效俱乐部:clubId 指定则须归属本人且生效;未指定取第一个生效俱乐部 */
    private Club resolveMyActiveClub(Long memberId, Long clubId) {
        List<Club> mine = clubMapper.selectClubsByMemberId(memberId);
        if (mine != null) {
            for (Club c : mine) {
                if (c == null || c.getStatus() == null || c.getStatus() != 1L) continue;
                if (clubId == null || clubId.equals(c.getId())) return c;
            }
        }
        throw new IllegalStateException("仅生效俱乐部的主理人可申请承接,请先创建并通过俱乐部审核");
    }

    /** 该主题是否已有已接受(status=1)的俱乐部合作 —— 邀约/申请两条路殊途同归的「已承接」判据 */
    private boolean hasAcceptedClubCoop(Long topicId) {
        CoopInvite q = new CoopInvite();
        q.setTopicId(topicId);
        q.setStatus(1);
        List<CoopInvite> accepted = coopInviteMapper.selectCoopInviteList(q);
        if (accepted == null) return false;
        for (CoopInvite i : accepted) {
            if ("club".equals(i.getToType())) return true;
        }
        return false;
    }
}
