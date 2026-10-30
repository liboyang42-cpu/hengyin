package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsTopic;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.Date;
import org.springframework.beans.BeanUtils;

/** Author save readback retains time; public CmsTopic continues to expose calendar dates. */
public final class TopicAuthorEditDTO extends CmsTopic {
    private static final long serialVersionUID = 1L;

    public TopicAuthorEditDTO(CmsTopic topic) {
        BeanUtils.copyProperties(topic, this);
    }

    @Override
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    public Date getStartDate() {
        return super.getStartDate();
    }

    @Override
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    public Date getEndDate() {
        return super.getEndDate();
    }
}
