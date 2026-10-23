package com.chengyinhub.business.service.minor;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 城市定向（F1）调用未成年挂点时给出的场次事实。时间为北京时间本地时刻。
 *
 * @param lowRiskRoute    线路是否被标为低风险（由调用方按线路配置判定）
 * @param start           计划开始
 * @param plannedEnd      计划结束（未知则传 null，会按无法确认白天拒绝）
 * @param teamMemberIds   同队成员会员ID（可含本人）
 */
public record OrienteeringParticipation(boolean lowRiskRoute, LocalDateTime start, LocalDateTime plannedEnd,
                                        Collection<Long> teamMemberIds) { }
