package com.chengyinhub.business.domain.dto;

import com.chengyinhub.business.domain.ClubPost;

/** 动态编辑结果；mutated 仅表示本次请求实际生成了新修订。 */
public final class ClubPostUpdateResult {
    private final ClubPost post;
    private final boolean mutated;

    public ClubPostUpdateResult(ClubPost post, boolean mutated) {
        this.post = post;
        this.mutated = mutated;
    }

    public ClubPost getPost() {
        return post;
    }

    public boolean isMutated() {
        return mutated;
    }
}
