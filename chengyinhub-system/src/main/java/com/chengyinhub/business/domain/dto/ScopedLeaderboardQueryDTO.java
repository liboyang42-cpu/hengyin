package com.chengyinhub.business.domain.dto;

/**
 * BE-14 多作用域榜单只读查询。
 *
 * <p>作用域与来源都是既有事实，不引入新的对局或分数表：
 * <ul>
 *   <li>{@code world} = 不限内容范围；</li>
 *   <li>{@code country} = {@code scopeId} 是 ISO 3166-1 alpha-2，按该局冻结快照
 *       {@code themeConfig.contentGeography.countryCode} 匹配；没有冻结值的旧局 UNKNOWN 排除；</li>
 *   <li>{@code city} = {@code scopeId} 是冻结的城市级 adcode，按该局冻结快照
 *       {@code themeConfig.contentGeography.cityCode} 匹配；不读主题当前 city_code 补历史；</li>
 *   <li>{@code quest} = {@code scopeId} 为主题 id；</li>
 *   <li>{@code club} = {@code scopeId} 为俱乐部 id，只统计该俱乐部授权成员在其归属主题上的对局。</li>
 * </ul>
 *
 * <p>{@code period=week} 按服务器时区切周（{@code Asia/Shanghai} 周一 00:00，
 * 与 {@code ClubMemberServiceImpl#currentWeekStart} 同口径），历史周不被改写；
 * {@code period=all} 为累计。
 */
public class ScopedLeaderboardQueryDTO {
    private String scope;
    private String scopeId;
    private String period;
    private Integer limit;

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
}
