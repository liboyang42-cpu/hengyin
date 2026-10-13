package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.domain.ViewMemberCart;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.math.BigDecimal;

public class MemberCartVO {

    @Schema(description="购物车类型")
    private int cartType;
    @Schema(description="商品金额")
    private BigDecimal productAmount;
    @Schema(description="商品数量")
    private Long productQuantity;
    @Schema(description="商品重量")
    private Double productWeight;
    @Schema(description="运费")
    private BigDecimal deliveryFee;
    @Schema(description="税费")
    private BigDecimal taxFee;
    @Schema(description="合计金额")
    private BigDecimal totalAmount;
    @Schema(description="商品列表")
    private List<ViewMemberCart> productList;
    @Schema(description="默认收获地址")
    private UmsMemberAddress address;
    @Schema(description="用户余额")
    private Double pointBalance;

    public int getCartType() {
        return cartType;
    }

    public void setCartType(int cartType) {
        this.cartType = cartType;
    }

    public BigDecimal getProductAmount() {
        return productAmount;
    }

    public void setProductAmount(BigDecimal productAmount) {
        this.productAmount = productAmount;
    }

    public Long getProductQuantity() {
        return productQuantity;
    }

    public void setProductQuantity(Long productQuantity) {
        this.productQuantity = productQuantity;
    }

    public Double getProductWeight() {
        return productWeight;
    }

    public void setProductWeight(Double productWeight) {
        this.productWeight = productWeight;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public BigDecimal getTaxFee() {
        return taxFee;
    }

    public void setTaxFee(BigDecimal taxFee) {
        this.taxFee = taxFee;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public List<ViewMemberCart> getProductList() {
        return productList;
    }

    public void setProductList(List<ViewMemberCart> productList) {
        this.productList = productList;
    }

    public UmsMemberAddress getAddress() {
        return address;
    }

    public void setAddress(UmsMemberAddress address) {
        this.address = address;
    }

    public Double getPointBalance() {
        return pointBalance;
    }

    public void setPointBalance(Double pointBalance) {
        this.pointBalance = pointBalance;
    }
}
