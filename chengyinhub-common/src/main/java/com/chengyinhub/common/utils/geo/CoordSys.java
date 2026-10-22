package com.chengyinhub.common.utils.geo;

import java.util.Locale;

/** 客户端上报的坐标系。小程序 wx.getLocation 的 type:'gcj02' 即 GCJ02。 */
public enum CoordSys {
    WGS84, GCJ02, BD09;

    /** 严格解析:空或未知值返回 null,由调用方拒绝(不猜默认坐标系)。 */
    public static CoordSys parse(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "WGS84": case "WGS-84": return WGS84;
            case "GCJ02": case "GCJ-02": return GCJ02;
            case "BD09": case "BD-09": case "BD09LL": return BD09;
            default: return null;
        }
    }
}
