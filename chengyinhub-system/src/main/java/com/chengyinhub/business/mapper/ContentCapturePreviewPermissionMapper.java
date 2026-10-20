package com.chengyinhub.business.mapper;

import org.apache.ibatis.annotations.Param;
import java.util.List;

/** Current platform authority only; no member identity joins or caller-supplied owner flags. */
public interface ContentCapturePreviewPermissionMapper {
    Long lockActiveUser(@Param("userId") long userId);
    List<String> lockPermissions(@Param("userId") long userId);
}
