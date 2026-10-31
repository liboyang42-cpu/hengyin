package com.chengyinhub.business.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.domain.ClubPost;
import com.chengyinhub.business.domain.ClubPostRevision;
import com.chengyinhub.business.domain.dto.ClubPostUpdateResult;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.mapper.ClubPostMapper;
import com.chengyinhub.business.mapper.ClubPostRevisionMapper;
import com.chengyinhub.business.service.IClubPostService;
import com.chengyinhub.business.service.IClubPostMediaReviewService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 俱乐部动态 Service 业务层处理
 */
@Service
public class ClubPostServiceImpl implements IClubPostService
{
    private static final String UPDATE_ACTION = "CLUB_POST_UPDATE";
    private static final String PIN_ACTION = "CLUB_POST_PIN";
    private static final String UNPIN_ACTION = "CLUB_POST_UNPIN";
    private static final String REQUEST_FINGERPRINT_PREFIX = "requestFingerprint=";

    @Autowired
    private ClubPostMapper clubPostMapper;

    @Autowired
    private ClubMapper clubMapper;

    @Autowired
    private ClubMemberMapper clubMemberMapper;

    @Autowired
    private IClubAccessService clubAccessService;

    @Autowired
    private ClubPostRevisionMapper clubPostRevisionMapper;

    @Autowired
    private BusinessAuditEventMapper businessAuditEventMapper;

    @Autowired
    private IClubPostMediaReviewService clubPostMediaReviewService;

    @Override
    public ClubPost selectClubPostById(Long id)
    {
        return clubPostMapper.selectClubPostById(id);
    }

    @Override
    public ClubPost selectClubPostByIdForUpdate(Long id)
    {
        return clubPostMapper.selectClubPostByIdForUpdate(id);
    }

    @Override
    public List<ClubPost> selectClubPostList(ClubPost clubPost)
    {
        return clubPostMapper.selectClubPostList(clubPost);
    }

    @Override
    public List<ClubPost> selectClubPostPage(Long clubId, Long beforeId, int limit)
    {
        List<ClubPost> page = new java.util.ArrayList<>();
        if (beforeId == null) page.addAll(clubPostMapper.selectClubPostPage(clubId, true, null, limit));
        page.addAll(clubPostMapper.selectClubPostPage(clubId, false, beforeId, limit));
        return page;
    }

    @Override
    public int insertClubPost(ClubPost clubPost)
    {
        validateContent(clubPost.getContent(), clubPost.getImages());
        clubPost.setCreateTime(DateUtils.getNowDate());
        return clubPostMapper.insertClubPost(clubPost);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertClubPostInActiveClub(ClubPost clubPost, Long memberId)
    {
        if (clubPost == null || clubPost.getClubId() == null || memberId == null)
        {
            throw new IllegalArgumentException("缺少俱乐部或会员参数");
        }
        Club club = clubMapper.selectClubByIdForUpdate(clubPost.getClubId());
        if (club == null)
        {
            throw new IllegalStateException("俱乐部不存在或已解散");
        }
        boolean isOwner = club.getMemberId() != null && club.getMemberId().equals(memberId);
        ClubMember membership = isOwner ? null
                : clubMemberMapper.selectByClubAndMember(clubPost.getClubId(), memberId);
        if (!isOwner && membership == null)
        {
            throw new IllegalStateException("加入俱乐部后才能发动态");
        }
        int type = clubPost.getType() == null ? 0 : clubPost.getType();
        if (type < 0 || type > 2) throw badRequest("动态类型不合法");
        validateContent(clubPost.getContent(), clubPost.getImages());
        if (type == 2)
        {
            if (clubPost.getContent() == null || clubPost.getContent().trim().isEmpty()) {
                throw badRequest("公告正文不能为空");
            }
            clubAccessService.require(memberId, clubPost.getClubId(), null, ClubPermission.CONTENT_MANAGE);
        }
        clubPost.setType(type);
        clubPost.setIsPinned(0);
        clubPost.setVersion(0);
        clubPost.setCreateTime(DateUtils.getNowDate());
        int rows = clubPostMapper.insertClubPost(clubPost);
        if (rows != 1)
        {
            throw new IllegalStateException("发布动态失败，请稍后重试");
        }
        if (clubPost.getImages() != null && !clubPost.getImages().trim().isEmpty())
        {
            clubPostMediaReviewService.enqueueUpdatedMedia(
                    clubPost.getId(), 0, memberId, clubPost.getImages().trim());
        }
        return rows;
    }

    @Override
    public int updateClubPost(ClubPost clubPost)
    {
        return clubPostMapper.updateClubPost(clubPost);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPostUpdateResult updatePost(Long principalMemberId, Long postId, String content, String images,
                                           Integer expectedVersion, String requestId)
    {
        requirePositive(principalMemberId, "登录会员不合法");
        requirePositive(postId, "动态ID不合法");
        validateVersion(expectedVersion);
        String normalizedContent = content == null ? "" : content.trim();
        String normalizedImages = images == null ? "" : images.trim();
        validateContent(normalizedContent, normalizedImages);
        String normalizedRequestId = normalizeRequestId(requestId);

        ClubPost locked = clubPostMapper.selectClubPostByIdForUpdate(postId);
        if (locked == null || !Integer.valueOf(0).equals(locked.getDelFlag())) throw badRequest("动态不存在");
        ClubAccessContext access = clubAccessService.resolve(principalMemberId, locked.getClubId(), null);
        requireScoped(access, principalMemberId, locked.getClubId());
        if (Integer.valueOf(2).equals(locked.getType())) {
            if (!access.hasPermission(ClubPermission.CONTENT_MANAGE)) throw forbidden();
        } else if (!principalMemberId.equals(locked.getAuthorMemberId())) {
            throw forbidden();
        }
        String requestFingerprint = updateFingerprint(principalMemberId, locked.getClubId(), postId,
                expectedVersion, normalizedContent, normalizedImages);
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", locked.getClubId(), UPDATE_ACTION, normalizedRequestId);
        if (receipt != null) {
            requireExactUpdateReplay(receipt, access, locked, requestFingerprint);
            return new ClubPostUpdateResult(locked, false);
        }
        int currentVersion = versionOf(locked);
        if (currentVersion != expectedVersion) throw conflict("动态已被修改，请刷新后重试");
        boolean imagesChanged = !normalizedImages.equals(nullToEmpty(locked.getImages()));
        if (normalizedContent.equals(nullToEmpty(locked.getContent())) && !imagesChanged) {
            return new ClubPostUpdateResult(locked, false);
        }

        Date now = new Date();
        ClubPostRevision revision = new ClubPostRevision();
        revision.setPostId(locked.getId());
        revision.setClubId(locked.getClubId());
        revision.setEditorMemberId(principalMemberId);
        revision.setSnapshotVersion(currentVersion);
        revision.setContent(locked.getContent());
        revision.setImages(locked.getImages());
        revision.setType(locked.getType());
        revision.setCreateTime(now);
        if (clubPostRevisionMapper.insertClubPostRevision(revision) != 1) {
            throw conflict("编辑历史写入失败");
        }
        if (clubPostMapper.updateContentCas(postId, normalizedContent, normalizedImages,
                currentVersion, now) != 1) throw conflict("动态已被修改，请刷新后重试");
        String addedImages = addedMedia(locked.getImages(), normalizedImages);
        if (imagesChanged && !addedImages.isEmpty()) {
            clubPostMediaReviewService.enqueueUpdatedMedia(
                    postId, currentVersion + 1, principalMemberId, addedImages);
        }
        audit(access, UPDATE_ACTION, locked, currentVersion, currentVersion + 1,
                normalizedRequestId, REQUEST_FINGERPRINT_PREFIX + requestFingerprint);
        return new ClubPostUpdateResult(clubPostMapper.selectClubPostById(postId), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ClubPost setPinned(Long principalMemberId, Long postId, boolean pinned,
                              Integer expectedVersion, String requestId)
    {
        requirePositive(principalMemberId, "登录会员不合法");
        requirePositive(postId, "动态ID不合法");
        validateVersion(expectedVersion);
        String normalizedRequestId = normalizeRequestId(requestId);
        ClubPost locked = clubPostMapper.selectClubPostByIdForUpdate(postId);
        if (locked == null || !Integer.valueOf(0).equals(locked.getDelFlag())) throw badRequest("动态不存在");
        ClubAccessContext access = clubAccessService.require(
                principalMemberId, locked.getClubId(), null, ClubPermission.CONTENT_MANAGE);
        requireScoped(access, principalMemberId, locked.getClubId());
        if (!Integer.valueOf(2).equals(locked.getType())) throw badRequest("只有公告可以置顶");
        String action = pinned ? PIN_ACTION : UNPIN_ACTION;
        String oppositeAction = pinned ? UNPIN_ACTION : PIN_ACTION;
        String requestFingerprint = pinFingerprint(principalMemberId, locked.getClubId(), postId,
                expectedVersion, pinned);
        BusinessAuditEvent receipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", locked.getClubId(), action, normalizedRequestId);
        BusinessAuditEvent oppositeReceipt = businessAuditEventMapper.selectByDomainActionRequestForUpdate(
                "CLUB", locked.getClubId(), oppositeAction, normalizedRequestId);
        if (receipt != null || oppositeReceipt != null) {
            if (receipt == null || oppositeReceipt != null) throw conflict("requestId 已用于其他置顶请求");
            requireExactPinReplay(receipt, access, locked, action, requestFingerprint);
            return locked;
        }
        int currentVersion = versionOf(locked);
        if (currentVersion != expectedVersion) throw conflict("公告已被修改，请刷新后重试");
        boolean currentPinned = Integer.valueOf(1).equals(locked.getIsPinned());
        if (currentPinned == pinned) {
            audit(access, action, locked, currentVersion, currentVersion,
                    normalizedRequestId, REQUEST_FINGERPRINT_PREFIX + requestFingerprint);
            return clubPostMapper.selectClubPostById(postId);
        }
        Date now = new Date();
        if (clubPostMapper.updatePinnedCas(postId, pinned ? 1 : 0, pinned ? now : null,
                currentVersion, now) != 1) throw conflict("公告已被修改，请刷新后重试");
        audit(access, action, locked, currentVersion, currentVersion + 1,
                normalizedRequestId, REQUEST_FINGERPRINT_PREFIX + requestFingerprint);
        return clubPostMapper.selectClubPostById(postId);
    }

    private String addedMedia(String previousImages, String currentImages)
    {
        java.util.Map<String, String> previous =
                ClubPostMediaReviewTransactionService.mediaByHash(previousImages);
        java.util.Map<String, String> current =
                ClubPostMediaReviewTransactionService.mediaByHash(currentImages);
        StringBuilder added = new StringBuilder();
        for (java.util.Map.Entry<String, String> media : current.entrySet())
        {
            if (previous.containsKey(media.getKey())) continue;
            if (added.length() > 0) added.append(';');
            added.append(media.getValue());
        }
        return added.toString();
    }

    @Override
    public List<ClubPostRevision> listRevisionHistory(Long postId)
    {
        requirePositive(postId, "动态ID不合法");
        ClubPost active = clubPostMapper.selectClubPostById(postId);
        if (active == null || !Integer.valueOf(0).equals(active.getDelFlag())) throw badRequest("动态不存在");
        if (clubMapper.selectClubById(active.getClubId()) == null) throw badRequest("动态不存在");
        List<ClubPostRevision> rows = clubPostRevisionMapper.selectByPostId(postId);
        if (rows == null) return Collections.emptyList();
        for (ClubPostRevision row : rows) row.setEditorMemberId(null);
        return rows;
    }

    @Override
    public int deleteClubPostById(Long id)
    {
        return clubPostMapper.deleteClubPostById(id);
    }

    @Override
    public int incrementLikeCount(Long id, int delta)
    {
        return clubPostMapper.incrementLikeCount(id, delta);
    }

    @Override
    public int incrementCommentCount(Long id, int delta)
    {
        return clubPostMapper.incrementCommentCount(id, delta);
    }

    @Override
    public List<ClubPost> selectFeedByClubIds(java.util.List<Long> clubIds, int limit) {
        // 空集合不进 SQL:MyBatis 的 foreach 会生成 `in ()`,直接语法错。
        // 「我一个俱乐部都没加入」是正常状态,不是异常。
        if (clubIds == null || clubIds.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return clubPostMapper.selectFeedByClubIds(clubIds, limit <= 0 ? 20 : Math.min(limit, 50));
    }

    @Override
    public List<ClubPost> selectSiteFeed(Long viewerId, Long beforeId, int limit) {
        // 51 = 单页上限 50 + 1 条探针(调用方据此判断 hasMore)。
        return clubPostMapper.selectSiteFeed(viewerId, beforeId != null && beforeId > 0 ? beforeId : null,
                limit <= 0 ? 21 : Math.min(limit, 51));
    }

    private void audit(ClubAccessContext access, String action, ClubPost post,
                       int beforeVersion, int afterVersion, String requestId, String reason) {
        BusinessAuditEvent event = new BusinessAuditEvent();
        event.setDomainType("CLUB");
        event.setDomainId(access.getClubId());
        event.setOwnerMemberId(access.getOwnerMemberId());
        event.setActorMemberId(access.getPrincipalMemberId());
        event.setActorRoleCode(access.getPrimaryRoleCode());
        event.setAction(action);
        event.setTargetType("CLUB_POST");
        event.setTargetId(post.getId());
        event.setRelationId(post.getId());
        event.setBeforeJson(stateJson(post, beforeVersion));
        event.setAfterJson("{\"version\":" + afterVersion + "}");
        event.setReason(reason);
        event.setRequestId(requestId);
        event.setCreateTime(new Date());
        try {
            if (businessAuditEventMapper.insertBusinessAuditEvent(event) != 1) {
                throw conflict("业务审计写入失败");
            }
        } catch (DuplicateKeyException e) {
            throw conflict("requestId 已用于其他请求");
        }
    }

    private void requireExactUpdateReplay(BusinessAuditEvent receipt, ClubAccessContext access,
                                          ClubPost post, String requestFingerprint) {
        boolean exact = Objects.equals(access.getOwnerMemberId(), receipt.getOwnerMemberId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && "CLUB_POST".equals(receipt.getTargetType())
                && Objects.equals(post.getId(), receipt.getTargetId())
                && Objects.equals(post.getId(), receipt.getRelationId())
                && (REQUEST_FINGERPRINT_PREFIX + requestFingerprint).equals(receipt.getReason());
        if (!exact) throw conflict("requestId 已用于其他动态编辑");
    }

    private void requireExactPinReplay(BusinessAuditEvent receipt, ClubAccessContext access,
                                       ClubPost post, String action, String requestFingerprint) {
        boolean exact = action.equals(receipt.getAction())
                && Objects.equals(access.getOwnerMemberId(), receipt.getOwnerMemberId())
                && Objects.equals(access.getPrincipalMemberId(), receipt.getActorMemberId())
                && "CLUB_POST".equals(receipt.getTargetType())
                && Objects.equals(post.getId(), receipt.getTargetId())
                && Objects.equals(post.getId(), receipt.getRelationId())
                && (REQUEST_FINGERPRINT_PREFIX + requestFingerprint).equals(receipt.getReason());
        if (!exact) throw conflict("requestId 已用于其他置顶请求");
    }

    private String pinFingerprint(Long principalMemberId, Long clubId, Long postId,
                                  Integer expectedVersion, boolean pinned) {
        StringBuilder source = new StringBuilder();
        appendFingerprintPart(source, pinned ? PIN_ACTION : UNPIN_ACTION);
        appendFingerprintPart(source, principalMemberId);
        appendFingerprintPart(source, clubId);
        appendFingerprintPart(source, postId);
        appendFingerprintPart(source, expectedVersion);
        appendFingerprintPart(source, pinned);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String updateFingerprint(Long principalMemberId, Long clubId, Long postId,
                                     Integer expectedVersion, String content, String images) {
        StringBuilder source = new StringBuilder();
        appendFingerprintPart(source, UPDATE_ACTION);
        appendFingerprintPart(source, principalMemberId);
        appendFingerprintPart(source, clubId);
        appendFingerprintPart(source, postId);
        appendFingerprintPart(source, expectedVersion);
        appendFingerprintPart(source, content);
        appendFingerprintPart(source, images);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void appendFingerprintPart(StringBuilder target, Object part) {
        String value = part == null ? "<null>" : String.valueOf(part);
        target.append(value.length()).append(':').append(value).append('|');
    }

    private String stateJson(ClubPost post, int version) {
        return "{\"version\":" + version + ",\"type\":" + post.getType()
                + ",\"isPinned\":" + (Integer.valueOf(1).equals(post.getIsPinned()) ? 1 : 0) + "}";
    }

    private void validateContent(String content, String images) {
        String normalizedContent = content == null ? "" : content.trim();
        String normalizedImages = images == null ? "" : images.trim();
        if (normalizedContent.isEmpty() && normalizedImages.isEmpty()) throw badRequest("动态内容不能为空");
        if (normalizedContent.length() > 300) throw badRequest("动态正文不能超过300字");
        if (normalizedImages.length() > 2000) throw badRequest("动态图片参数过长");
    }

    private String normalizeRequestId(String requestId) {
        String normalized = requestId == null ? "" : requestId.trim();
        if (normalized.isEmpty()) normalized = UUID.randomUUID().toString();
        if (normalized.length() > 64) throw badRequest("requestId 不能超过64字");
        return normalized;
    }

    private void requireScoped(ClubAccessContext access, Long principalMemberId, Long clubId) {
        if (access == null || !principalMemberId.equals(access.getPrincipalMemberId())
                || !clubId.equals(access.getClubId())) throw forbidden();
    }

    private void validateVersion(Integer version) {
        if (version == null || version < 0) throw badRequest("缺少有效版本号");
    }

    private int versionOf(ClubPost post) { return post.getVersion() == null ? 0 : post.getVersion(); }
    private String nullToEmpty(String value) { return value == null ? "" : value.trim(); }
    private void requirePositive(Long value, String message) {
        if (value == null || value <= 0) throw badRequest(message);
    }
    private ServiceException badRequest(String message) { return new ServiceException(message, HttpStatus.BAD_REQUEST); }
    private ServiceException forbidden() { return new ServiceException("无权操作该动态", HttpStatus.FORBIDDEN); }
    private ServiceException conflict(String message) { return new ServiceException(message, HttpStatus.CONFLICT); }
}
