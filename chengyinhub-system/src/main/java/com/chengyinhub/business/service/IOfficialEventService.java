package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.OfficialEvent;

/**
 * 官方活动(可配置活动引擎)服务:列表/详情/报名/进度/我的 + 白名单发布 + 状态流转 + 结算发奖。
 *
 * @author cy
 * @date 2026-07-08
 */
public interface IOfficialEventService
{
    /** 玩家端可见活动列表(含倒计时字段/集体进度/是否已报名)。 */
    List<Map<String, Object>> listPublic(String city, Long memberId);

    /** 活动详情(含集体进度/我的报名态/参与人数)。memberId<=0 时不含个人态。 */
    Map<String, Object> detail(Long id, Long memberId);

    /** 报名(uk 幂等)。返回错误消息;null=成功。 */
    String signup(Long eventId, Long memberId);

    /** 完成一个活动任务:progress+1 + 即时XP + 达标判定。返回错误消息;null=成功。 */
    String completeTask(Long eventId, Long memberId);

    /** 集体进度 + 参与/完成人数。 */
    Map<String, Object> progress(Long eventId);

    /** 我报名的活动(含个人进度)。 */
    List<Map<String, Object>> myEvents(Long memberId);

    /** 可点亮的官方活动(roam 绑定 · 报名中/进行中),供 roam 页发现当前可参与的城市点亮活动。 */
    List<Map<String, Object>> activeForRoam(String city, Long memberId);

    /** 白名单发布:建活动(按时间定初始 status)。 */
    OfficialEvent publish(OfficialEvent e, Long senderAccount);

    List<OfficialEvent> listBySender(Long senderAccount);

    /** 后台:按条件列出全部活动。 */
    List<OfficialEvent> adminList(OfficialEvent criteria);

    /** 后台:取活动原始对象(编辑回显用)。 */
    OfficialEvent adminGet(Long id);

    /** 后台:新建(id空,按时间定status)或更新活动。 */
    OfficialEvent adminSave(OfficialEvent e, Long operatorId);

    /** 后台:改状态(上线2报名中/下线9)。 */
    void adminSetStatus(Long id, Integer status);

    /** Quartz:按时间推进 status;进入结算态触发发奖。 */
    void runStatusFlow();

    /** 结算发奖(即时/结算/集体达标;reward_claimed 幂等)。返回发奖人数。 */
    int settle(Long eventId);
}
