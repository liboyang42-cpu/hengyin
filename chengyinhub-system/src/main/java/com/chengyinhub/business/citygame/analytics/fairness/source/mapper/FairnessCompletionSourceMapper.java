package com.chengyinhub.business.citygame.analytics.fairness.source.mapper;

import com.chengyinhub.business.citygame.analytics.fairness.source.FairnessCompletionRow;
import org.apache.ibatis.annotations.Param;

/** Read-only. Scanned with the other mappers. Does not write game_player_node_outcome. */
public interface FairnessCompletionSourceMapper {
    FairnessCompletionRow selectById(@Param("id") long id);
}
