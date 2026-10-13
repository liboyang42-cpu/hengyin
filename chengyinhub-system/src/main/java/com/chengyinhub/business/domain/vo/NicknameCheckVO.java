package com.chengyinhub.business.domain.vo;

public class NicknameCheckVO {
    private String nickname;
    private boolean available;
    private String errorCode;
    private Integer cursor;

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Integer getCursor() { return cursor; }
    public void setCursor(Integer cursor) { this.cursor = cursor; }
}
