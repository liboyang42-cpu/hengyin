package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.AiThemeDraftRequest;
import org.apache.ibatis.annotations.Param;

public interface AiThemeDraftRequestMapper {
    AiThemeDraftRequest selectByUserAndRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);
    int insertRequest(AiThemeDraftRequest request);
    int finishRequest(AiThemeDraftRequest request);
}
