package com.chengyinhub.business.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 路线剪影点 —— 归一化 0~1 相对坐标，无底图无绝对经纬度。
 * 绝对经纬度在服务端丢弃，永不下发。
 *
 * @author chengyinhub
 * @date 2026-07-19
 */
public class RouteShapePoint {

    @Schema(description = "归一化 x (0~1)")
    private double x;

    @Schema(description = "归一化 y (0~1)")
    private double y;

    public RouteShapePoint() {}

    public RouteShapePoint(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }
    public double getY() { return y; }
    public void setY(double y) { this.y = y; }
}
