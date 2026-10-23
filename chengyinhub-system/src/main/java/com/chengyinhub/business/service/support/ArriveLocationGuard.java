package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.chengyinhub.business.mapper.PlayArriveGuardLogMapper;
import com.chengyinhub.business.service.support.ArriveLocationGuardPolicy.Decision;
import com.chengyinhub.business.service.support.ArriveLocationGuardPolicy.Fix;
import com.chengyinhub.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * O-01 /api/play/arrive 位置守卫(第 4–7 层,判定见 {@link ArriveLocationGuardPolicy})。
 *
 * <p>sys_config 开关 {@value #CONFIG_MODE}(键缺失、读取异常按 off):off(默认,不读不写守卫表,
 * 与改动前逐字等价)/ shadow(照常放行,只把「本应拒绝」记成 SHADOW 供评估误伤)/ enforce(拒绝并记 REJECT)。
 *
 * <p>空精度:原「空精度放行」与开关 play.arrive.require_accuracy 已删除(owner 2026-10-23 拍板,
 * 尚无真实用户,不设过渡期)。控制器对未上报 / 非法精度一律拒绝({@link #ACCURACY_MISSING} /
 * {@link #ACCURACY_INVALID}),不进入本守卫。
 *
 * <p>守卫表不可用(迁移未落库、库抖动)时一律按放行处理并打 error 日志:新闸的故障不能把
 * 全部 GPS 到达拦死。被拒行的 id 作为申诉引用号返回给端上。
 */
@Component
public class ArriveLocationGuard
{
    public static final String CONFIG_MODE = "play.arrive.location_guard.mode";
    /** 未上报精度被拒时的错误码(不是作弊判定,不可申诉,重新定位或扫码即可) */
    public static final String ACCURACY_MISSING = "ARRIVE_ACCURACY_MISSING";
    /** 上报精度非有限 / 负数 / 坏文本被拒时的错误码(同上,不可申诉) */
    public static final String ACCURACY_INVALID = "ARRIVE_ACCURACY_INVALID";
    /** 申诉流引用:audit_appeal.biz_type 取此值,biz_id 取 play_arrive_guard_log.id */
    public static final String APPEAL_BIZ_TYPE = "play_arrive_guard";

    static final String VERDICT_PASS = "PASS";
    static final String VERDICT_REJECT = "REJECT";
    static final String VERDICT_SHADOW = "SHADOW";
    /** 窗口查询行数上限:节流 5s ⇒ 60 分钟最多 720 次评估,放行行远少于此 */
    static final int WINDOW_ROW_LIMIT = 800;

    enum Mode { OFF, SHADOW, ENFORCE }

    /** errorCode 为 null = 放行。 */
    public record Result(String errorCode, String message, Long guardLogId, boolean appealable)
    {
        public boolean rejected()
        {
            return errorCode != null;
        }
    }

    static final Result PASS = new Result(null, null, null, false);

    private static final Logger logger = LoggerFactory.getLogger(ArriveLocationGuard.class);

    @Autowired
    private ISysConfigService configService;

    @Autowired
    private PlayArriveGuardLogMapper guardLogMapper;

    Mode mode()
    {
        try {
            String value = configService.selectConfigByKey(CONFIG_MODE);
            if (value == null) return Mode.OFF;
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "enforce": return Mode.ENFORCE;
                case "shadow": return Mode.SHADOW;
                default: return Mode.OFF;
            }
        } catch (RuntimeException e) {
            logger.error("arrive 位置守卫开关读取失败,按 off 处理", e);
            return Mode.OFF;
        }
    }

    long nowMillis()
    {
        return System.currentTimeMillis();
    }

    /**
     * 第 1–3 层(坐标、精度、围栏)已通过后调用。nodeLat/nodeLng 为本次判定用的站点坐标,
     * accuracyM 为已校验过的上报精度(控制器已拒绝空精度;历史行可能为 null)。
     */
    public Result check(Long memberId, Long activityId, Long topicId, Long nodeId,
                        double nodeLat, double nodeLng, Double accuracyM)
    {
        Mode mode = mode();
        if (mode == Mode.OFF) return PASS;
        try {
            long now = nowMillis();
            Fix current = new Fix(nodeLat, nodeLng, accuracyM, now);
            List<PlayArriveGuardLog> recent = guardLogMapper.selectAcceptedSince(memberId,
                    new Date(now - ArriveLocationGuardPolicy.WINDOW_MILLIS), WINDOW_ROW_LIMIT);
            List<Fix> window = new ArrayList<>();
            if (recent != null) {
                for (PlayArriveGuardLog row : recent) {
                    Fix f = fixOf(row);
                    if (f != null) window.add(f);
                }
                Collections.reverse(window);
            }
            Decision decision = ArriveLocationGuardPolicy.evaluate(current,
                    fixOf(guardLogMapper.selectLastEvaluated(memberId)),
                    fixOf(guardLogMapper.selectLastAccepted(memberId)), window);

            PlayArriveGuardLog row = new PlayArriveGuardLog();
            row.setMemberId(memberId);
            row.setActivityId(activityId);
            row.setTopicId(topicId);
            row.setNodeId(nodeId);
            row.setNodeLat(BigDecimal.valueOf(nodeLat));
            row.setNodeLng(BigDecimal.valueOf(nodeLng));
            row.setAccuracyM(accuracyM == null ? null : BigDecimal.valueOf(accuracyM));
            row.setVerdict(decision.pass() ? VERDICT_PASS : mode == Mode.ENFORCE ? VERDICT_REJECT : VERDICT_SHADOW);
            row.setReasonCode(decision.reasonCode());
            row.setDetail(decision.detail());
            row.setCreatedAt(new Date(now));
            guardLogMapper.insertGuardLog(row);

            if (decision.pass()) return PASS;
            if (mode == Mode.SHADOW) {
                logger.warn("arrive 位置守卫(shadow)本应拒绝 memberId={} nodeId={} reason={} detail={}",
                        memberId, nodeId, decision.reasonCode(), decision.detail());
                return PASS;
            }
            boolean throttle = ArriveLocationGuardPolicy.THROTTLE.equals(decision.reasonCode());
            return new Result(decision.reasonCode(), messageOf(decision.reasonCode()), row.getId(), !throttle);
        } catch (RuntimeException e) {
            logger.error("arrive 位置守卫不可用,按放行处理 memberId={} nodeId={}", memberId, nodeId, e);
            return PASS;
        }
    }

    static String messageOf(String reasonCode)
    {
        if (ArriveLocationGuardPolicy.THROTTLE.equals(reasonCode)) return "操作太频繁，请稍等几秒再试";
        if (ArriveLocationGuardPolicy.SPEED.equals(reasonCode)) return "移动速度异常，本次到达未记录；如有误判可申诉";
        return "短时间内移动距离异常，本次到达未记录；如有误判可申诉";
    }

    private static Fix fixOf(PlayArriveGuardLog row)
    {
        if (row == null || row.getNodeLat() == null || row.getNodeLng() == null || row.getCreatedAt() == null) {
            return null;
        }
        Double acc = row.getAccuracyM() == null ? null : row.getAccuracyM().doubleValue();
        return new Fix(row.getNodeLat().doubleValue(), row.getNodeLng().doubleValue(), acc,
                row.getCreatedAt().getTime());
    }
}
