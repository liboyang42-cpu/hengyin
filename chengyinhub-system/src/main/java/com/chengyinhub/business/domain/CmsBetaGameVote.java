package com.chengyinhub.business.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.chengyinhub.common.annotation.Excel;
import com.chengyinhub.common.core.domain.BaseEntity;

import java.util.Date;

/**
 * 游戏调查投票对象 cms_beta_game_vote
 * 
 * @author webxh
 * @date 2025-10-13
 */
public class CmsBetaGameVote extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    private Long id;

    /** 用户ID */
    @Excel(name = "用户ID")
    private Long memberId;

    /** 游戏ID */
    @Excel(name = "游戏ID")
    private Long gameId;

    /** 评分 */
    @Excel(name = "评分")
    private Long star;

    /** 评论内容 */
    @Excel(name = "评论内容")
    private String contents;

    @Override
    public Date getCreateTime() {
        return createTime;
    }

    @Override
    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "评论时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss" )
    private Date createTime;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setMemberId(Long memberId) 
    {
        this.memberId = memberId;
    }

    public Long getMemberId() 
    {
        return memberId;
    }

    public void setGameId(Long gameId) 
    {
        this.gameId = gameId;
    }

    public Long getGameId() 
    {
        return gameId;
    }

    public void setStar(Long star) 
    {
        this.star = star;
    }

    public Long getStar() 
    {
        return star;
    }

    public void setContents(String contents) 
    {
        this.contents = contents;
    }

    public String getContents() 
    {
        return contents;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("memberId", getMemberId())
            .append("gameId", getGameId())
            .append("star", getStar())
            .append("contents", getContents())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .toString();
    }
}
