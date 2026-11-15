package com.chengyinhub.business.channelevent.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.chengyinhub.business.channelevent.ChannelEventRow;

/**
 * C3 channel events: {@code channel_event}, {@code channel_event_rsvp}, {@code channel_event_photo}
 * (see {@code sql/migration_20261116_channel_events.sql}), plus the narrow reads over the existing activity,
 * ticket, enrolment and member tables they need. Enrolment truth stays in {@code cms_registration}:
 * a member is "going" only with a confirmed registration (payment_status 2, registration_status 2), the same
 * predicate the activity channel uses ({@code PlayTeamMapper.hasPaidRegistration}).
 */
public interface ChannelEventMapper
{
    String EVENT_COLS = "id, club_id AS clubId, channel_id AS channelId, activity_id AS activityId, ticket_id AS ticketId, "
            + "message_id AS messageId, poster_member_id AS posterMemberId, title, start_at AS startAt, end_at AS endAt, "
            + "place_name AS placeName, lat, lng, capacity, paid, status, create_time AS createTime";

    String CONFIRMED = "r.owner_type = 2 AND r.owner_id = #{activityId} AND r.payment_status = 2 AND r.registration_status = 2";

    // ------------------------------------------------------------------ channel_event

    @Insert("INSERT INTO channel_event (club_id, channel_id, activity_id, ticket_id, poster_member_id, title, start_at, end_at, "
            + "place_name, lat, lng, capacity, paid, status, create_time, update_time) VALUES (#{clubId}, #{channelId}, #{activityId}, "
            + "#{ticketId}, #{posterMemberId}, #{title}, #{startAt}, #{endAt}, #{placeName}, #{lat}, #{lng}, #{capacity}, #{paid}, "
            + "#{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertEvent(ChannelEventRow row);

    @Select("SELECT " + EVENT_COLS + " FROM channel_event WHERE id = #{id}")
    ChannelEventRow selectEvent(@Param("id") long id);

    @Select("SELECT " + EVENT_COLS + " FROM channel_event WHERE id = #{id} FOR UPDATE")
    ChannelEventRow lockEvent(@Param("id") long id);

    @Select("SELECT " + EVENT_COLS + " FROM channel_event WHERE activity_id = #{activityId}")
    ChannelEventRow selectByActivity(@Param("activityId") long activityId);

    /** Upcoming and running first (soonest first), then up to the limit of past ones (latest first). */
    @Select("SELECT " + EVENT_COLS + " FROM channel_event WHERE channel_id = #{channelId} AND status IN (0, 1) "
            + "ORDER BY (end_at < #{now}) ASC, CASE WHEN end_at >= #{now} THEN start_at END ASC, start_at DESC LIMIT #{limit}")
    List<ChannelEventRow> selectByChannel(@Param("channelId") long channelId, @Param("now") Date now, @Param("limit") int limit);

    @Select("SELECT " + EVENT_COLS + " FROM channel_event WHERE channel_id = #{channelId} AND status = 0")
    List<ChannelEventRow> selectWaitingByChannel(@Param("channelId") long channelId);

    @Update("UPDATE channel_event SET message_id = #{messageId}, status = 1, update_time = NOW() WHERE id = #{id} AND status = 0")
    int markPosted(@Param("id") long id, @Param("messageId") long messageId);

    @Update("UPDATE channel_event SET status = 2, update_time = NOW() WHERE id = #{id} AND status IN (0, 1)")
    int markCancelled(@Param("id") long id);

    /** Admin console: newest first, optional club filter. */
    @Select("<script>SELECT " + EVENT_COLS + " FROM channel_event <where><if test='clubId != null'>club_id = #{clubId}</if>"
            + "<if test='beforeId != null'> AND id &lt; #{beforeId}</if></where> ORDER BY id DESC LIMIT #{limit}</script>")
    List<ChannelEventRow> selectForAdmin(@Param("clubId") Long clubId, @Param("beforeId") Long beforeId, @Param("limit") int limit);

    // ------------------------------------------------------------------ existing activity / ticket / enrolment

    /** Review state of the underlying activity: status 1 = approved, publish_status 1 = on shelf. */
    @Select("SELECT id, status, publish_status AS publishStatus, del_flag AS delFlag, cancel_time AS cancelTime, club_id AS clubId, "
            + "member_id AS memberId FROM cms_activity WHERE id = #{activityId}")
    Map<String, Object> selectActivityState(@Param("activityId") long activityId);

    @Select("SELECT id FROM oms_ticket WHERE owner_type = 2 AND owner_id = #{activityId} AND del_flag = 0 ORDER BY id LIMIT 1")
    Long selectFirstTicketId(@Param("activityId") long activityId);

    @Select("SELECT remaining_inventory FROM oms_ticket WHERE id = #{ticketId}")
    Integer selectRemaining(@Param("ticketId") long ticketId);

    @Select("SELECT COUNT(DISTINCT r.member_id) FROM cms_registration r WHERE " + CONFIRMED)
    int countGoing(@Param("activityId") long activityId);

    @Select("SELECT COUNT(1) FROM cms_registration r WHERE " + CONFIRMED + " AND r.member_id = #{memberId}")
    int countMyConfirmed(@Param("activityId") long activityId, @Param("memberId") long memberId);

    /** My open (not cancelled) enrolment, confirmed or still waiting for payment; null when none. */
    @Select("SELECT id, payment_status AS paymentStatus, registration_status AS registrationStatus FROM cms_registration "
            + "WHERE owner_type = 2 AND owner_id = #{activityId} AND member_id = #{memberId} AND registration_status IN (1, 2) "
            + "ORDER BY id DESC LIMIT 1")
    Map<String, Object> selectMyOpenRegistration(@Param("activityId") long activityId, @Param("memberId") long memberId);

    @Select("SELECT DISTINCT r.member_id AS memberId, m.nickname, m.avatar FROM cms_registration r "
            + "LEFT JOIN ums_member m ON m.id = r.member_id WHERE " + CONFIRMED + " AND (m.del_flag IS NULL OR m.del_flag = 0) "
            + "ORDER BY r.member_id LIMIT #{limit}")
    List<Map<String, Object>> selectGoingMembers(@Param("activityId") long activityId, @Param("limit") int limit);

    // ------------------------------------------------------------------ channel_event_rsvp

    @Insert("INSERT INTO channel_event_rsvp (event_id, member_id, status, create_time, update_time) "
            + "VALUES (#{eventId}, #{memberId}, #{status}, NOW(), NOW()) ON DUPLICATE KEY UPDATE status = VALUES(status), update_time = NOW()")
    int upsertRsvp(@Param("eventId") long eventId, @Param("memberId") long memberId, @Param("status") String status);

    @Select("SELECT status FROM channel_event_rsvp WHERE event_id = #{eventId} AND member_id = #{memberId}")
    String selectMyRsvp(@Param("eventId") long eventId, @Param("memberId") long memberId);

    /** RSVPs of members without a confirmed enrolment (a confirmed enrolment always reads as going). */
    @Select("SELECT COUNT(1) FROM channel_event_rsvp v WHERE v.event_id = #{eventId} AND v.status = #{status} AND NOT EXISTS "
            + "(SELECT 1 FROM cms_registration r WHERE " + CONFIRMED + " AND r.member_id = v.member_id)")
    int countRsvpWithoutEnrolment(@Param("eventId") long eventId, @Param("activityId") long activityId, @Param("status") String status);

    @Select("SELECT v.member_id AS memberId, m.nickname, m.avatar FROM channel_event_rsvp v LEFT JOIN ums_member m ON m.id = v.member_id "
            + "WHERE v.event_id = #{eventId} AND v.status = 'MAYBE' AND (m.del_flag IS NULL OR m.del_flag = 0) AND NOT EXISTS "
            + "(SELECT 1 FROM cms_registration r WHERE " + CONFIRMED + " AND r.member_id = v.member_id) "
            + "ORDER BY v.update_time DESC LIMIT #{limit}")
    List<Map<String, Object>> selectMaybeMembers(@Param("eventId") long eventId, @Param("activityId") long activityId, @Param("limit") int limit);

    /** MAYBE members of the channel event on this ticket (for the existing before-start reminder). */
    @Select("SELECT v.member_id FROM channel_event e JOIN channel_event_rsvp v ON v.event_id = e.id "
            + "WHERE e.ticket_id = #{ticketId} AND e.status = 1 AND v.status = 'MAYBE' LIMIT 500")
    List<Long> selectMaybeMemberIdsByTicket(@Param("ticketId") long ticketId);

    // ------------------------------------------------------------------ channel_event_photo

    @Insert("INSERT INTO channel_event_photo (event_id, member_id, asset_id, removed, create_time) "
            + "VALUES (#{eventId}, #{memberId}, #{assetId}, 0, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertPhoto(Map<String, Object> row);

    @Select("SELECT id, event_id AS eventId, member_id AS memberId, asset_id AS assetId, removed, removed_by AS removedBy, "
            + "create_time AS createTime FROM channel_event_photo WHERE id = #{id}")
    Map<String, Object> selectPhoto(@Param("id") long id);

    @Select("<script>SELECT id, event_id AS eventId, member_id AS memberId, asset_id AS assetId, removed, removed_by AS removedBy, "
            + "create_time AS createTime FROM channel_event_photo WHERE event_id = #{eventId}"
            + "<if test='!includeRemoved'> AND removed = 0</if><if test='beforeId != null'> AND id &lt; #{beforeId}</if>"
            + " ORDER BY id DESC LIMIT #{limit}</script>")
    List<Map<String, Object>> selectPhotos(@Param("eventId") long eventId, @Param("includeRemoved") boolean includeRemoved,
            @Param("beforeId") Long beforeId, @Param("limit") int limit);

    @Select("SELECT COUNT(1) FROM channel_event_photo WHERE event_id = #{eventId} AND member_id = #{memberId} AND removed = 0")
    int countMyPhotos(@Param("eventId") long eventId, @Param("memberId") long memberId);

    @Update("UPDATE channel_event_photo SET removed = #{removed}, removed_by = #{actor} WHERE id = #{id}")
    int setPhotoRemoved(@Param("id") long id, @Param("removed") int removed, @Param("actor") Long actor);

    // ------------------------------------------------------------------ the card message in the channel

    @Insert("INSERT INTO im_message (conversation_id, sender_id, receiver_id, msg_type, content, extra_json, status, check_status, "
            + "client_message_id, create_time, visible_at) VALUES (#{conversationId}, #{senderId}, 0, 1, #{content}, #{extraJson}, 0, 1, "
            + "#{clientMessageId}, NOW(), NOW(6))")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertCardMessage(Map<String, Object> message);

    @Select("SELECT ch.id AS channelId, ch.status AS channelStatus, cv.id AS conversationId FROM im_channel ch "
            + "LEFT JOIN im_conversation cv ON cv.id = ch.id AND (cv.type = 5 OR ch.legacy_conversation_id = ch.id) WHERE ch.id = #{channelId}")
    Map<String, Object> selectChannelConversation(@Param("channelId") long channelId);

    @Update("UPDATE im_conversation SET last_msg_type = 1, last_msg_text = #{text}, last_msg_at = NOW(), update_time = NOW() WHERE id = #{conversationId}")
    int touchConversation(@Param("conversationId") long conversationId, @Param("text") String text);

    @Update("UPDATE im_conversation_member SET unread_count = unread_count + 1 WHERE conversation_id = #{conversationId} "
            + "AND deleted = 0 AND member_id <> #{senderId}")
    int fanOutUnread(@Param("conversationId") long conversationId, @Param("senderId") long senderId);
}
