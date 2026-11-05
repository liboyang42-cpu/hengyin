package com.chengyinhub.business.service.channel.foundation;

/**
 * Validated channel metadata, not an authorization decision or persistence layer.
 * Shared ID and fields follow the channel specification. Voice/stage are excluded
 * by the newer App specification. Visibility is only a list prefilter.
 */
public record ChannelDefinition(long id, int guildKind, long guildId, long parentId,
        Kind kind, String name, String topic, int position, boolean isDefault,
        Status status, int visibility, int slowmodeSeconds, boolean botEnabled,
        int feedSource, long feedRef, int permissionVersion, Long legacyConversationId,
        boolean deleted) {
    public static final int REQUIRED_CONVERSATION_TYPE = 5;

    public enum Kind {
        CATEGORY(0), TEXT(1), ANNOUNCEMENT(2), TOPIC(3), DIRECT(6), SYSTEM(7), SUPPORT(8);
        private final int code;
        Kind(int code) { this.code = code; }
        public int code() { return code; }
        public boolean hasGuild() { return code <= 3; }
        public static Kind fromCode(int code) {
            for (Kind kind : values()) if (kind.code == code) return kind;
            throw new IllegalArgumentException("Unsupported channel kind");
        }
    }

    public enum Status {
        ACTIVE(0), ARCHIVED(1), BANNED(2), PENDING_DELETE(3), DELETED(4);
        private final int code;
        Status(int code) { this.code = code; }
        public int code() { return code; }
    }

    public ChannelDefinition {
        require(id > 0, "Shared conversation ID must be positive");
        require(kind != null && status != null, "Kind and status are required");
        require(parentId >= 0 && parentId != id, "Invalid parent ID");
        if (kind.hasGuild()) {
            require(guildKind >= 1 && guildKind <= 7 && guildId > 0, "Guild is required");
        } else {
            require(guildKind == 0 && guildId == 0, "Direct/system/support has no guild");
            require(parentId == 0, "Non-guild channel has no category");
        }
        if (legacyConversationId == null && kind.hasGuild()) name = normalizeNewName(name);
        require(name != null && !name.isBlank() && codePoints(name) <= 64, "Invalid name");
        require(topic == null || codePoints(topic) <= 120, "Topic is too long");
        require(!isDefault || (kind == Kind.TEXT && position == 0), "Default must be text at position zero");
        require(visibility == 1 || visibility == 2, "Invalid visibility projection");
        require(slowmodeSeconds >= 0 && slowmodeSeconds <= 21600, "Invalid slowmode");
        require((feedSource == 0 || feedSource == 1) && feedRef == 0
                || feedSource == 2 && feedRef > 0, "Invalid feed reference");
        require(permissionVersion >= 0, "Invalid permission version");
        require(legacyConversationId == null || legacyConversationId == id,
                "Migration must preserve conversation ID");
    }

    /** Call with the conversation row read within the same transaction. No IDs are allocated here. */
    public void requireConversation(long conversationId, int conversationType) {
        int expected = REQUIRED_CONVERSATION_TYPE;
        if (legacyConversationId != null) {
            expected = switch (kind) {
                case DIRECT -> 1;
                case SYSTEM -> 2;
                case SUPPORT -> 3;
                case TEXT -> 4;
                default -> throw new IllegalArgumentException("Unsupported legacy channel kind");
            };
        }
        require(conversationId == id && conversationType == expected, "Channel/conversation mismatch");
    }

    /** Parent must be loaded in the same authoritative snapshot as this channel. */
    public void requireParent(ChannelDefinition parent) {
        if (parentId == 0) {
            require(parent == null, "Top-level channel has no parent");
            return;
        }
        require(parent != null && parent.id == parentId && parent.kind == Kind.CATEGORY
                && parent.guildKind == guildKind && parent.guildId == guildId && parent.lifecycleAllowsHistory(),
                "Parent must be a live category in the same guild");
    }

    /** Only a lifecycle restriction: true never grants VIEW or history/search access. */
    public boolean lifecycleAllowsHistory() { return !deleted && (status == Status.ACTIVE || status == Status.ARCHIVED); }

    /** Only a lifecycle restriction: announcement/system/member permissions must still be checked. */
    public boolean lifecycleAllowsMessageWrite() {
        return !deleted && status == Status.ACTIVE && kind != Kind.CATEGORY;
    }

    public static String normalizeNewName(String input) {
        require(input != null, "Name is required");
        String normalized = input.strip();
        require(!normalized.isEmpty() && codePoints(normalized) <= 64
                && normalized.indexOf('/') < 0 && normalized.indexOf('#') < 0, "Invalid channel name");
        return normalized;
    }

    private static int codePoints(String value) { return value.codePointCount(0, value.length()); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
