package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.vo.ContentCapturePreviewVO;
import com.chengyinhub.business.mapper.AuditTaskMapper;
import com.chengyinhub.business.mapper.ContentCapturePreviewPermissionMapper;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.utils.SecurityUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Current reviewer authority and a locked exact binding are required for every text preview. */
@Service
public class ContentCapturePreviewService {
    private static final String REVIEW_PERMISSION="business:audit:review";
    private final ContentCaptureReviewService reviews;
    private final AuditTaskMapper tasks;
    private final ContentCapturePreviewPermissionMapper permissions;
    private final ITopicOperationsScopeAuthorizer scope;
    public ContentCapturePreviewService(ContentCaptureReviewService reviews, AuditTaskMapper tasks,
            ContentCapturePreviewPermissionMapper permissions, ITopicOperationsScopeAuthorizer scope) {
        this.reviews=reviews; this.tasks=tasks; this.permissions=permissions; this.scope=scope;
    }

    @Transactional(rollbackFor=Exception.class)
    public ContentCapturePreviewVO preview(long taskId, int taskVersion, long snapshotId,
            long sourceVersion, String hash) {
        if (taskId<=0 || taskVersion<0 || snapshotId<=0 || sourceVersion<0
                || hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid preview identity");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ContentCaptureReviewService.UnavailableException();
        long reviewer=currentReviewer();
        AuditTask hint=tasks.selectAuditTaskById(taskId);
        if (hint==null || !ContentCaptureReviewService.BIZ_TYPE.equals(hint.getBizType())
                || !ContentCaptureReviewService.SOURCE_TYPE.equals(hint.getSourceType())) {
            // A menu-only reviewer without global topic scope must not enumerate candidate-task existence.
            if (!scope.hasGlobalTopicScope()) throw denied();
            throw conflict();
        }
        requireScope(hint,reviewer);
        ContentCaptureReviewService.PreviewEvidence evidence=reviews.lockPreview(taskId,taskVersion,snapshotId,sourceVersion,hash);
        if (!Objects.equals(hint.getBizId(),evidence.task.getBizId())) throw conflict();
        requireScope(evidence.task,reviewer);
        return new ContentCapturePreviewVO(taskId,taskVersion,evidence.task.getStatus(),snapshotId,
                evidence.candidate.topicId(),sourceVersion,hash,ContentCapturePreviewProjection.project(evidence.candidate));
    }

    private long currentReviewer() {
        Authentication authentication=SecurityUtils.getAuthentication();
        if (authentication==null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser)) throw denied();
        LoginUser login=(LoginUser)authentication.getPrincipal();
        Long id=login.getUserId();
        if (id==null || id<=0 || login.getUser()==null || !id.equals(login.getUser().getUserId())) throw denied();
        Set<String> cached=login.getPermissions();
        if (cached==null || (!cached.contains(REVIEW_PERMISSION) && !cached.contains("*:*:*"))) throw denied();
        // Do not trust stale session permissions: current active account and active role/menu grants are read under lock.
        if (!id.equals(permissions.lockActiveUser(id))) throw denied();
        if (!SysUser.isAdmin(id)) {
            List<String> current=permissions.lockPermissions(id);
            boolean allowed=false;
            if (current!=null) for (String grant:current) {
                if (grant!=null) for (String permission:grant.split(",")) {
                    if (REVIEW_PERMISSION.equals(permission.trim()) || "*:*:*".equals(permission.trim())) allowed=true;
                }
            }
            if (!allowed) throw denied();
        }
        return id;
    }

    private void requireScope(AuditTask task,long reviewer) {
        // An assignee is not topic delegation. The existing authorizer currently permits only platform superadmin.
        if (task.getBizId()==null || !scope.canViewTopic(task.getBizId())) throw denied();
        if (!SysUser.isAdmin(reviewer) && task.getCurrentAssignee()!=null
                && !Long.valueOf(reviewer).equals(task.getCurrentAssignee())) throw denied();
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Candidate preview access denied"); }
    private static ConflictException conflict() { return new ConflictException(); }
    public static final class ConflictException extends IllegalStateException {
        public ConflictException() { super("Candidate preview binding changed"); }
    }
}
