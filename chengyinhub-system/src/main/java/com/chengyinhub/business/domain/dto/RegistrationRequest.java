package com.chengyinhub.business.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

/**
 * 报名请求对象
 *
 * @author webxh
 * @date 2025-09-02
 */
public class RegistrationRequest {

    /** 类型1、主题  2、活动 */
    @NotNull(message = "活动类型不能为空")
    @Schema(description = "活动类型", example = "类型1、主题  2、活动 ")
    private Integer ownerType;

    /** 主题ID/活动ID */
    @NotNull(message = "主题/活动ID不能为空")
    @Schema(description = "主题/活动ID", example = "1")
    private Long ownerId;

    /** 票种ID（单票模式必填，多票模式可空） */
    @Schema(description = "票种ID", example = "1")
    private Long ticketId;

    /** 多票模式：tickets 数组（每项 = 一张票种 + 数量 + 参与日期） */
    @Schema(description = "多票数组")
    private List<RegistrationTicketItem> tickets;

    /** 报名姓名 */
    @NotBlank(message = "姓名不能为空")
    @Schema(description = "姓名", example = "张三")
    private String realName;

    /** 联系电话 */
    @NotBlank(message = "手机号不能为空")
    @Schema(description = "手机号", example = "18812341234")
    private String phone;

    /** 邮箱 */
    @Schema(description = "邮箱", example = "example@qq.com")
    private String email;

    /** 地址 */
    @Schema(description = "地址", example = "上海市人民广场")
    private String address;

    /** 地址ID */
    @Schema(description = "地址ID", example = "1")
    private Long addressId;



    /** 是否使用积分支付 */
    @Schema(description = "是否使用积分支付", example = "0不使用 1使用")
    private Integer isUsePoint;

    /** 本期票源俱乐部标记；仅在支付成功且仍处售票窗时才冻结为 club 归因。 */
    @Schema(description = "本期票源俱乐部ID；无标记归平台")
    private Long sourceClubId;

    /** 专属链接/邀请码原文，仅用于本期票源审计。 */
    @Size(max = 64, message = "票源标记长度不能超过64个字符")
    @Schema(description = "本期售票来源标记")
    private String sourceChannelCode;

    /** 报价签名（M0-1 实扣报价一致）：来自 /api/registration/quote 的 quoteSign，
     *  下单时携带；后端重算当前报价签名比对，不一致返回 409 促使前端重新报价。为空=兼容旧版跳过校验。 */
    @Schema(description = "报价签名(quote 接口返回)", example = "e10adc...")
    private String quoteSign;

    public String getQuoteSign() {
        return quoteSign;
    }

    public void setQuoteSign(String quoteSign) {
        this.quoteSign = quoteSign;
    }

    /** BE-03 幂等键：每个结账意图生成一次，网络重试必须复用；服务端不生成兜底键。 */
    @NotBlank(message = "报名幂等键不能为空")
    @Size(max = 64, message = "幂等键长度不能超过64个字符")
    @Schema(description = "幂等键(前端每次结账生成的 UUID)", example = "a1b2c3d4-...")
    private String requestId;

    /**
     * 支付渠道:"APP" = 原生 App 支付(trade_type=APP);其余/为空 = 小程序 JSAPI。
     *
     * ★ 渠道必须在**建单时**就定,不能建完 JSAPI 单再改走 App:
     *   同一个 outTradeNo 在微信侧只能对应一种 trade_type,重复下单会被拒。
     */
    private String payChannel;

    /** 候补 offer；必须与 token 成对提交，仅服务端 OFFERED 状态可认领。 */
    private Long waitlistOfferId;
    private String waitlistOfferToken;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId == null || requestId.trim().isEmpty() ? null : requestId.trim();
    }


    public Integer getOwnerType() {
        return ownerType;
    }

    public void setOwnerType(Integer ownerType) {
        this.ownerType = ownerType;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Long getAddressId() {
        return addressId;
    }

    public void setAddressId(Long addressId) {
        this.addressId = addressId;
    }

    public Integer getIsUsePoint() {
        return isUsePoint;
    }

    public void setIsUsePoint(Integer isUsePoint) {
        this.isUsePoint = isUsePoint;
    }

    public Long getSourceClubId() {
        return sourceClubId;
    }

    public void setSourceClubId(Long sourceClubId) {
        this.sourceClubId = sourceClubId;
    }

    public String getSourceChannelCode() {
        return sourceChannelCode;
    }

    public void setSourceChannelCode(String sourceChannelCode) {
        this.sourceChannelCode = sourceChannelCode == null || sourceChannelCode.trim().isEmpty()
                ? null : sourceChannelCode.trim();
    }

    public List<RegistrationTicketItem> getTickets() {
        return tickets;
    }

    public void setTickets(List<RegistrationTicketItem> tickets) {
        this.tickets = tickets;
    }


    public String getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(String payChannel) {
        this.payChannel = payChannel;
    }

    public Long getWaitlistOfferId() { return waitlistOfferId; }
    public void setWaitlistOfferId(Long waitlistOfferId) { this.waitlistOfferId = waitlistOfferId; }
    public String getWaitlistOfferToken() { return waitlistOfferToken; }
    public void setWaitlistOfferToken(String waitlistOfferToken) {
        this.waitlistOfferToken = waitlistOfferToken == null || waitlistOfferToken.trim().isEmpty()
                ? null : waitlistOfferToken.trim();
    }
}
