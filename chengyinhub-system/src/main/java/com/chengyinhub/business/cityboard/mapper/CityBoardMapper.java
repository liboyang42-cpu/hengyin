package com.chengyinhub.business.cityboard.mapper;

import com.chengyinhub.business.cityboard.CityBoardDraftRow;
import com.chengyinhub.business.cityboard.CityBoardOperationRow;
import com.chengyinhub.business.cityboard.CityBoardReleaseRow;

public interface CityBoardMapper {
    CityBoardDraftRow selectDraftById(long id);

    int insertDraft(CityBoardDraftRow row);

    int updateDraft(CityBoardDraftRow row);

    CityBoardReleaseRow selectCurrentRelease(String regionId);

    CityBoardReleaseRow selectReleaseById(long id);

    Long selectMaxRevision(String regionId);

    int clearCurrent(String regionId);

    int insertRelease(CityBoardReleaseRow row);

    CityBoardOperationRow selectOperation(String requestId);

    int insertOperation(CityBoardOperationRow row);
}
