package com.chengyinhub.business.mapper.friend;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 好友合同（App NativeFriend*）的读写原语：好友对 member_friend、个人元数据 member_friend_meta、
 * 分组 member_friend_group（#727 三表）。授权、拉黑与 CAS 判定在 FriendService 同一事务里完成。
 */
public interface FriendMapper
{
    Map<String, Object> lockPair(@Param("low") long low, @Param("high") long high);

    Map<String, Object> selectPair(@Param("low") long low, @Param("high") long high);

    int insertPending(@Param("low") long low, @Param("high") long high, @Param("requester") long requester,
            @Param("msg") String msg, @Param("sourceType") int sourceType, @Param("sourceId") Long sourceId);

    int transition(@Param("id") long id, @Param("expectedVersion") long expectedVersion, @Param("status") int status,
            @Param("requester") long requester, @Param("msg") String msg, @Param("sourceType") int sourceType,
            @Param("sourceId") Long sourceId);

    int countBlock(@Param("a") long a, @Param("b") long b);

    String nickname(@Param("id") long id);

    List<Map<String, Object>> listFriends(@Param("viewer") long viewer, @Param("cursor") long cursor, @Param("limit") int limit);

    List<Map<String, Object>> listPending(@Param("viewer") long viewer, @Param("cursor") long cursor, @Param("limit") int limit);

    int countDirectory(@Param("viewer") long viewer, @Param("section") String section, @Param("groupId") Long groupId,
            @Param("starred") boolean starred);

    List<Map<String, Object>> listDirectory(@Param("viewer") long viewer, @Param("section") String section,
            @Param("groupId") Long groupId, @Param("starred") boolean starred, @Param("cursor") long cursor,
            @Param("limit") int limit);

    Long selectSingleConversation(@Param("bizKey") String bizKey);

    int insertSingleConversation(@Param("bizKey") String bizKey);

    int ensureConversationMember(@Param("conversationId") long conversationId, @Param("memberId") long memberId);

    Map<String, Object> lockMeta(@Param("owner") long owner, @Param("peer") long peer);

    Map<String, Object> selectMeta(@Param("owner") long owner, @Param("peer") long peer);

    int upsertMeta(@Param("owner") long owner, @Param("peer") long peer, @Param("remark") String remark,
            @Param("groupId") Long groupId, @Param("starred") int starred, @Param("progressScope") int progressScope);

    int clearPairMeta(@Param("a") long a, @Param("b") long b);

    List<Map<String, Object>> listGroups(@Param("owner") long owner);

    Map<String, Object> lockGroup(@Param("owner") long owner, @Param("id") long id);

    Long lockGroupOwner(@Param("owner") long owner);

    int countGroupName(@Param("owner") long owner, @Param("name") String name, @Param("exceptId") long exceptId);

    int countGroups(@Param("owner") long owner);

    int insertGroup(@Param("owner") long owner, @Param("name") String name, @Param("position") int position);

    int updateGroup(@Param("owner") long owner, @Param("id") long id, @Param("expectedVersion") long expectedVersion,
            @Param("name") String name, @Param("position") int position);

    int moveGroupMembers(@Param("owner") long owner, @Param("fromGroup") long fromGroup, @Param("toGroup") Long toGroup);

    int deleteGroup(@Param("owner") long owner, @Param("id") long id, @Param("expectedVersion") long expectedVersion);

    List<Map<String, Object>> listPeerProgress(@Param("peer") long peer);
}
