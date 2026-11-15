package com.chengyinhub.business.growthloops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** GRO-13 邀请奖励的判定（纯函数）。 */
public final class InviteRewardPolicy
{
    /** 结果：GRANTED / REFUSED 落账本；WAIT 不落账，下次可信漫游再判。NONE = 不是被邀请人或已处理。 */
    public enum Outcome { NONE, WAIT, GRANTED, REFUSED }

    public record Decision(Outcome outcome, String reason) {
        static Decision none(String reason) { return new Decision(Outcome.NONE, reason); }
        static Decision waitFor(String reason) { return new Decision(Outcome.WAIT, reason); }
        static Decision refuse(String reason) { return new Decision(Outcome.REFUSED, reason); }
        static Decision grant() { return new Decision(Outcome.GRANTED, null); }
    }

    private InviteRewardPolicy() {}

    /** invite_user_id 是自由文本列：空、非数字、≤0、等于本人一律视为没有邀请人。 */
    public static Long inviterOf(Object raw, long inviteeId)
    {
        if (raw == null) return null;
        try {
            long id = Long.parseLong(raw.toString().trim());
            return id > 0 && id != inviteeId ? id : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * 本次漫游的位置记录是否可信：至少一条干净的定位，且没有被拒、不计奖或模拟定位。
     * 没有任何定位记录（老客户端）不算通过，等下一次。
     */
    public static boolean locationTrusted(long clean, long flagged)
    {
        return clean > 0 && flagged == 0;
    }

    /** 手机号只保留数字，去掉 86 国家码，再取 SHA-256；空号返回 null。 */
    public static String phoneHash(Object phone)
    {
        if (phone == null) return null;
        String digits = phone.toString().replaceAll("\\D", "");
        if (digits.length() == 13 && digits.startsWith("86")) digits = digits.substring(2);
        return digits.length() < 6 ? null : sha256("phone:" + digits);
    }

    public static String deviceHash(String token)
    {
        if (token == null || token.isBlank()) return null;
        return sha256("device:" + token.trim().toLowerCase(java.util.Locale.ROOT));
    }

    static String sha256(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
