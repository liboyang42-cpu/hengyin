package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.business.domain.UmsMemberCart;
import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.domain.vo.MemberCartVO;
import com.chengyinhub.business.service.IOmsOrderItemService;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IUmsMemberAddressService;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IViewMemberCartService;
import com.chengyinhub.business.service.impl.UmsMemberCartServiceImpl;
import com.chengyinhub.business.mapper.UmsMemberCartMapper;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-C 积分商城 0 元下单回归。
 * orderSettlement 曾无扣积分/无事务:任意登录用户可 0 积分生成 status=1 已支付订单 + 扣库存 + 清购物车 = 白拿商品。
 * 修复(积分 tender):积分不足即拒不建单;负数量拒;扣积分成功才建单,库存原子条件扣。
 */
class ApiCartControllerSettlementTest {

    private static final Long MEMBER = 88L;
    private static final Long SKU = 300L;
    private static final Long MERCHANT = 9L;

    private IViewMemberCartService cartView = mock(IViewMemberCartService.class);
    private IPmsSkuService skuSvc = mock(IPmsSkuService.class);
    private IPmsProductService productSvc = mock(IPmsProductService.class);
    private IUmsMemberService memberSvc = mock(IUmsMemberService.class);
    private IUmsMemberAddressService addrSvc = mock(IUmsMemberAddressService.class);
    private UmsMemberAddressMapper addressMapper = mock(UmsMemberAddressMapper.class);
    private IOmsOrderService orderSvc = mock(IOmsOrderService.class);
    private IOmsOrderItemService itemSvc = mock(IOmsOrderItemService.class);
    private UmsMemberCartMapper cartMapper = mock(UmsMemberCartMapper.class);
    private UmsMemberCartServiceImpl cartSvc = new UmsMemberCartServiceImpl();

    @org.junit.jupiter.api.BeforeEach
    void successfulPersistenceReturnsItsActualWriteReceipt() {
        when(orderSvc.insertOmsOrder(any())).thenAnswer(call -> {
            com.chengyinhub.business.domain.OmsOrder order = call.getArgument(0);
            order.setId(700L);
            return 1;
        });
        when(itemSvc.insertOmsOrderItem(any())).thenReturn(1);
    }

    @Test
    void zeroOrderWriteStopsBeforeItemsStockAndCartConsumption() {
        commonStubs(1L, 1000L);
        doReturn(0).when(orderSvc).insertOmsOrder(any());
        controller();
        assertThrows(ServiceException.class, () -> cartSvc.settleCart(MEMBER, "u", "5", "", "1"));
        verify(itemSvc, never()).insertOmsOrderItem(any());
        verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong());
        verify(cartMapper, never()).deleteUmsMemberCartByIdAndMemberId(anyLong(), anyLong());
    }

    @Test
    void missingGeneratedOrderIdCannotBecomeASuccessfulReceipt() {
        commonStubs(1L, 1000L);
        org.mockito.Mockito.doAnswer(call -> 1).when(orderSvc).insertOmsOrder(any());
        controller();
        assertThrows(ServiceException.class, () -> cartSvc.settleCart(MEMBER, "u", "5", "", "1"));
        verify(itemSvc, never()).insertOmsOrderItem(any());
        verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong());
    }

    @Test
    void zeroItemWriteStopsBeforeStockAndCartConsumption() {
        commonStubs(1L, 1000L);
        doReturn(0).when(itemSvc).insertOmsOrderItem(any());
        controller();
        assertThrows(ServiceException.class, () -> cartSvc.settleCart(MEMBER, "u", "5", "", "1"));
        verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong());
        verify(cartMapper, never()).deleteUmsMemberCartByIdAndMemberId(anyLong(), anyLong());
    }

    private ApiCartController controller() {
        ApiCartController c = spy(new ApiCartController());
        LoginUser lu = mock(LoginUser.class);
        when(lu.getUserId()).thenReturn(MEMBER);
        when(lu.getUsername()).thenReturn("u");
        doReturn(lu).when(c).getLoginUser();
        ReflectionTestUtils.setField(c, "viewMemberCartService", cartView);
        ReflectionTestUtils.setField(c, "umsMemberCartService", cartSvc);
        ReflectionTestUtils.setField(cartSvc, "umsMemberCartMapper", cartMapper);
        ReflectionTestUtils.setField(cartSvc, "viewMemberCartService", cartView);
        ReflectionTestUtils.setField(cartSvc, "pmsProductService", productSvc);
        ReflectionTestUtils.setField(cartSvc, "pmsSkuService", skuSvc);
        ReflectionTestUtils.setField(cartSvc, "umsMemberService", memberSvc);
        ReflectionTestUtils.setField(cartSvc, "umsMemberAddressService", addrSvc);
        ReflectionTestUtils.setField(cartSvc, "umsMemberAddressMapper", addressMapper);
        ReflectionTestUtils.setField(cartSvc, "omsOrderService", orderSvc);
        ReflectionTestUtils.setField(cartSvc, "omsOrderItemService", itemSvc);
        when(cartMapper.lockUmsMemberCartByIdAndMemberId(eq(MEMBER), anyLong())).thenReturn(5L);
        return c;
    }

    @Test
    void cartDeleteForwardsRawIdsAndAuthenticatedMemberToService() {
        IUmsMemberCartService service = mock(IUmsMemberCartService.class);
        ApiCartController controller = spy(new ApiCartController());
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUserId()).thenReturn(MEMBER);
        doReturn(loginUser).when(controller).getLoginUser();
        ReflectionTestUtils.setField(controller, "umsMemberCartService", service);

        assertTrue(controller.cartDelete("5,6").isSuccess());

        verify(service).deleteCarts(MEMBER, "5,6");
        verify(service, never()).selectUmsMemberCartById(anyLong());
    }

    @Test
    void controllerForwardsRawCartParametersToService() {
        IUmsMemberCartService service = mock(IUmsMemberCartService.class);
        ApiCartController controller = spy(new ApiCartController());
        LoginUser loginUser = mock(LoginUser.class);
        when(loginUser.getUserId()).thenReturn(MEMBER);
        when(loginUser.getUsername()).thenReturn("u");
        doReturn(loginUser).when(controller).getLoginUser();
        ReflectionTestUtils.setField(controller, "umsMemberCartService", service);

        controller.cartAdd("1", "2", "3", "0");
        controller.cartUpdate("4", "5", "6");
        controller.cartSettlement("7,8");
        controller.orderSettlement("9,10", "备注", "11");

        verify(service).addCart(MEMBER, "1", "2", "3", "0");
        verify(service).updateCart(MEMBER, "4", "5", "6");
        verify(service).previewSettlement(MEMBER, "7,8");
        verify(service).settleCart(MEMBER, "u", "9,10", "备注", "11");
    }

    @Test
    void deleteCartsFailsClosedWhenConditionalDeleteLosesRace() {
        controller();
        UmsMemberCart cart = new UmsMemberCart();
        cart.setId(5L);
        cart.setMemberId(MEMBER);
        when(cartMapper.selectUmsMemberCartByIdsAndMemberId(eq(MEMBER), any())).thenReturn(Collections.singletonList(cart));
        when(cartMapper.deleteUmsMemberCartByIdsAndMemberId(eq(MEMBER), any())).thenReturn(0);

        assertThrows(ServiceException.class, () -> cartSvc.deleteCarts(MEMBER, "5"));

        verify(cartMapper).deleteUmsMemberCartByIdsAndMemberId(eq(MEMBER), any());
    }

    @Test
    void cartUpdateFailsClosedWhenOwnedConditionalUpdateAffectsNoRows() {
        controller();
        UmsMemberCart cart = new UmsMemberCart();
        cart.setId(5L);
        cart.setMemberId(MEMBER);
        cart.setProductId(1L);
        when(cartMapper.selectUmsMemberCartById(5L)).thenReturn(cart);
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku(10L));
        when(cartMapper.updateUmsMemberCartByIdAndMemberId(any())).thenReturn(0);

        assertTrue(controller().cartUpdate("5", "1", String.valueOf(SKU)).isError());
    }

    private ViewMemberCart cart(long qty) {
        ViewMemberCart v = new ViewMemberCart();
        v.setId(5L);
        v.setMemberId(MEMBER);
        v.setMerchantId(MERCHANT);
        v.setSkuId(SKU);
        v.setProductId(1L);
        v.setQuantity(qty);
        v.setPrice(new BigDecimal("100"));
        v.setWeight(BigDecimal.ZERO);
        v.setCartType(1L);
        v.setProductName("g");
        return v;
    }

    private PmsSku sku(long stock) {
        PmsSku s = new PmsSku();
        s.setId(SKU);
        s.setProductId(1L);
        s.setPrice(new BigDecimal("100"));
        s.setStock(stock);
        return s;
    }

    private PmsProduct product() {
        PmsProduct product = new PmsProduct();
        product.setId(1L);
        product.setMerchantId(MERCHANT);
        product.setPublishStatus(1);
        product.setDelFlag(0);
        return product;
    }

    private void commonStubs(long qty, long point) {
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart(qty)));
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku(10L));
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        UmsMemberAddress address = new UmsMemberAddress();
        address.setMemberId(MEMBER);
        when(addrSvc.selectOwnedAddressById(1L, MEMBER)).thenReturn(address);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(1L, MEMBER)).thenReturn(address);
        UmsMember m = new UmsMember();
        m.setId(MEMBER);
        m.setPoint(point);
        when(memberSvc.selectUmsMemberById(MEMBER)).thenReturn(m);
        when(memberSvc.deductPoints(anyLong(), anyInt())).thenReturn(true);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(eq(1L), eq(MEMBER))).thenReturn(address);
    }

    @Test
    void orderSettlementFailsClosedWhenPointDeductionReturnsFalse() {
        commonStubs(1L, 1000L);
        when(memberSvc.deductPoints(MEMBER, 100)).thenReturn(false);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(1);

        assertTrue(controller().orderSettlement("5", "", "1").isError());

        verify(orderSvc, never()).insertOmsOrder(any());
        verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong());
    }

    @Test
    void zeroPointsMemberCannotCreatePaidOrder() {
        commonStubs(1L, 0L);

        controller().orderSettlement("5", "", "1");

        // 0 积分 → 友好预检拦下:不尝试扣积分、不生成订单/扣库存/清车
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(orderSvc, never()).insertOmsOrder(any());
        verify(skuSvc, never()).deductStock(anyLong(), anyLong());
        verify(cartMapper, never()).deleteUmsMemberCartById(anyLong());
    }

    @Test
    void negativeQuantityRejected() {
        commonStubs(-5L, 100000L); // 积分充足,但数量为负

        controller().orderSettlement("5", "", "1");

        // 负数量必须拒:不建单、不扣积分、不刷库存
        verify(orderSvc, never()).insertOmsOrder(any());
        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(skuSvc, never()).deductStock(anyLong(), anyLong());
    }

    @Test
    void sufficientPointsChargesAndCreatesOrder() {
        commonStubs(1L, 100000L);           // 积分充足
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(1); // 有货

        controller().orderSettlement("5", "", "1");

        // 真实扣积分(price 100 × qty 1 = 100)+ 原子扣库存 + 建单
        verify(memberSvc).deductPoints(MEMBER, 100);
        verify(skuSvc).deductStockForProduct(SKU, 1L, 1L);
        verify(orderSvc).insertOmsOrder(any());
    }

    @Test
    void orderSettlementRejectsAnotherMembersAddressWithoutSideEffects() {
        assertRejectedAddressHasNoSideEffects(address(MEMBER + 1, 0));
    }

    @Test
    void orderSettlementRejectsDeletedAddressWithoutSideEffects() {
        assertRejectedAddressHasNoSideEffects(address(MEMBER, 1));
    }

    @Test
    void orderSettlementFailsClosedWhenOwnedAddressDisappearsBeforeLock() {
        commonStubs(1L, 1000L);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(1L, MEMBER)).thenReturn(null);

        AjaxResult result = controller().orderSettlement("5", "", "1");

        assertAddressRejectionHasNoSideEffects(result);
        verify(addrSvc).selectOwnedAddressById(1L, MEMBER);
        verify(addressMapper).lockActiveUmsMemberAddressByIdAndMemberId(1L, MEMBER);
    }

    @Test
    void cartSettlementDoesNotExposeAnotherMembersAddress() {
        assertPreviewDoesNotExpose(address(MEMBER + 1, 0));
    }

    @Test
    void cartSettlementDoesNotExposeDeletedAddress() {
        assertPreviewDoesNotExpose(address(MEMBER, 1));
    }

    private void assertRejectedAddressHasNoSideEffects(UmsMemberAddress address) {
        commonStubs(1L, 1000L);
        when(addrSvc.selectOwnedAddressById(1L, MEMBER)).thenReturn(null);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(1L, MEMBER)).thenReturn(address);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(1);

        AjaxResult result = controller().orderSettlement("5", "", "1");

        assertAddressRejectionHasNoSideEffects(result);
        verify(addrSvc).selectOwnedAddressById(1L, MEMBER);
        verify(addrSvc, never()).selectUmsMemberAddressById(anyLong());
    }

    private void assertAddressRejectionHasNoSideEffects(AjaxResult result) {
        assertAll(
                () -> assertTrue(result.isError()),
                () -> assertEquals("收货地址不正确", result.get(AjaxResult.MSG_TAG)),
                () -> verify(memberSvc, never()).deductPoints(anyLong(), anyInt()),
                () -> verify(orderSvc, never()).insertOmsOrder(any()),
                () -> verify(itemSvc, never()).insertOmsOrderItem(any()),
                () -> verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong()),
                () -> verify(cartMapper, never()).deleteUmsMemberCartByIdAndMemberId(anyLong(), anyLong()));
    }

    private void assertPreviewDoesNotExpose(UmsMemberAddress address) {
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart(1L)));
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku(10L));
        when(addrSvc.selectUmsMemberAddressList(any())).thenReturn(Collections.singletonList(address));
        when(addrSvc.selectOwnedAddressList(MEMBER)).thenReturn(Collections.emptyList());

        AjaxResult result = controller().cartSettlement("5");
        MemberCartVO preview = result.isSuccess() ? (MemberCartVO) result.get(AjaxResult.DATA_TAG) : null;

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertNotNull(preview),
                () -> assertNull(preview.getAddress(), "结算预览不得返回他人或已删除的地址"),
                () -> verify(addrSvc).selectOwnedAddressList(MEMBER),
                () -> verify(addrSvc, never()).selectUmsMemberAddressList(any()));
    }

    private UmsMemberAddress address(Long memberId, Integer delFlag) {
        UmsMemberAddress address = new UmsMemberAddress();
        address.setId(1L);
        address.setMemberId(memberId);
        address.setDelFlag(delFlag);
        return address;
    }

    @Test
    void cartAddRejectsSkuBelongingToAnotherProduct() {
        PmsSku foreignSku = sku(10L);
        foreignSku.setProductId(2L);
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(foreignSku);

        assertTrue(controller().cartAdd("1", String.valueOf(SKU), "1", "0").isError());

        verify(cartMapper, never()).insertUmsMemberCart(any());
    }

    @Test
    void cartUpdateRejectsSkuBelongingToAnotherProduct() {
        UmsMemberCart cart = new UmsMemberCart();
        cart.setId(5L);
        cart.setMemberId(MEMBER);
        cart.setProductId(1L);
        PmsSku foreignSku = sku(10L);
        foreignSku.setProductId(2L);
        when(cartMapper.selectUmsMemberCartById(5L)).thenReturn(cart);
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(foreignSku);

        assertTrue(controller().cartUpdate("5", "1", String.valueOf(SKU)).isError());

        verify(cartMapper, never()).updateUmsMemberCart(any());
    }

    @Test
    void cartSettlementRejectsSkuBelongingToAnotherProduct() {
        PmsSku foreignSku = sku(10L);
        foreignSku.setProductId(2L);
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart(1L)));
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(foreignSku);
        when(addrSvc.selectUmsMemberAddressList(any())).thenReturn(Collections.emptyList());

        assertTrue(controller().cartSettlement("5").isError());
    }

    @Test
    void orderSettlementRejectsSkuBelongingToAnotherProduct() {
        commonStubs(1L, 1000L);
        PmsSku foreignSku = sku(10L);
        foreignSku.setProductId(2L);
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(foreignSku);

        assertTrue(controller().orderSettlement("5", "", "1").isError());

        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(orderSvc, never()).insertOmsOrder(any());
        verify(skuSvc, never()).deductStockForProduct(anyLong(), anyLong(), anyLong());
    }

    @Test
    void orderSettlementDeductsStockWithSkuProductPair() {
        commonStubs(1L, 1000L);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(1);

        controller().orderSettlement("5", "", "1");

        verify(skuSvc).deductStockForProduct(SKU, 1L, 1L);
    }

    @Test
    void cartAddRejectsUnpublishedProduct() {
        PmsProduct unpublished = product();
        unpublished.setPublishStatus(2);
        when(productSvc.selectPmsProductById(1L)).thenReturn(unpublished);
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku(10L));

        assertTrue(controller().cartAdd("1", String.valueOf(SKU), "1", "0").isError());

        verify(cartMapper, never()).insertUmsMemberCart(any());
    }

    @Test
    void cartUpdateRejectsAnotherMembersCart() {
        UmsMemberCart cart = new UmsMemberCart();
        cart.setId(5L);
        cart.setMemberId(MEMBER + 1);
        when(cartMapper.selectUmsMemberCartById(5L)).thenReturn(cart);

        assertTrue(controller().cartUpdate("5", "1", String.valueOf(SKU)).isError());

        verify(cartMapper, never()).updateUmsMemberCart(any());
    }

    @Test
    void cartSettlementUsesCurrentSkuPrice() {
        ViewMemberCart cart = cart(1L);
        cart.setPrice(BigDecimal.ONE);
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart));
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku(10L));
        when(addrSvc.selectUmsMemberAddressList(any())).thenReturn(Collections.emptyList());

        MemberCartVO result = (MemberCartVO) controller().cartSettlement("5").get("data");

        assertEquals(new BigDecimal("100"), result.getProductAmount());
        assertEquals(new BigDecimal("100"), result.getProductList().get(0).getPrice());
    }

    @Test
    void settlementRejectsWhenAnyRequestedCartIsMissing() {
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart(1L)));

        assertTrue(controller().cartSettlement("5,999").isError());
        assertTrue(controller().orderSettlement("5,999", "", "1").isError());

        verify(memberSvc, never()).deductPoints(anyLong(), anyInt());
        verify(orderSvc, never()).insertOmsOrder(any());
    }

    @Test
    void settlementRejectsQuantityBeyondResponseContractLimit() {
        long overflow = ((long) Integer.MAX_VALUE) + 1L;
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart(overflow)));

        assertTrue(controller().cartSettlement("5").isError());
        assertTrue(controller().orderSettlement("5", "", "1").isError());
    }

    @Test
    void orderSettlementFailsClosedWhenAtomicStockDeductionLosesRace() {
        commonStubs(1L, 1000L);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(0);

        assertTrue(controller().orderSettlement("5", "", "1").isError());

        verify(cartMapper, never()).deleteUmsMemberCartById(anyLong());
    }

    @Test
    void settleCartFailsClosedWhenOwnedConditionalDeleteAffectsNoRows() {
        commonStubs(1L, 1000L);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(1);
        when(cartMapper.deleteUmsMemberCartByIdAndMemberId(MEMBER, 5L)).thenReturn(0);
        controller();

        assertThrows(ServiceException.class, () -> cartSvc.settleCart(MEMBER, "u", "5", "", "1"));

        verify(memberSvc).deductPoints(MEMBER, 100);
        verify(orderSvc).insertOmsOrder(any());
        verify(skuSvc).deductStockForProduct(SKU, 1L, 1L);
        verify(cartMapper).deleteUmsMemberCartByIdAndMemberId(MEMBER, 5L);
    }

    @Test
    void settleCartFailureEscapesTransactionalServiceBoundary() throws Exception {
        commonStubs(1L, 1000L);
        when(skuSvc.deductStockForProduct(SKU, 1L, 1L)).thenReturn(0);
        controller();

        assertThrows(ServiceException.class, () -> cartSvc.settleCart(MEMBER, "u", "5", "", "1"));

        Method method = UmsMemberCartServiceImpl.class.getMethod("settleCart", Long.class, String.class,
                String.class, String.class, String.class);
        assertNotNull(method.getAnnotation(Transactional.class), "库存竞争异常必须从事务 Service 穿透，交由 Spring 回滚积分、订单与库存");
        verify(memberSvc).deductPoints(MEMBER, 100);
        verify(orderSvc).insertOmsOrder(any());
        verify(cartMapper, never()).deleteUmsMemberCartById(anyLong());
    }

    @Test
    void orderSettlementPreservesCurrentSkuPricePrecision() {
        ViewMemberCart cart = cart(3L);
        PmsSku sku = sku(10L);
        sku.setPrice(new BigDecimal("0.1"));
        when(cartView.selectViewMemberCartByIds(any())).thenReturn(Collections.singletonList(cart));
        when(productSvc.selectPmsProductById(1L)).thenReturn(product());
        when(skuSvc.selectPmsSkuById(SKU)).thenReturn(sku);
        UmsMemberAddress address = new UmsMemberAddress();
        address.setMemberId(MEMBER);
        when(addrSvc.selectOwnedAddressById(1L, MEMBER)).thenReturn(address);
        when(addressMapper.lockActiveUmsMemberAddressByIdAndMemberId(1L, MEMBER)).thenReturn(address);
        UmsMember member = new UmsMember();
        member.setId(MEMBER);
        member.setPoint(1000L);
        when(memberSvc.selectUmsMemberById(MEMBER)).thenReturn(member);
        when(memberSvc.deductPoints(MEMBER, 1)).thenReturn(true);
        when(skuSvc.deductStockForProduct(SKU, 1L, 3L)).thenReturn(1);

        controller().orderSettlement("5", "", "1");

        ArgumentCaptor<OmsOrder> orderCaptor = ArgumentCaptor.forClass(OmsOrder.class);
        verify(orderSvc).insertOmsOrder(orderCaptor.capture());
        assertEquals(new BigDecimal("0.3"), orderCaptor.getValue().getProductTotalAmount());
    }
}
