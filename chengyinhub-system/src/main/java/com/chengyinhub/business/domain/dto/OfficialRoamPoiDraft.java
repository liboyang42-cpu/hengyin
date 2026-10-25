package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.RoamPoi;

/** 官方据点编辑请求；所有归属与上架字段由服务端决定。 */
public class OfficialRoamPoiDraft {
    public RoamPoi poi;
    public CmsMemberTemplate game;
}
