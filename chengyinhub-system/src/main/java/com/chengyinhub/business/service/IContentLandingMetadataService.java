package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ContentLandingMetadata;
import com.chengyinhub.business.domain.dto.ContentLandingMetadataSaveRequest;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationSaveRequest;
import com.chengyinhub.business.domain.vo.ContentLandingMetadataVO;
import com.chengyinhub.business.domain.dto.ContentLandingTranslationReviewRequest;

import java.util.List;

/**
 * M7 落地元信息与公开落地文案（作者写面 + 后台审校写面）。
 *
 * <p><b>写权限分两层</b>：
 * <ul>
 *   <li>作者面：只有 {@code cms_topic.memberId}（该主题发布者本人）可写自己主题的落地元信息与译文，
 *       且<b>只能写 PENDING</b> —— {@code APPROVED} 由作者提交一律 403，作者改已审内容会自动回到 PENDING。</li>
 *   <li>审校面：{@link #reviewTranslation} 只由后台审校端点调用（权限串 {@code business:contentlanding:review}，
 *       本轮不 seed、不授角色，fail-closed，超管按既有机制可用）。审校只翻转状态，不覆盖作者文字。</li>
 * </ul>
 *
 * <p>为什么必须分离：公开目录只信 {@code translation_state='APPROVED'}
 * （{@code PublicContentCatalogMapper}），若作者能自写 APPROVED，就等于无人复核的多语种文案直接对外展示。
 *
 * <p>幂等：自然键(主题 / 主题+语言)加乐观锁版本；重复提交同一 payload 只有在版本未变时才成功，
 * 版本已推进就返回 409 让调用方重读，绝不静默覆盖他人写入。审校另加内容摘要绑定，防「看 A 审 B」。
 *
 * <p>本接口不改任何发布、售卖、价格、退款事实：落地元信息只是「对外怎么介绍」，
 * 不影响主题能否公开、能否购买；主题下架/打回时审校也一律拒绝。
 */
public interface IContentLandingMetadataService {

    ContentLandingMetadataVO readOwn(Long topicId, Long memberId);

    ContentLandingMetadataVO saveMetadata(ContentLandingMetadataSaveRequest request, Long memberId);

    /** 作者写译文：状态恒为 PENDING；请求里带 APPROVED 直接 403。 */
    ContentLandingMetadataVO saveTranslation(ContentLandingTranslationSaveRequest request, Long memberId);

    /** 后台审校只读列表：只含已公开主题；调用方必须先 {@code startPage()}。 */
    List<ContentLandingMetadata> reviewList(String keyword);

    /** 审校台只读详情：已公开主题的落地信息与全部译文（含状态与版本），不改任何行。 */
    ContentLandingMetadataVO reviewDetail(Long topicId);

    /**
     * 审校人当时看到的译文内容摘要（含主题原文修订时刻）。
     * 审校台渲染前调用一次，提交时原样回传，服务端重算比对。
     */
    String reviewDigest(Long topicId, String locale);

    /**
     * 审校翻转：PENDING→APPROVED 或 APPROVED→PENDING。只改状态，不改 title/subtitle/summary。
     *
     * @param operatorUserId 后台操作者（sys user id），写入审计
     * @throws com.chengyinhub.common.exception.ServiceException 主题不公开/内容已变/版本过期/角色越权
     */
    ContentLandingMetadataVO reviewTranslation(ContentLandingTranslationReviewRequest request, Long operatorUserId);
}
