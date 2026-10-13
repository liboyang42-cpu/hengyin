package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopicNode;

import java.util.List;

/**
 * 自由探索:承接商家在章节下的自有节点(2026-08-06 拍板 B 方案)。
 *
 * <p>为什么要有这层:章节可招 N 家商家,但商家承接的是「供给 offer」,offer 上没有 nodeId ——
 * 商家在玩家眼里只是兑券点,没有自己的玩法。于是同章节 8 家店跑主办方同一套玩法,
 * 玩家去 1 家和去 8 家体验完全相同,「一章节招 8 家」的商业前提不成立。
 * 让商家拥有章节下的一个节点,玩家侧全部机制原样复用。
 *
 * <p><b>边界在招商时公示、承接时冻结</b>:允许的验证方式与探索值上限写在章节招商条款上,
 * 商家承接通过时冻进 offer,之后按**冻结的那份**校验 —— 主办方事后改章节不会让
 * 已上线的节点突然「超标」。
 *
 * <p><b>责任口径</b>:主办方审核 = <b>准入,不是背书</b>。内容责任在作者(商家)。
 * 所以节点上同时记「谁写的」与「谁放行的、何时」。
 */
public interface IChapterMerchantNodeService {

    /**
     * 商家提交/更新自己在某章节下的节点。提交即回到待审。
     *
     * @param merchantMemberId 当前登录商家
     * @param chapterId        目标章节(必须已有该商家生效的承接供给)
     * @param draft            节点内容;只取白名单字段,归属/审核态一律服务端定
     * @return 落库后的节点
     * @throws IllegalStateException 未承接 / 主题已开始 / 玩法超出冻结条款 / 模板不属于本人
     */
    CmsTopicNode submit(Long merchantMemberId, Long chapterId, CmsTopicNode draft);

    /** 商家看自己在该主题下的节点(含待审与驳回原因)。 */
    List<CmsTopicNode> listMine(Long merchantMemberId, Long topicId);

    /**
     * 取本人**可编辑**的节点:归属 + 承接生效 + 主题未开始。
     *
     * <p>给节点内容编辑(如节点 NPC)用。与 {@link #posterCodeNode} 的分工:那个是「玩家要扫到它」,
     * 所以还要求节点已过审、且有打卡码;这里是「作者要改它」——节点还没过审也应允许先把内容配好,
     * 玩家侧另有可见性闸(节点过审 + 内容过审)。
     *
     * @throws IllegalStateException 未登录 / 参数不合规 / 节点不存在或不归本人 / 承接失效 / 主题已开始
     */
    CmsTopicNode requireOwnLiveNode(Long merchantMemberId, Long nodeId);

    /**
     * 取本人节点用于**读取**(详情回填 / 声音状态):归属 + 承接生效,不判主题是否开始。
     *
     * <p>与 {@link #requireOwnLiveNode} 的分工:冻结闸只挡「写」——主题一开跑商家不能再改内容;
     * 但已经配好的内容与生成中的声音状态必须读得回来,否则商家点开自己的 NPC 只会看到失败半屏、
     * 2s 后被收掉,生成中的声音再也没人查,状态永远卡在「复刻中」。写入口(save/enroll/reset)一律仍用
     * {@link #requireOwnLiveNode}。
     *
     * @throws IllegalStateException 未登录 / 参数不合规 / 节点不存在或不归本人 / 承接失效
     */
    CmsTopicNode requireOwnNode(Long merchantMemberId, Long nodeId);

    /**
     * 批 5 · T2:商家取本店海报码要用的节点(带归属闸)。
     *
     * <p>返回的 {@code checkinCode} 就是**店内静态码本身** —— 玩家扫它,
     * {@code /api/play/checkin} 按 {@code checkin_code} 反查节点建进店事实。
     * 不要在这里改签动态核销码:那套是玩家掏给商家扫的,签出来的串 {@code /checkin} 反查不到节点。
     *
     * <p>两道闸都要过:① 节点 {@code merchant_member_id} == 本人 memberId
     * (探店日的归属真源是 memberId,不是 {@code mms_merchant.id} —— 照抄漫游会恒 null);
     * ② 该章节上有本人生效({@code status=1} 且 {@code valid_end} 未过期)的承接供给。
     * 只判 ① 会让退出承接的商家继续印码;只判 ② 会让同章节另一家商家印走邻居的码。
     *
     * @throws IllegalStateException 未登录 / 参数不合规 / 节点不存在或不归本人 / 承接失效 / 节点空码
     */
    CmsTopicNode posterCodeNode(Long merchantMemberId, Long nodeId);

    /**
     * 主办方审核商家节点。
     *
     * @param publisherMemberId 当前登录用户,必须是该节点所属主题的发布者
     * @param approve           true 放行 / false 驳回
     * @param reason            驳回原因(驳回时必填)
     */
    CmsTopicNode audit(Long publisherMemberId, Long nodeId, boolean approve, String reason);

    /**
     * 随章节申请的审核一并处置该商家在这一章的点位。
     *
     * <p>2026-08-06 用户指出:报名时就该把点位填好,主办方**一次审完** ——
     * 既定要不要这家,也审他要做什么玩法。原来分两次审等于让主办方在不知道玩法的情况下
     * 先决定要不要人,商家还要多等一轮。
     *
     * <p>调用方是申请审核/撤回链路,归属与权限已在那边校验过,这里不重复校验
     * (重复校验容易两边口径漂移);它只负责把点位的审核态跟着申请一起翻。撤回时用商家作处理人,
     * 并把点位标为驳回,内容保留供重申后继续编辑。
     *
     * @return 处置到的点位数(没提交点位就是 0,不是错误)
     */
    int auditAlongApplication(Long chapterId, Long merchantMemberId, Long actorMemberId,
                              boolean approve, String reason);
}
