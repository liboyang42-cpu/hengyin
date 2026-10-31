package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.RoamHangout;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * 漫游·附近的局。免费、无票、零资金流;加入 = 进群;成员真源 = IM 群成员表。
 * 所有闸的错误文案都指明是哪道闸(「标题含违规内容」「同时最多开 3 个局」「群已满」)。
 */
public interface IRoamHangoutService {

    int MAX_ACTIVE_PER_MEMBER = 3;
    int MAX_MEMBERS = 200;
    int DEFAULT_RADIUS_M = 3000;
    /** 走查 P-38(业主):附近地图「全部」= 所有距离;结果仍按距离由近到远截断条数,半径上限只防坐标溢出。 */
    int MAX_RADIUS_M = 1_000_000;
    /** 返回 {items:[局 + 活动], suggestedRadius?, suggestedCount?}。viewerId 用于 isMember 与拉黑过滤;必须登录。 */
    Map<String, Object> nearby(Long viewerId, double lat, double lng, int radiusM);

    RoamHangout create(Long memberId, String title, String description, String coverUrl,
                       BigDecimal latitude, BigDecimal longitude, String addressName, Date startAt);

    /** 局 + members(≤6 头像) + memberCount + isMember + isOwner;非进行中只对局主/成员可见。 */
    Map<String, Object> detail(Long viewerId, Long id);

    /** 幂等:已在群直接返回 conversationId。 */
    Long join(Long memberId, Long id);

    void leave(Long memberId, Long id);

    void close(Long memberId, Long id);

    Long report(Long memberId, Long id, String reason);

    /** 定时任务入口:关掉过期局并关群,返回关掉的个数。 */
    int expireDue(Date now);

    /** 审核工作台处置:hidden=true 违规成立 → status=2 + 关群;false 举报无效 → 不改变组局及群状态。 */
    void moderate(Long id, boolean hidden);

    /** 账号注销执行期:把该成员进行中的局全部关掉并关群,返回个数。 */
    int closeAllByMember(Long memberId);
}
