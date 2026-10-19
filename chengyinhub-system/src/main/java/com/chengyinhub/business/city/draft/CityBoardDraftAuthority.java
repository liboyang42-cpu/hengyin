package com.chengyinhub.business.city.draft;

import java.util.Map;
import java.util.Set;

/** Server-only seam. No production implementation or permission is supplied by this slice. */
public interface CityBoardDraftAuthority {
    Context requireCurrentPlatformEditor();
    Map<String, Object> catalog(Context context);

    final class Context {
        public final String actorId;
        public final Set<String> regionIds;
        public final boolean platformEditor;
        public Context(String actorId, Set<String> regionIds, boolean platformEditor) {
            this.actorId = actorId;
            this.regionIds = java.util.Collections.unmodifiableSet(new java.util.HashSet<>(regionIds));
            this.platformEditor = platformEditor;
        }
    }
}
