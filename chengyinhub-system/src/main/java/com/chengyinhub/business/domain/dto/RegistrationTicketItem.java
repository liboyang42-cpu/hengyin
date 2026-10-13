package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 多票报名的单项：票种 + 数量 + 参与日期
 */
public class RegistrationTicketItem {

    @Schema(description = "票种ID", example = "1")
    private Long ticketId;

    @Schema(description = "张数", example = "1")
    private Integer count;

    /** 自由定向票必填，经典定向票留空 */
    @Schema(description = "参与日期 yyyy-MM-dd", example = "2025-12-13")
    private String participateDate;

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public String getParticipateDate() {
        return participateDate;
    }

    public void setParticipateDate(String participateDate) {
        this.participateDate = participateDate;
    }
}
