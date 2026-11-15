package com.chengyinhub.business.exception;

/**
 * B2(acceptance-r3):提现金额超过可提现余额。
 *
 * <p>getMessage() 仍是原拒绝理由(冷静期时带 PLAYER_WITHDRAWAL_COOLDOWN 机器码,监控按码分流);
 * 给用户看的是 {@link #getUserMessage()},不带机器码,也不叫用户「稍后重试」。</p>
 */
public class WithdrawalInsufficientBalanceException extends FundActionPreflightException {
    public static final String INSUFFICIENT_BALANCE = "WITHDRAWAL_INSUFFICIENT_BALANCE";
    public static final String COOLDOWN = "PLAYER_WITHDRAWAL_COOLDOWN";

    private final String userMessage;

    public WithdrawalInsufficientBalanceException(boolean cooldown, String message, String userMessage) {
        super(cooldown ? COOLDOWN : INSUFFICIENT_BALANCE, message);
        this.userMessage = userMessage == null || userMessage.isEmpty() ? "余额不足" : userMessage;
    }

    public String getUserMessage() { return userMessage; }
}
