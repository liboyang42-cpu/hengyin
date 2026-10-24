package com.chengyinhub.business.service.support;

import com.chengyinhub.business.access.merchant.MerchantPermission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doc2 F4 商家看板指标口径字典。
 *
 * <p>只描述<b>已经在下发</b>的指标(既有 {@code /api/merchant/dashboard} 与店铺参谋 insight 块),
 * 字段路径与计算口径逐条对齐实现({@code ApiMerchantController#getDashboard}、
 * {@code MerchantInsightServiceImpl#dashboard / buildCheckinFacts}),不新增任何口径、不造数。
 * 看板改口径时必须同步改这里,{@code MerchantMetricDictionaryTest} 钉着字段路径集合。
 */
public final class MerchantMetricDictionary {

    /** 字典版本:口径变化时 +1,端上据此判断缓存的释义是否过期 */
    public static final int VERSION = 2;

    private static final List<Map<String, Object>> METRICS;

    static {
        List<Map<String, Object>> m = new ArrayList<>();
        m.add(metric("revenue", "累计收入", "元", MerchantPermission.FINANCE_READ,
                "Owner 账户余额流水中 change_type=1(收入)的 change_balance 求和,含全部历史;两位小数。",
                "ums_member_balance_detail", null));
        m.add(metric("revenue7d", "近 7 日收入趋势", "元/天", MerchantPermission.FINANCE_READ,
                "同 revenue 口径,按流水创建日(服务器时区)分桶,含今天共 7 天,缺天补 0。",
                "ums_member_balance_detail", 7));
        m.add(metric("pendingOrders", "待处理订单", "单", MerchantPermission.ORDER_READ,
                "oms_order 中 mms_merchant_id = 本门店、status=1 且未删除的订单数。",
                "oms_order", null));
        m.add(metric("insight.exposure", "曝光", "次", MerchantPermission.MARKETING_READ,
                "近 30 天内,含本商家节点的主题被浏览次数。店铺页/分享暂无埋点,不计入。",
                "主题浏览记录", 30));
        m.add(metric("insight.checkin.total", "到店", "人次", MerchantPermission.MARKETING_READ,
                "近 30 天内创建的已支付报名对应权益事实中，有到店打卡时间的条数；不是按到店时间截取。",
                "商家权益到店/核销事实", 30));
        m.add(metric("insight.checkin.redeemRate", "核销率", "比例", MerchantPermission.MARKETING_READ,
                "近 30 天内创建报名的权益 cohort 中，已核销条数 ÷ 权益事实总条数;分母为 0 时为 null(不显示 0%)。两位小数。",
                "商家权益到店/核销事实", 30));
        m.add(metric("insight.checkin.slotBuckets", "到店时段分布", "人次", MerchantPermission.MARKETING_READ,
                "近 30 天内创建报名对应的到店打卡，按到店小时归入 早/午/晚/夜 四段计数。",
                "商家权益到店/核销事实", 30));
        m.add(metric("insight.checkin.repeatRate", "复购率", "比例", MerchantPermission.MARKETING_READ,
                "近 30 天内创建报名的权益 cohort 中，核销过 ≥2 次的玩家数 ÷ 核销过的玩家数;无核销时为 null。两位小数。",
                "商家权益到店/核销事实", 30));
        m.add(metric("insight.checkin.avgWaitMinutes", "平均等待", "分钟", MerchantPermission.MARKETING_READ,
                "近 30 天内创建报名的同一条权益事实，核销时间 − 到店时间(仅非负差值)的平均,四舍五入到分钟;无样本为 null。",
                "商家权益到店/核销事实", 30));
        m.add(metric("insight.supplyCount", "在架供给", "条", MerchantPermission.MARKETING_READ,
                "本商家当前在架的供给事实条数(不限窗口)。",
                "商家供给事实", null));
        METRICS = Collections.unmodifiableList(m);
    }

    private MerchantMetricDictionary() {
    }

    public static Map<String, Object> view() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", VERSION);
        view.put("source", "/api/merchant/dashboard");
        view.put("metrics", METRICS);
        return view;
    }

    static List<Map<String, Object>> metrics() {
        return METRICS;
    }

    private static Map<String, Object> metric(String path, String name, String unit, String permission,
                                              String definition, String source, Integer windowDays) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("name", name);
        row.put("unit", unit);
        row.put("requiredPermission", permission);
        row.put("definition", definition);
        row.put("source", source);
        row.put("windowDays", windowDays);
        return Collections.unmodifiableMap(row);
    }
}
