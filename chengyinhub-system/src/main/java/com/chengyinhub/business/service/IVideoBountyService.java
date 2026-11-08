package com.chengyinhub.business.service;

import java.util.List;

import com.chengyinhub.business.domain.VideoBountyAccrual;
import com.chengyinhub.business.domain.VideoBountySubmission;
import com.chengyinhub.business.domain.VideoCampaign;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceRequest;
import com.chengyinhub.business.domain.dto.VideoBountyEvidenceView;
import com.chengyinhub.business.domain.dto.VideoBountyReviewRequest;
import com.chengyinhub.business.domain.dto.VideoBountySubmissionView;
import com.chengyinhub.business.domain.dto.VideoBountySubmitRequest;
import com.chengyinhub.business.domain.dto.VideoCampaignAttributionView;
import com.chengyinhub.business.domain.dto.VideoCampaignRequest;

/**
 * 视频内容悬赏:投稿 → 审核 → 证据 → 应计 → 申诉/撤销。
 *
 * <p><b>本服务不付钱</b>:应计只落"仅记录"状态;现金兑付需独立获批的通道集成。
 * 播放基数只能来自外部平台证据,服务自身从不"查平台"。
 *
 * @author chengyinhub
 */
public interface IVideoBountyService
{
    /**
     * 作者投稿。同一活动内同一规范化视频键唯一;重复投稿抛
     * {@code BountyTipException(VIDEO_ALREADY_SUBMITTED)}。
     */
    VideoBountySubmissionView submit(long memberId, VideoBountySubmitRequest request);

    /** 作者查看自己的投稿。 */
    List<VideoBountySubmissionView> listMine(long memberId, String campaignKey);

    /** 作者申诉被拒投稿。仅本人 + 仅 REJECTED。 */
    VideoBountySubmissionView appeal(long memberId, long submissionId, String note);

    /** 作者取消自己的投稿(已通过的需走运营撤销,避免作者自行抹掉已计应计)。 */
    VideoBountySubmissionView cancel(long memberId, long submissionId);

    /**
     * 运营审核:APPROVE 需 publishedAt;REJECT 需 reviewNote。权限由 Controller 校验。
     *
     * @param reviewerRef 审核人标识,必须带 realm 前缀(如 {@code sys:1}),
     *                    否则不同 id 空间的"1 号"会被记成同一个人,审计无法回溯
     */
    VideoBountySubmissionView review(String reviewerRef, long submissionId, VideoBountyReviewRequest request);

    /** 运营撤销已通过投稿:相应应计置 REVERSED,不删除。{@code reviewerRef} 同上。 */
    VideoBountySubmissionView revoke(String reviewerRef, long submissionId, String note);

    /**
     * 运营核验「这条投稿属于哪一条原视频」。封顶与应计只认这个结论。
     *
     * <p>作者提交的 {@code originGroupKey} 只是建议:同一视频在两个平台各填一个组键就能把
     * {@code per_video_cap} 拆开绕掉,所以必须由具备审阅权的人在看得到证据的前提下确认分组。
     *
     * <p>规则：
     * <ul>
     *   <li>只有 APPROVED 投稿可核验；</li>
     *   <li>必须给 {@code sourceRef}（证据引用）与 {@code reason}，写入审计；</li>
     *   <li>已有有效应计后组键不可再改（防"先按小组计满、再并组"）；</li>
     *   <li>同结论重投按版本判定幂等，不重复记审计；并发核验只有一方生效，另一方 409。</li>
     * </ul>
     *
     * @param reviewerRef 审核人标识，必须带 realm 前缀（如 {@code sys:1}）
     */
    VideoBountySubmissionView verifyOriginalGroup(String reviewerRef, long submissionId,
                                                  String verifiedGroupKey, String sourceRef,
                                                  Integer expectedVersion, String reason);

    /**
     * 录入外部平台播放证据。仅对 APPROVED 投稿;{@code sourceRef} 必填。
     * 同一证据内容重复录入按 {@code evidenceHash} 幂等(返回既有行,不重复累加)。
     */
    VideoBountyEvidenceView recordEvidence(String operator, long submissionId, VideoBountyEvidenceRequest request);

    /**
     * 依据证据重算同组应计。幂等:同组同一证据水位与规则版本只会产生一行;
     * 旧行置 SUPERSEDED。返回本条投稿的应计(可能是既有行)。
     */
    VideoBountyAccrual recomputeAccrual(long submissionId);

    /** 作者查自己的应计(只读;不是可提现余额)。 */
    List<VideoBountyAccrual> listMyAccruals(long memberId);

    // ------------------------------------------------------------------ 运营查询

    List<VideoBountySubmission> adminListSubmissions(Long campaignId, Integer status, int offset, int limit);

    List<VideoBountyAccrual> adminListAccruals(Long campaignId, int offset, int limit);

    // ------------------------------------------------------------------ 活动配置(运营)

    /**
     * 新建活动。默认草稿状态、默认 {@code cashPayoutEnabled=0};
     * 费率/封顶/窗口只是可配置参考值,不是已批准的线上费率。
     *
     * @param operatorRef 运营人标识,必须带 realm 前缀(如 {@code sys:1});
     *                    后台账号与会员是两套 id 空间,裸数字无法回溯是谁操作
     */
    VideoCampaign createCampaign(String operatorRef, VideoCampaignRequest request);

    /**
     * 更新活动配置。
     *
     * <p>⚠️ 一旦该活动已有应计,拒绝修改费率/单条封顶/窗口 —— 那会追溯改写历史口径。
     * 要改必须新建活动(或明确以新 ruleVersion 起新活动)。
     */
    VideoCampaign updateCampaign(String operatorRef, long campaignId, VideoCampaignRequest request);

    /** 活动状态流转(草稿/进行中/暂停/关闭),CAS 保证并发只有一方生效。 */
    VideoCampaign changeCampaignStatus(String operatorRef, long campaignId, int targetStatus);

    List<VideoCampaign> listCampaigns(Integer status);

    // ------------------------------------------------------------------ 成交归因(消费归因域,不自建推荐码)

    /**
     * 作者读<b>自己那条投稿</b>带来的成交归因。
     *
     * <p>聚合主体(sharer)由服务端从投稿行读取,<b>不接受客户端指定</b>;
     * 目标 scope 固定为投稿的作品主题(TYPE=TOPIC)。未绑定凭据时返回
     * {@code status=UNBOUND} 且数字全为 null —— 不能显示成"确认 0 成交"。
     */
    VideoCampaignAttributionView submissionAttribution(long memberId, long submissionId);

    /**
     * 运营/超管读某条投稿的成交归因。
     *
     * <p>同样由服务端加载投稿行与其作者作为聚合主体;{@code reviewerRef} 只用于
     * 判断"你是不是这条活动的 owner",<b>不会</b>被当作聚合主体去查别人的单。
     */
    VideoCampaignAttributionView reviewedAttribution(String reviewerRef, long submissionId);

    /**
     * 活动 owner 按目标主题读成交归因。
     *
     * <p>只有 {@code campaign.ownerMemberId == memberId} 才允许;{@code topicId} 必须是
     * 该活动下真实已通过投稿的作品,否则视为越界(不返回任何数字)。
     */
    VideoCampaignAttributionView ownerCampaignAttribution(long memberId, long campaignId, long topicId);
}
