package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TrackDraft;

import java.util.List;

/**
 * M8 轨迹草稿 Mapper。
 */
public interface TrackDraftMapper {
    int insertTrackDraft(TrackDraft draft);
    TrackDraft selectTrackDraftById(Long id);
    List<TrackDraft> selectTrackDraftList(TrackDraft draft);
    int updateTrackDraft(TrackDraft draft);
}
