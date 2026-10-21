package com.chengyinhub.business.citygame.analytics.fairness.source;

import java.util.Date;

/** Columns that exist on game_player_node_outcome. Scope fields stay null until a real join writes them. */
public class FairnessCompletionRow {
    public Long id;
    public Long gameSessionId;
    public Long memberId;
    public String status;
    public String completionSource;
    public Date completedAt;
    public String boardId;
    public String regionId;
    public String seasonId;
    public String rulesVersion;
}
