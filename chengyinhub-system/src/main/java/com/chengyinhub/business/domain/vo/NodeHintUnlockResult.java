package com.chengyinhub.business.domain.vo;

/** Original hint response values; ServiceException keeps its existing exception contract. */
public record NodeHintUnlockResult(Hints hints, Failure failure) {
    public NodeHintUnlockResult {
        if ((hints == null) == (failure == null)) throw new IllegalArgumentException("Exactly one hint outcome is required");
    }
    public record Hints(String hint1, String hint2, int cost) {}
    public record Failure(int originalAjaxCode, String message) {}
}
