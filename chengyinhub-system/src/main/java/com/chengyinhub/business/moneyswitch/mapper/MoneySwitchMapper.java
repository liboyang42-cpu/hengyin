package com.chengyinhub.business.moneyswitch.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * Money switch rows ({@code cy_money_switch}) and their append-only audit ({@code cy_money_switch_audit}).
 * A missing row means the area is off. See {@code sql/migration_20261115_money_switch.sql}.
 */
public interface MoneySwitchMapper {

    @Select("select area, enabled, update_by, update_time from cy_money_switch")
    List<Map<String, Object>> selectAll();

    @Select("select enabled from cy_money_switch where area = #{area} for update")
    Integer selectEnabledForUpdate(@Param("area") String area);

    @Insert("insert into cy_money_switch (area, enabled, update_by, update_time) values (#{area}, #{enabled}, #{by}, now()) "
            + "on duplicate key update enabled = values(enabled), update_by = values(update_by), update_time = values(update_time)")
    int upsert(@Param("area") String area, @Param("enabled") int enabled, @Param("by") String by);

    @Insert("insert into cy_money_switch_audit (area, old_enabled, new_enabled, changed_by, reason, changed_at) "
            + "values (#{area}, #{oldEnabled}, #{newEnabled}, #{by}, #{reason}, now())")
    int insertAudit(@Param("area") String area, @Param("oldEnabled") int oldEnabled, @Param("newEnabled") int newEnabled,
                    @Param("by") String by, @Param("reason") String reason);

    @Select("select id, area, old_enabled, new_enabled, changed_by, reason, changed_at from cy_money_switch_audit "
            + "order by id desc limit #{limit}")
    List<Map<String, Object>> selectAudit(@Param("limit") int limit);

    // ---------------------------------------------------------------- W7 MERCHANT-15 two-person opening

    String REQUEST_COLUMNS = "id, area, status, requested_by as requestedBy, request_reason as requestReason, "
            + "decided_by as decidedBy, decide_reason as decideReason, create_time as createTime, decide_time as decideTime";

    @Insert("insert into cy_money_switch_request (area, status, requested_by, request_reason, create_time) "
            + "values (#{area}, 'PENDING', #{by}, #{reason}, now())")
    int insertRequest(@Param("area") String area, @Param("by") String by, @Param("reason") String reason);

    /** The open request for an area, if one is younger than {@code hours}. */
    @Select("select " + REQUEST_COLUMNS + " from cy_money_switch_request where area = #{area} and status = 'PENDING' "
            + "and create_time > date_sub(now(), interval #{hours} hour) order by id desc limit 1")
    Map<String, Object> selectPendingRequest(@Param("area") String area, @Param("hours") int hours);

    /** Includes {@code expired}: 1 when the request is older than {@code hours}. */
    @Select("select " + REQUEST_COLUMNS + ", (create_time <= date_sub(now(), interval #{hours} hour)) as expired "
            + "from cy_money_switch_request where id = #{id} for update")
    Map<String, Object> selectRequestForUpdate(@Param("id") long id, @Param("hours") int hours);

    @Update("update cy_money_switch_request set status = #{status}, decided_by = #{by}, decide_reason = #{reason}, "
            + "decide_time = now() where id = #{id} and status = 'PENDING'")
    int decideRequest(@Param("id") long id, @Param("status") String status, @Param("by") String by,
                      @Param("reason") String reason);

    @Select("select " + REQUEST_COLUMNS + " from cy_money_switch_request order by id desc limit #{limit}")
    List<Map<String, Object>> selectRequests(@Param("limit") int limit);
}
