package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import org.springframework.stereotype.Component;

import java.util.Date;

/** 集合签到只认服务端场次时间、集合点；输入坐标不作为已认证的设备证明。 */
@Component
public class ClubGatherArrivalPolicy {
    public String validate(CmsActivity activity, Double latitude, Double longitude, Date now) {
        if (activity == null || activity.getClubId() == null || activity.getClubId() <= 0) return "该场次不是带领场";
        if (!Integer.valueOf(0).equals(activity.getDelFlag()) || activity.getCancelTime() != null) return "该场次已取消或删除";
        if (now == null || activity.getStartDate() == null || activity.getStartDate().getTime() <= 0) return "集合时间未配置，请联系领队";
        if (activity.getEndDate() != null && !activity.getEndDate().after(activity.getStartDate())) return "场次时间配置不完整，请联系领队";
        if (activity.getEndDate() != null && !now.before(activity.getEndDate())) return "该场次已结束";
        if (activity.getStartDate().getTime() - now.getTime() > 60L * 60L * 1000L) return "集合前60分钟才可签到";
        Double targetLat = coordinate(activity.getLatitude(), 90);
        Double targetLng = coordinate(activity.getLongitude(), 180);
        if (targetLat == null || targetLng == null) return "集合点未配置，请联系领队";
        if (!valid(latitude, 90) || !valid(longitude, 180)) return "当前位置无效，请重新定位";
        // 发现列表的距离工具会按10米舍入；签到硬边界必须使用未舍入的米数。
        double latDelta = Math.toRadians(latitude - targetLat);
        double lngDelta = Math.toRadians(longitude - targetLng);
        double a = Math.pow(Math.sin(latDelta / 2), 2) + Math.cos(Math.toRadians(targetLat))
                * Math.cos(Math.toRadians(latitude)) * Math.pow(Math.sin(lngDelta / 2), 2);
        double meters = 6371000D * 2D * Math.asin(Math.sqrt(Math.min(1D, Math.max(0D, a))));
        return meters <= 500D ? null : "请到集合点500米内签到";
    }

    private Double coordinate(String value, double max) {
        if (value == null || value.trim().isEmpty()) return null;
        try { Double parsed = Double.valueOf(value); return valid(parsed, max) ? parsed : null; }
        catch (NumberFormatException invalid) { return null; }
    }

    private boolean valid(Double value, double max) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= max;
    }
}
