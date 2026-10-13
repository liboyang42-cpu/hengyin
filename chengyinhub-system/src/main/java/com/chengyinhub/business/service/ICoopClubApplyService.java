package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CoopClubApply;

/**
 * 俱乐部承接申请 Service 接口(P0-4 开放范围)。
 *
 * 状态机:0待处理 →(商家婉拒)1 / (俱乐部撤回)2;婉拒/撤回后可重新申请(同行复位 0)。
 * 申请不生成合作单:商家从候选池对申请俱乐部发 type1 带条款邀约,接受才成 coop_order(§3.6)。
 */
public interface ICoopClubApplyService {

    /** 按主键查询申请,供发带条款邀约时校验开放报名溯源。 */
    CoopClubApply selectById(Long applyId);

    /**
     * 俱乐部发起承接申请。
     *
     * @param topicId  开放承接的主题
     * @param memberId 申请人(须为生效俱乐部主理人)
     * @param clubId   以哪个俱乐部申请;null = 取申请人第一个生效俱乐部
     * @param message  留言(可空)
     * @return 申请行(新建或复位)
     * @throws IllegalStateException 闸不过:非主理人/主题不存在或未开放/自己主题/已锁价/已有俱乐部承接/重复申请
     */
    CoopClubApply apply(Long topicId, Long memberId, Long clubId, String message);

    /** 俱乐部撤回待处理申请(仅本人俱乐部、仅 status=0) */
    CoopClubApply withdraw(Long topicId, Long memberId);

    /** 商家(主题发布者)婉拒待处理申请 */
    CoopClubApply decline(Long applyId, Long memberId);

    /** 发布者候选池:某主题全部申请 */
    List<CoopClubApply> selectByTopic(Long topicId);

    /** 俱乐部侧:我的俱乐部发出的申请 */
    List<CoopClubApply> selectByClubIds(List<Long> clubIds);

    /** 发布者侧「收到的」:我发布的全部主题上的申请(含已处理/已锁价的历史) */
    List<CoopClubApply> selectByTopicOwner(Long memberId);

    /** 申请人侧「我发出的」:member_id = 我 的申请(含已处理/已锁价的历史) */
    List<CoopClubApply> selectByApplicant(Long memberId);

    /**
     * 申请被转成邀约时回写状态(0 待处理 → 3 已转为邀约)。
     *
     * <p>不叫 accept:主办方同意**不产生合作单**,条款随那张 type=1 邀约走,
     * 俱乐部还要去确认。叫 accept 会让人以为这里就成单了。
     *
     * <p>CAS 语义:只从 0 翻,已处理的不动 —— 并发下重复调用不会把已婉拒的翻回来。
     */
    int markConverted(Long applyId);
}
