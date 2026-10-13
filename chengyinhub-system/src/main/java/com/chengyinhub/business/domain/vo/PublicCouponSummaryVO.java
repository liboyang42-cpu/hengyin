package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.SmsCoupon;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 玩家端可见的优惠券摘要 —— 只回答「这张券是什么、值多少、什么时候能用」。
 *
 * ⚠️ 刻意不投影的字段,别顺手加回来:
 *   · code           —— 兑换码。任何人拿到就能领,等于把券白送。
 *   · memberId       —— 定向发放的归属人,是别人的 PII。
 *   · publishCount / useCount / receiveCount —— 发行量与核销量,商家经营数据。
 *   · status / delFlag —— 内部状态位;from() 只用它们判定是否可见,不向外透出。
 *
 * from() 对「下架 / 已删除」直接返回 null:券没了就整块不渲染,不给玩家一个点不动的空壳。
 */
public class PublicCouponSummaryVO {
    private Long id;
    private String name;
    /** 类型语义沿用 SmsCoupon.couponType；客户端解释 amount 时必须与它配套读取。 */
    private Integer couponType;
    /** 金额 / 折扣系数 */
    private BigDecimal amount;
    private String description;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date startTime;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private Date endTime;

    public static PublicCouponSummaryVO from(SmsCoupon source) {
        if (source == null) return null;
        // status:0未开始 / 1进行中 / 2已结束 / 3手动失效(见 SmsCoupon 字段注释)。
        // ⚠️ 只藏 2 和 3。「未开始」必须照常展示 —— 主题介绍说的是「走完能拿到什么」,
        //    活动往往先上架、券后生效,把 0 也藏掉会让奖励在开赛前凭空消失。
        // 4=商家停发:玩家已无法再获得这张券,「走完能拿到什么」里继续挂它就是空头支票。
        Integer status = source.getStatus();
        if (status != null && (status == 2 || status == 3 || status == 4)) return null;
        // delFlag:0 存在 / 2 删除
        if (source.getDelFlag() != null && source.getDelFlag() != 0) return null;
        PublicCouponSummaryVO out = new PublicCouponSummaryVO();
        out.setId(source.getId());
        out.setName(source.getName());
        out.setCouponType(source.getCouponType());
        out.setAmount(source.getAmount());
        out.setDescription(source.getDescription());
        out.setStartTime(source.getStartTime());
        out.setEndTime(source.getEndTime());
        return out;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCouponType() { return couponType; }
    public void setCouponType(Integer couponType) { this.couponType = couponType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }
    public Date getEndTime() { return endTime; }
    public void setEndTime(Date endTime) { this.endTime = endTime; }
}
