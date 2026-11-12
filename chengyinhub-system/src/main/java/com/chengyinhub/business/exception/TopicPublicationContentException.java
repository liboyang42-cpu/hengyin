package com.chengyinhub.business.exception;

import java.util.List;
import com.chengyinhub.business.domain.vo.PublicationProblem;
import com.chengyinhub.common.exception.ServiceException;

/** Carries an already-rejected author content rule without changing the legacy error family. */
public final class TopicPublicationContentException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final Issues data;

    public TopicPublicationContentException(String message, List<? extends PublicationProblem> issues) {
        super(message);
        this.data = new Issues(issues);
    }

    public Issues getData() { return data; }

    public static InvalidArgument invalid(String code, String field, IllegalArgumentException failure) {
        return new InvalidArgument(failure.getMessage(), failure,
                List.of(PublicationProblem.from(code, field, failure)));
    }

    public static ServiceException withIssues(ServiceException failure, List<? extends PublicationProblem> issues) {
        return failure.setData(new Issues(issues));
    }

    public static Issues dataOf(RuntimeException failure) {
        if (failure instanceof TopicPublicationContentException content) return content.getData();
        if (failure instanceof InvalidArgument invalid) return invalid.getData();
        if (failure instanceof InvalidContent invalid) return invalid.getData();
        if (failure instanceof ServiceException service && service.getData() instanceof Issues issues) return issues;
        return null;
    }

public static final class InvalidArgument extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Issues data;

        public InvalidArgument(String message, Throwable cause, List<? extends PublicationProblem> issues) {
            super(message, cause);
            this.data = new Issues(issues);
        }

        public Issues getData() { return data; }

        /** getMessage() 仍是给作者的人话;toString() 接上被包装拒绝自己的工程诊断(如 GameplaySupportMatrix.Refusal 的节点/玩法 key/原因码)。 */
        @Override public String toString() {
            return getCause() == null ? super.toString() : super.toString() + " <- " + getCause();
        }
    }

public static final class InvalidContent extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Issues data;

        public InvalidContent(String message, List<? extends PublicationProblem> issues) {
            super(message);
            this.data = new Issues(issues);
        }

        public Issues getData() { return data; }
    }

    public static final class Issues {
        private final List<PublicationProblem> issues;

        public Issues(List<? extends PublicationProblem> issues) { this.issues = issues.stream().map(PublicationProblem::userSafe).toList(); }

        public List<PublicationProblem> getIssues() { return issues; }
    }
}
