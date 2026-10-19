package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.ImMessage;

/**
 * 站内消息(IM) Service
 *
 * 约定：业务校验失败(未登录/越权/被拉黑/内容违规)统一抛 RuntimeException(中文提示)，
 * 控制器 try/catch 转 AjaxResult.error。
 */
public interface IImService
{
    /** 我的会话列表(组装对方信息 + 最后一条 + 未读) */
    List<Map<String, Object>> listConversations(Long memberId);

    /** 会话消息分页(校验我是参与者)；返回 { list(按时间正序), nextCursor, hasMore } */
    Map<String, Object> listMessages(Long memberId, Long conversationId, Long cursorId, int size);

<<<<<<< public
    Map<String, Object> listMessages(Long memberId, Long conversationId, Long cursorId, int size, Long afterId);

    /** 只读原发送收据，不重新发消息。 */
    ImMessage readSentMessage(Long memberId, Long conversationId, String clientMessageId);
=======
    /** 增量同步；afterId 之后按 id 升序读取，并把首次查询水位固定在 watermarkId。 */
    Map<String, Object> listMessagesAfter(Long memberId, Long conversationId, Long afterId,
                                          Long watermarkId, int size);

    /** 在当前会话内按客户端消息标识精确回读本人原消息。 */
    ImMessage findOwnMessage(Long memberId, Long conversationId, String clientMessageId);
>>>>>>> private-codex/batch1

    /** 发送消息(文本/图片)；内容安全前置；成功返回落库的消息 */
    ImMessage send(Long memberId, Long conversationId, Long targetMemberId, Integer msgType, String content, String extraJson);

    ImMessage send(Long memberId, Long conversationId, Long targetMemberId, Integer msgType,
                   String content, String extraJson, String clientMessageId);

    /** 群功能共用的实时成员闸；会话索引行不能独立授权。 */
    void requireActiveGroupMembership(Long memberId, Long conversationId);

    /** 结果读取使用：当前真实群成员可读，新加入成员不依赖创建时快照。 */
    boolean isActiveGroupMember(Long memberId, Long conversationId);

    /** 标记会话已读 */
    void markRead(Long memberId, Long conversationId);

<<<<<<< public
    void markRead(Long memberId, Long conversationId, Long readTo);
=======
    /** 仅把客户端实际消费到的本会话消息推进为已读；读位单调不回退。 */
    void markRead(Long memberId, Long conversationId, Long readToMessageId);
>>>>>>> private-codex/batch1

    /** 删除会话(仅对当前用户隐藏) */
    void deleteConversation(Long memberId, Long conversationId);

    /** 设置会话免打扰(仅对当前用户;仍累未读,但不计入 unreadTotal 红点) */
    void setMuted(Long memberId, Long conversationId, boolean muted);

    /** 未读总数(红点) */
    int unreadTotal(Long memberId);

    /** 发起/获取与某用户的单聊会话，返回会话id(含拉黑校验) */
    Long startSingle(Long memberId, Long targetMemberId);

    /** 获取/创建俱乐部群会话(bizKey=club_<id>),把传入成员全量灌入(自愈),返回会话id */
    Long getOrCreateClubGroup(Long clubId, Long requesterId);

    /** 退出俱乐部群:对该成员隐藏群会话(从其会话列表移除) */
    void leaveClubGroup(Long clubId, Long memberId);

    /** 解散俱乐部群：封禁会话并对全部参与者隐藏，历史消息保留作审计。 */
    void closeClubGroup(Long clubId);

    /** 获取/创建「附近的局」群会话(bizKey=hangout_<id>);成员真源就是群成员表本身,局主与 requester 都灌入。 */
    Long getOrCreateHangoutGroup(Long hangoutId, Long requesterId, Long ownerId);

    /** 退出局 = 对该成员隐藏局群(群成员表即局成员真源)。 */
    void leaveHangoutGroup(Long hangoutId, Long memberId);

    /** 关局 / 隐藏局后关闭群聊,历史消息保留。 */
    void closeHangoutGroup(Long hangoutId);

    /** 获取/创建玩家队伍群会话(bizKey=team_<id>)，成员真源为 play_team_member。 */
    Long getOrCreateTeamGroup(Long teamId, Long requesterId);

    /** 玩家退队/被移出后隐藏队伍群。 */
    void leaveTeamGroup(Long teamId, Long memberId);

    /** 队伍解散/到期后关闭群聊，历史消息保留。 */
    void closeTeamGroup(Long teamId);

    /** 系统消息：向某用户的系统会话下发(供报名/审核结果等调用)，返回消息id */
    Long sendSystem(Long toMemberId, Integer msgType, String content, String extraJson);

    /** 外层广播事务内的 NESTED 保存点系统消息；外层失败时必须一起回滚。 */
    Long sendSystemInNestedTransaction(Long toMemberId, Integer msgType, String content, String extraJson);

    /** 与原业务同事务持久化；同key异内容拒绝，不代表已经送达。 */
    Long enqueueSystemNotification(Long toMemberId, Integer msgType, String content, String extraJson, String eventKey);

    /** 原意图内部投递，已终态返回原收据，不新建事件。 */
    Long deliverSystemNotification(Long intentId);

    /** 用户可控文本在进入系统消息通道前，按真实发送者身份执行内容安全检查。 */
    void checkUserGeneratedText(Long senderMemberId, String content);

    /** 拉黑 / 取消拉黑 */
    void block(Long memberId, Long targetMemberId);
    void unblock(Long memberId, Long targetMemberId);
    boolean isBlockedBetween(Long memberId, Long targetMemberId);

    /** 举报消息(转统一审核) */
    void report(Long memberId, Long messageId, String reason);

    // ===== 后台客服端 =====

    /** 客服(官方)向某用户/商家发送消息(客服会话 type=3, 发送者=系统0)，返回消息id */
    Long sendFromAdmin(Long toMemberId, Integer msgType, String content, String extraJson);

    /** 后台:列出所有客服会话(含对方信息 + 最后一条) */
    List<Map<String, Object>> adminListConversations();

    /** 后台:某会话消息分页(不做参与者校验) */
    Map<String, Object> adminListMessages(Long conversationId, Long cursorId, int size);

    /** 平台拉黑/解除：禁止该用户在站内发消息 */
    void adminBlock(Long targetMemberId);
    void adminUnblock(Long targetMemberId);
    boolean isPlatformBlocked(Long memberId);
}
