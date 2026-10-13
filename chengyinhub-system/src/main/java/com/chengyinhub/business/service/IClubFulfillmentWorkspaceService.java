package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ClubFulfillmentWorkspaceDTO;

/** 俱乐部带队履约工作区的只读聚合。 */
public interface IClubFulfillmentWorkspaceService {

    ClubFulfillmentWorkspaceDTO getFulfillment(Long clubId);
}
