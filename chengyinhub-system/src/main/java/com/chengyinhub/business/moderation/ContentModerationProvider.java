package com.chengyinhub.business.moderation;

/**
 * One machine moderation provider behind {@code IContentSafetyGate} (SEC-12).
 *
 * <p>The WeChat mini-program check needs the author's mini-program openid; phone and Apple accounts have none.
 * {@link ModerationRouter} picks WeChat for authors with an openid and a provider that needs no openid otherwise.
 * The local keyword pre-filter ({@code ISensitiveContentLibrary}) always runs before any provider.
 */
public interface ContentModerationProvider {

    /** Short stable name for logs and audit snapshots, e.g. {@code WECHAT}, {@code TENCENT_CLOUD}. */
    String name();

    /** Whether this provider can judge text written by an author with this openid (may be blank). */
    boolean canJudge(String openid);

    /** Whether {@link #checkImage} answers synchronously. Asynchronous providers return UNAVAILABLE there. */
    boolean judgesImagesSynchronously();

    ModerationVerdict checkText(String text, String openid);

    ModerationVerdict checkImage(String imageUrl, String openid);
}
