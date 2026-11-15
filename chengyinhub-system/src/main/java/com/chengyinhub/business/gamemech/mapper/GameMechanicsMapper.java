package com.chengyinhub.business.gamemech.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * G2 game mechanics ({@code sql/migration_20261115_game_mechanics.sql}): treasure hunts, duels and walk tests.
 * Item coordinates are read only by the find check and by the owner/operator editors, never by a player list.
 */
public interface GameMechanicsMapper {

    // ------------------------------------------------------------------ treasure hunt
    String HUNT_COLUMNS = "h.id, h.title, h.intro, h.cover_img as coverImg, h.city_code as cityCode, h.status,"
            + " h.creator_member_id as creatorMemberId, h.ai_assisted as aiAssisted, h.review_note as reviewNote,"
            + " h.start_time as startTime, h.end_time as endTime, h.create_time as createTime, h.update_time as updateTime";
    String LIVE = "h.status = 2 and (h.start_time is null or h.start_time <= now()) and (h.end_time is null or h.end_time > now())";

    @Select("select " + HUNT_COLUMNS + ", (select count(*) from treasure_hunt_item i where i.hunt_id = h.id) as itemCount"
            + " from treasure_hunt h where " + LIVE
            + " and (#{cityCode} is null or #{cityCode} = '' or h.city_code is null or h.city_code = #{cityCode})"
            + " order by h.id desc limit #{limit}")
    List<Map<String, Object>> selectLiveHunts(@Param("cityCode") String cityCode, @Param("limit") int limit);

    @Select("select " + HUNT_COLUMNS + " from treasure_hunt h where h.id = #{id}")
    Map<String, Object> selectHunt(@Param("id") Long id);

    @Select("select " + HUNT_COLUMNS + " from treasure_hunt h where h.id = #{id} for update")
    Map<String, Object> selectHuntForUpdate(@Param("id") Long id);

    @Select("select " + HUNT_COLUMNS + ", (select count(*) from treasure_hunt_item i where i.hunt_id = h.id) as itemCount"
            + " from treasure_hunt h where h.creator_member_id = #{memberId} order by h.id desc limit 50")
    List<Map<String, Object>> selectHuntsByCreator(@Param("memberId") Long memberId);

    @Select("<script>select " + HUNT_COLUMNS + ", (select count(*) from treasure_hunt_item i where i.hunt_id = h.id) as itemCount"
            + " from treasure_hunt h <where><if test='status != null'>h.status = #{status}</if></where>"
            + " order by h.id desc limit 200</script>")
    List<Map<String, Object>> selectAdminHunts(@Param("status") Integer status);

    @Insert("insert into treasure_hunt(title, intro, cover_img, city_code, status, creator_member_id, ai_assisted,"
            + " start_time, end_time, create_by, create_time, update_time)"
            + " values(#{title}, #{intro}, #{coverImg}, #{cityCode}, #{status}, #{creatorMemberId}, #{aiAssisted},"
            + " #{startTime}, #{endTime}, #{createBy}, now(), now())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertHunt(Map<String, Object> row);

    @Update("update treasure_hunt set title = #{title}, intro = #{intro}, cover_img = #{coverImg}, city_code = #{cityCode},"
            + " ai_assisted = #{aiAssisted}, start_time = #{startTime}, end_time = #{endTime},"
            + " update_by = #{updateBy}, update_time = now() where id = #{id}")
    int updateHunt(Map<String, Object> row);

    @Update("update treasure_hunt set status = #{status}, review_note = #{note}, update_by = #{by}, update_time = now()"
            + " where id = #{id}")
    int updateHuntStatus(@Param("id") Long id, @Param("status") int status, @Param("note") String note, @Param("by") String by);

    @Select("select i.id, i.hunt_id as huntId, i.sort, i.name, i.clue, i.lat, i.lng, i.radius_m as radiusM"
            + " from treasure_hunt_item i where i.hunt_id = #{huntId} order by i.sort, i.id")
    List<Map<String, Object>> selectItems(@Param("huntId") Long huntId);

    @Delete("delete from treasure_hunt_item where hunt_id = #{huntId}")
    int deleteItems(@Param("huntId") Long huntId);

    @Insert("insert into treasure_hunt_item(hunt_id, sort, name, clue, lat, lng, radius_m)"
            + " values(#{huntId}, #{sort}, #{name}, #{clue}, #{lat}, #{lng}, #{radiusM})")
    int insertItem(Map<String, Object> row);

    @Select("select f.item_id as itemId, f.found_at as foundAt from treasure_hunt_find f"
            + " where f.hunt_id = #{huntId} and f.member_id = #{memberId}")
    List<Map<String, Object>> selectFinds(@Param("huntId") Long huntId, @Param("memberId") Long memberId);

    @Insert("insert ignore into treasure_hunt_find(hunt_id, item_id, member_id, found_at, fix_id)"
            + " values(#{huntId}, #{itemId}, #{memberId}, now(), #{fixId})")
    int insertFind(@Param("huntId") Long huntId, @Param("itemId") Long itemId, @Param("memberId") Long memberId,
                   @Param("fixId") Long fixId);

    @Select("select p.found_count as foundCount, p.completed_at as completedAt, p.reward_points as rewardPoints"
            + " from treasure_hunt_progress p where p.hunt_id = #{huntId} and p.member_id = #{memberId}")
    Map<String, Object> selectProgress(@Param("huntId") Long huntId, @Param("memberId") Long memberId);

    @Insert("insert into treasure_hunt_progress(hunt_id, member_id, found_count, update_time)"
            + " values(#{huntId}, #{memberId}, #{found}, now())"
            + " on duplicate key update found_count = values(found_count), update_time = now()")
    int upsertProgress(@Param("huntId") Long huntId, @Param("memberId") Long memberId, @Param("found") int found);

    @Update("update treasure_hunt_progress set completed_at = now(), reward_points = #{points}, update_time = now()"
            + " where hunt_id = #{huntId} and member_id = #{memberId} and completed_at is null")
    int markCompleted(@Param("huntId") Long huntId, @Param("memberId") Long memberId, @Param("points") int points);

    @Select("select p.hunt_id as huntId, p.found_count as foundCount, p.completed_at as completedAt,"
            + " p.reward_points as rewardPoints from treasure_hunt_progress p where p.member_id = #{memberId}"
            + " order by p.update_time desc limit 50")
    List<Map<String, Object>> selectMemberProgress(@Param("memberId") Long memberId);

    @Select("select count(*) from treasure_hunt_progress where hunt_id = #{huntId}")
    int countPlayers(@Param("huntId") Long huntId);

    @Select("select count(*) from treasure_hunt_progress where hunt_id = #{huntId} and completed_at is not null")
    int countFinishers(@Param("huntId") Long huntId);

    // ------------------------------------------------------------------ duel
    String DUEL_COLUMNS = "d.id, d.topic_id as topicId, d.challenger_id as challengerId, d.opponent_id as opponentId,"
            + " d.relation, d.status, d.created_at as createdAt, d.accepted_at as acceptedAt, d.expires_at as expiresAt,"
            + " d.challenger_seconds as challengerSeconds, d.challenger_done_at as challengerDoneAt,"
            + " d.opponent_seconds as opponentSeconds, d.opponent_done_at as opponentDoneAt, d.result, d.version,"
            + " t.name as topicName";

    @Insert("insert into game_duel(topic_id, challenger_id, opponent_id, relation, status, created_at, expires_at)"
            + " values(#{topicId}, #{challengerId}, #{opponentId}, #{relation}, 'PENDING', now(3), #{expiresAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDuel(Map<String, Object> row);

    @Select("select " + DUEL_COLUMNS + " from game_duel d left join cms_topic t on t.id = d.topic_id where d.id = #{id}")
    Map<String, Object> selectDuel(@Param("id") Long id);

    @Select("select " + DUEL_COLUMNS + " from game_duel d left join cms_topic t on t.id = d.topic_id"
            + " where d.challenger_id = #{memberId} or d.opponent_id = #{memberId} order by d.id desc limit 50")
    List<Map<String, Object>> selectMemberDuels(@Param("memberId") Long memberId);

    @Select("select count(*) from game_duel where challenger_id = #{challengerId} and opponent_id = #{opponentId}"
            + " and topic_id = #{topicId} and status in ('PENDING', 'ACTIVE')")
    int countOpenDuels(@Param("challengerId") Long challengerId, @Param("opponentId") Long opponentId,
                       @Param("topicId") Long topicId);

    @Select("select count(*) from game_duel where challenger_id = #{memberId} and created_at >= #{since}")
    int countDuelsSince(@Param("memberId") Long memberId, @Param("since") Date since);

    @Update("<script>update game_duel set status = #{status}, version = version + 1"
            + "<if test='accept'>, accepted_at = now(3)</if>"
            + " where id = #{id} and version = #{version}</script>")
    int updateDuelStatus(@Param("id") Long id, @Param("status") String status, @Param("accept") boolean accept,
                         @Param("version") int version);

    @Update("update game_duel set challenger_seconds = #{cs}, challenger_done_at = #{cd}, opponent_seconds = #{os},"
            + " opponent_done_at = #{od}, result = #{result}, status = #{status}, version = version + 1"
            + " where id = #{id} and version = #{version}")
    int updateDuelResult(@Param("id") Long id, @Param("cs") Integer challengerSeconds, @Param("cd") Date challengerDoneAt,
                         @Param("os") Integer opponentSeconds, @Param("od") Date opponentDoneAt,
                         @Param("result") String result, @Param("status") String status, @Param("version") int version);

    /** First journey run that started or was still running after {@code since} and reached the end. */
    @Select("select r.create_time as startedAt, r.completed_at as completedAt from topic_route_session r"
            + " where r.member_id = #{memberId} and r.topic_id = #{topicId} and r.status = 1"
            + " and r.completed_at is not null and r.completed_at >= #{since}"
            + " order by r.completed_at asc limit 1")
    Map<String, Object> selectFirstCompletionSince(@Param("memberId") Long memberId, @Param("topicId") Long topicId,
                                                   @Param("since") Date since);

    @Select("select count(*) from club c where c.status = 1 and c.del_flag = 0"
            + " and (c.member_id = #{a} or exists (select 1 from club_member cm where cm.club_id = c.id"
            + "   and cm.member_id = #{a} and cm.status = 1 and cm.del_flag = 0))"
            + " and (c.member_id = #{b} or exists (select 1 from club_member cm where cm.club_id = c.id"
            + "   and cm.member_id = #{b} and cm.status = 1 and cm.del_flag = 0))")
    int countSharedClubs(@Param("a") Long a, @Param("b") Long b);

    @Select("select id, name, status, member_id as memberId from cms_topic where id = #{id}")
    Map<String, Object> selectTopicBrief(@Param("id") Long id);

    @Select("select nickname from ums_member where id = #{id}")
    String selectNickname(@Param("id") Long id);

    // ------------------------------------------------------------------ walk test
    @Insert("insert into topic_walk_test(topic_id, kind, member_id, recorded_at, nodes_checked, nodes_total, note,"
            + " fix_decision, fix_id, near_node_id)"
            + " values(#{topicId}, #{kind}, #{memberId}, now(), #{nodesChecked}, #{nodesTotal}, #{note},"
            + " #{fixDecision}, #{fixId}, #{nearNodeId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertWalkTest(Map<String, Object> row);

    @Select("select w.id, w.kind, w.member_id as memberId, w.recorded_at as recordedAt, w.nodes_checked as nodesChecked,"
            + " w.nodes_total as nodesTotal, w.note, w.fix_decision as fixDecision, w.near_node_id as nearNodeId"
            + " from topic_walk_test w where w.topic_id = #{topicId} order by w.recorded_at desc, w.id desc limit 20")
    List<Map<String, Object>> selectWalkTests(@Param("topicId") Long topicId);

    @Select("select w.topic_id as topicId, t.name as topicName, t.member_id as ownerId,"
            + " sum(case when w.kind = 'DESK' then 1 else 0 end) as deskCount,"
            + " sum(case when w.kind = 'ONSITE' and w.fix_decision = 'ACCEPT' then 1 else 0 end) as onsiteCount,"
            + " max(w.recorded_at) as lastRecordedAt,"
            + " (select v.note from topic_walk_test_waiver v where v.topic_id = w.topic_id) as waiverNote"
            + " from topic_walk_test w left join cms_topic t on t.id = w.topic_id"
            + " group by w.topic_id, t.name, t.member_id order by lastRecordedAt desc limit 200")
    List<Map<String, Object>> selectWalkTestSummary();

    @Select("select v.topic_id as topicId, v.waived_by as waivedBy, v.note, v.create_time as createTime"
            + " from topic_walk_test_waiver v where v.topic_id = #{topicId}")
    Map<String, Object> selectWaiver(@Param("topicId") Long topicId);

    @Insert("insert into topic_walk_test_waiver(topic_id, waived_by, note, create_time) values(#{topicId}, #{by}, #{note}, now())"
            + " on duplicate key update waived_by = values(waived_by), note = values(note), create_time = now()")
    int upsertWaiver(@Param("topicId") Long topicId, @Param("by") String by, @Param("note") String note);

    @Delete("delete from topic_walk_test_waiver where topic_id = #{topicId}")
    int deleteWaiver(@Param("topicId") Long topicId);

    @Select("select n.id, n.latitude as lat, n.longitude as lng from cms_topic_node n where n.topic_id = #{topicId} and n.status = 1"
            + " and n.latitude is not null and n.longitude is not null")
    List<Map<String, Object>> selectNodePoints(@Param("topicId") Long topicId);

    @Select("select count(*) from cms_topic_node n where n.topic_id = #{topicId} and n.status = 1")
    int countEnabledNodes(@Param("topicId") Long topicId);
}
