package com.chengyinhub.business.telemetry.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * P6 measurement:匿名安装事件(app_install_event)、安装号↔账号关联(app_install_link)、
 * MetricKit 载荷(app_metric_payload)。表见 migration_20261115_measurement.sql。
 */
public interface InstallTelemetryMapper {

    /** 同一安装号下事件编号唯一,重传幂等。 */
    @Insert("insert ignore into app_install_event (install_id, event_id, event_name, props_json, platform, "
            + "app_version, occurred_at, create_time) values (#{installId}, #{eventId}, #{eventName}, #{propsJson}, "
            + "#{platform}, #{appVersion}, #{occurredAt}, now())")
    int insertEvent(@Param("installId") String installId, @Param("eventId") String eventId,
                    @Param("eventName") String eventName, @Param("propsJson") String propsJson,
                    @Param("platform") String platform, @Param("appVersion") String appVersion,
                    @Param("occurredAt") Date occurredAt);

    /** 一台设备可先后登录多个账号:每对 (安装号, 账号) 只记一次。 */
    @Insert("insert ignore into app_install_link (install_id, member_id, linked_at) "
            + "values (#{installId}, #{memberId}, now())")
    int insertLink(@Param("installId") String installId, @Param("memberId") long memberId);

    @Select("select count(*) from app_metric_payload where install_id = #{installId} and create_time >= #{since}")
    int countMetricPayloadsSince(@Param("installId") String installId, @Param("since") Date since);

    @Insert("insert into app_metric_payload (install_id, payload_kind, app_version, os_version, payload_json, "
            + "payload_bytes, create_time) values (#{installId}, #{kind}, #{appVersion}, #{osVersion}, "
            + "#{payloadJson}, #{payloadBytes}, now())")
    int insertMetricPayload(@Param("installId") String installId, @Param("kind") String kind,
                            @Param("appVersion") String appVersion, @Param("osVersion") String osVersion,
                            @Param("payloadJson") String payloadJson, @Param("payloadBytes") int payloadBytes);

    /** 管理端登录前漏斗:按事件统计去重安装数与事件数;窗口由调用方限定。 */
    @Select("select event_name as eventName, count(distinct install_id) as installCount, count(*) as eventCount "
            + "from app_install_event where occurred_at >= #{start} and occurred_at < #{endExclusive} "
            + "group by event_name")
    List<Map<String, Object>> selectEventCounts(@Param("start") Date start, @Param("endExclusive") Date endExclusive);

    /** 窗口内完成「安装号 → 账号」关联的去重安装数。 */
    @Select("select count(distinct l.install_id) from app_install_link l where l.linked_at >= #{start} "
            + "and l.linked_at < #{endExclusive}")
    long countLinkedInstalls(@Param("start") Date start, @Param("endExclusive") Date endExclusive);
}
