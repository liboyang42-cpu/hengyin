package com.chengyinhub.business.moneyswitch.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
