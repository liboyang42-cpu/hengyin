package com.chengyinhub.business.domain.community;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * community_post_command_receipt.command_type 的登记表(C:U-037)。
 *
 * <p>列本身是 VARCHAR(80) 自由字符串;写入前由 CommunityPostServiceImpl 用 {@link #isKnown} 把关,
 * 未登记的命令类型直接拒写,不再靠各调用点手写字面量"碰巧一致"。三种形状:
 * <ul>
 *   <li>固定命令:{@link #FIXED}</li>
 *   <li>互动命令:{@code POST_ACTION_<互动类型>} / {@code POST_UNACTION_<互动类型>}</li>
 *   <li>带参数指纹的治理命令:{@code <前缀>:<32 位 sha256 十六进制>},前缀见 {@link #FINGERPRINTED_PREFIXES}</li>
 * </ul>
 */
public final class CommunityCommandType {
    public static final String POST_PUBLISH = "POST_PUBLISH";
    public static final String POST_DELETE = "POST_DELETE";
    public static final String POST_SHARE = "POST_SHARE";
    public static final String LOCATION_WITHDRAW = "LOCATION_WITHDRAW";
    public static final String COPYRIGHT_REMOVE = "COPYRIGHT_REMOVE";
    public static final String COMMENT_APPROVE = "COMMENT_APPROVE";
    public static final String COMMENT_DELETE = "COMMENT_DELETE";
    public static final String COMMENT_UNLIKE = "COMMENT_UNLIKE";
    public static final String MEMBER_UNMUTE = "MEMBER_UNMUTE";
    public static final String NOTIFICATION_READ = "NOTIFICATION_READ";

    public static final String POST_ACTION_PREFIX = "POST_ACTION_";
    public static final String POST_UNACTION_PREFIX = "POST_UNACTION_";

    public static final String POST_FEATURE = "POST_FEATURE";
    public static final String POST_ENFORCE = "POST_ENFORCE";
    public static final String COMMENT_ENFORCE = "COMMENT_ENFORCE";

    public static final Set<String> FIXED = Collections.unmodifiableSet(new LinkedHashSet<>(java.util.Arrays.asList(
            POST_PUBLISH, POST_DELETE, POST_SHARE, LOCATION_WITHDRAW, COPYRIGHT_REMOVE,
            COMMENT_APPROVE, COMMENT_DELETE, COMMENT_UNLIKE, MEMBER_UNMUTE, NOTIFICATION_READ)));

    public static final Set<String> FINGERPRINTED_PREFIXES = Collections.unmodifiableSet(new LinkedHashSet<>(
            java.util.Arrays.asList(POST_FEATURE, POST_ENFORCE, COMMENT_ENFORCE)));

    private static final Pattern ACTION = Pattern.compile("^POST_(UN)?ACTION_[A-Z][A-Z_]*$");
    private static final Pattern FINGERPRINTED = Pattern.compile("^(POST_FEATURE|POST_ENFORCE|COMMENT_ENFORCE):[0-9a-f]{32}$");

    /** 列宽 VARCHAR(80)。 */
    public static final int MAX_LENGTH = 80;

    private CommunityCommandType() {}

    public static boolean isKnown(String commandType) {
        if (commandType == null || commandType.isEmpty() || commandType.length() > MAX_LENGTH) return false;
        return FIXED.contains(commandType)
                || ACTION.matcher(commandType).matches()
                || FINGERPRINTED.matcher(commandType).matches();
    }
}
