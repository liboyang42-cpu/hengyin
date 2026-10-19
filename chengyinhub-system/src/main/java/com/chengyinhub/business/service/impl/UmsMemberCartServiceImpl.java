package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;
import com.chengyinhub.business.domain.CartSettlementReceipt;
import com.chengyinhub.business.mapper.CartSettlementReceiptMapper;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.domain.OmsOrderItem;
import com.chengyinhub.business.domain.PmsProduct;
import com.chengyinhub.business.domain.PmsSku;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberAddress;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.UmsMemberCartMapper;
import com.chengyinhub.business.mapper.UmsMemberAddressMapper;
import com.chengyinhub.business.domain.UmsMemberCart;
import com.chengyinhub.business.domain.ViewMemberCart;
import com.chengyinhub.business.domain.vo.MemberCartVO;
import com.chengyinhub.business.service.IUmsMemberCartService;
import com.chengyinhub.business.service.IViewMemberCartService;
import com.chengyinhub.business.service.IPmsProductService;
import com.chengyinhub.business.service.IPmsSkuService;
import com.chengyinhub.business.service.IUmsMemberAddressService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IOmsOrderService;
import com.chengyinhub.business.service.IOmsOrderItemService;
import com.chengyinhub.common.utils.StringUtils;

/**
 * 购物车Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-09-02
 */
@Service
public class UmsMemberCartServiceImpl implements IUmsMemberCartService 
{
    private static final long MAX_CART_QUANTITY = Integer.MAX_VALUE;
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    @Autowired
    private UmsMemberCartMapper umsMemberCartMapper;

    @Autowired
    private CartSettlementReceiptMapper cartSettlementReceiptMapper;

    @Autowired
    private IViewMemberCartService viewMemberCartService;

    @Autowired
    private IPmsProductService pmsProductService;

    @Autowired
    private IPmsSkuService pmsSkuService;

    @Autowired
    private IUmsMemberAddressService umsMemberAddressService;

    @Autowired
    private UmsMemberAddressMapper umsMemberAddressMapper;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IOmsOrderService omsOrderService;

    @Autowired
    private IOmsOrderItemService omsOrderItemService;

    /**
     * 查询购物车
     * 
     * @param id 购物车主键
     * @return 购物车
     */
    @Override
    public UmsMemberCart selectUmsMemberCartById(Long id)
    {
        return umsMemberCartMapper.selectUmsMemberCartById(id);
    }

    /**
     * 查询购物车列表
     * 
     * @param umsMemberCart 购物车
     * @return 购物车
     */
    @Override
    public List<UmsMemberCart> selectUmsMemberCartList(UmsMemberCart umsMemberCart)
    {
        return umsMemberCartMapper.selectUmsMemberCartList(umsMemberCart);
    }

    /**
     * 新增购物车
     * 
     * @param umsMemberCart 购物车
     * @return 结果
     */
    @Override
    public int insertUmsMemberCart(UmsMemberCart umsMemberCart)
    {
        umsMemberCart.setCreateTime(DateUtils.getNowDate());
        return umsMemberCartMapper.insertUmsMemberCart(umsMemberCart);
    }

    /**
     * 修改购物车
     * 
     * @param umsMemberCart 购物车
     * @return 结果
     */
    @Override
    public int updateUmsMemberCart(UmsMemberCart umsMemberCart)
    {
        umsMemberCart.setUpdateTime(DateUtils.getNowDate());
        return umsMemberCartMapper.updateUmsMemberCart(umsMemberCart);
    }

    /**
     * 批量删除购物车
     * 
     * @param ids 需要删除的购物车主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberCartByIds(Long[] ids)
    {
        return umsMemberCartMapper.deleteUmsMemberCartByIds(ids);
    }

    /**
     * 删除购物车信息
     * 
     * @param id 购物车主键
     * @return 结果
     */
    @Override
    public int deleteUmsMemberCartById(Long id)
    {
        return umsMemberCartMapper.deleteUmsMemberCartById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCarts(Long memberId, String rawCartIds)
    {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("购物车数据异常");
        }
        Long[] cartIds = parseCartIds(rawCartIds);
        Set<Long> requestedIds = new LinkedHashSet<>();
        for (Long cartId : cartIds) {
            requestedIds.add(cartId);
        }
        List<UmsMemberCart> carts = umsMemberCartMapper.selectUmsMemberCartByIdsAndMemberId(memberId, cartIds);
        if (carts == null || carts.size() != cartIds.length) {
            throw new ServiceException("购物车信息不存在或无权删除");
        }
        Set<Long> returnedIds = new LinkedHashSet<>();
        for (UmsMemberCart cart : carts) {
            if (cart == null || cart.getId() == null || !requestedIds.contains(cart.getId()) || !memberId.equals(cart.getMemberId())
                    || !returnedIds.add(cart.getId())) {
                throw new ServiceException("购物车信息不存在或无权删除");
            }
        }
        int deleted = umsMemberCartMapper.deleteUmsMemberCartByIdsAndMemberId(memberId, cartIds);
        if (deleted != cartIds.length) {
            throw new ServiceException("购物车数据已变更，请重试");
        }
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addCart(Long memberId, String rawProductId, String rawSkuId, String rawQuantity, String rawIsBuy)
    {
        Long productId = parsePositiveId(rawProductId);
        Long skuId = parsePositiveId(rawSkuId);
        Long quantity = parsePositiveId(rawQuantity);
        Long isBuy = parseIsBuy(rawIsBuy);
        requireQuantity(quantity);
        PmsProduct product = pmsProductService.selectPmsProductById(productId);
        PmsSku sku = pmsSkuService.selectPmsSkuById(skuId);
        validateProductAndSku(product, sku, quantity);

        UmsMemberCart cart = new UmsMemberCart();
        cart.setMemberId(memberId);
        cart.setMerchantId(product.getMerchantId());
        cart.setProductId(product.getId());
        cart.setSkuId(sku.getId());
        cart.setQuantity(quantity);
        cart.setIsBuy(isBuy);
        cart.setStatus(0L);
        insertUmsMemberCart(cart);
        return cart.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long updateCart(Long memberId, String rawCartId, String rawQuantity, String rawSkuId)
    {
        Long cartId = parsePositiveId(rawCartId);
        Long quantity = parsePositiveId(rawQuantity);
        Long skuId = parsePositiveId(rawSkuId);
        requireQuantity(quantity);
        UmsMemberCart cart = selectUmsMemberCartById(cartId);
        if (cart == null || !memberId.equals(cart.getMemberId())) {
            throw new ServiceException("购物车信息不存在");
        }
        PmsProduct product = pmsProductService.selectPmsProductById(cart.getProductId());
        PmsSku sku = pmsSkuService.selectPmsSkuById(skuId);
        validateProductAndSku(product, sku, quantity);
        cart.setQuantity(quantity);
        cart.setSkuId(sku.getId());
        cart.setMemberId(memberId);
        cart.setUpdateTime(DateUtils.getNowDate());
        if (umsMemberCartMapper.updateUmsMemberCartByIdAndMemberId(cart) != 1) {
            throw new ServiceException("购物车数据已变更，请重试");
        }
        return cart.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public MemberCartVO previewSettlement(Long memberId, String rawCartIds)
    {
        Long[] cartIds = parseCartIds(rawCartIds);
        List<ViewMemberCart> carts = loadValidatedCarts(memberId, cartIds);
        BigDecimal productAmount = BigDecimal.ZERO;
        long productQuantity = 0L;
        double productWeight = 0D;
        for (ViewMemberCart cart : carts) {
            productAmount = productAmount.add(cart.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
            productQuantity = addQuantity(productQuantity, cart.getQuantity());
            productWeight += cart.getWeight() == null ? 0D : cart.getWeight().doubleValue();
        }

        MemberCartVO result = new MemberCartVO();
        result.setProductAmount(productAmount);
        result.setProductQuantity(productQuantity);
        result.setProductWeight(productWeight);
        result.setDeliveryFee(BigDecimal.ZERO);
        result.setTaxFee(BigDecimal.ZERO);
        result.setTotalAmount(productAmount);
        result.setProductList(carts);

        List<UmsMemberAddress> addresses = umsMemberAddressService.selectOwnedAddressList(memberId);
        if (addresses != null && !addresses.isEmpty()) {
            result.setAddress(addresses.get(0));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long settleCart(Long memberId, String username, String rawCartIds, String remark, String rawAddressId)
    {
        return settleCartInternal(memberId, username, rawCartIds, remark, rawAddressId, null).getFirstOrderId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CartSettlementReceipt settleCart(Long memberId, String username, String requestId, String rawCartIds,
                                            String remark, String rawAddressId)
    {
        requireRequestId(requestId);
        Long[] cartIds = parseCartIds(rawCartIds);
        String requestHash = requestHash(cartIds, remark, rawAddressId);
        CartSettlementReceipt seed = new CartSettlementReceipt();
        seed.setMemberId(memberId);
        seed.setRequestId(requestId);
        seed.setRequestHash(requestHash);
        cartSettlementReceiptMapper.insertRequest(seed);
        CartSettlementReceipt receipt = cartSettlementReceiptMapper.selectForUpdate(memberId, requestId);
        if (receipt == null) {
            throw new ServiceException("兑换请求创建失败，请重试");
        }
        if (!requestHash.equals(receipt.getRequestHash())) {
            throw new ServiceException("请求标识已用于不同的兑换内容");
        }
        if ("SUCCEEDED".equals(receipt.getStatus())) {
            return populateOrders(receipt);
        }
        if (!"PROCESSING".equals(receipt.getStatus())) {
            throw new ServiceException("兑换请求状态异常");
        }
        return settleCartInternal(memberId, username, rawCartIds, remark, rawAddressId, receipt);
    }

    @Override
    @Transactional(readOnly = true)
    public CartSettlementReceipt getSettlementReceipt(Long memberId, String requestId)
    {
        requireRequestId(requestId);
        CartSettlementReceipt receipt = cartSettlementReceiptMapper.selectOwned(memberId, requestId);
        if (receipt == null) {
            throw new ServiceException("兑换记录不存在");
        }
        return populateOrders(receipt);
    }

    private CartSettlementReceipt settleCartInternal(Long memberId, String username, String rawCartIds, String remark,
                                                     String rawAddressId, CartSettlementReceipt receipt)
    {
        Long[] cartIds = parseCartIds(rawCartIds);
        lockCartsForSettlement(memberId, cartIds);
        List<ViewMemberCart> carts = loadValidatedCarts(memberId, cartIds);
        Long addressId = parsePositiveId(rawAddressId);
        if (umsMemberAddressService.selectOwnedAddressById(addressId, memberId) == null) {
            throw new ServiceException("收货地址不正确");
        }
        UmsMemberAddress address = umsMemberAddressMapper.lockActiveUmsMemberAddressByIdAndMemberId(addressId, memberId);
        if (address == null) {
            throw new ServiceException("收货地址不正确");
        }
        UmsMember member = umsMemberService.selectUmsMemberById(memberId);
        if (member == null) {
            throw new ServiceException("操作失败");
        }

        BigDecimal totalCost = BigDecimal.ZERO;
        for (ViewMemberCart cart : carts) {
            totalCost = totalCost.add(cart.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
        }
        int pointsCost = toPointsCost(totalCost);
        if (pointsCost > 0) {
            Long point = member.getPoint();
            if (point == null || point < pointsCost) {
                throw new ServiceException("积分不足");
            }
            if (!umsMemberService.deductPoints(memberId, pointsCost)) {
                throw new ServiceException("积分扣减失败，请重试");
            }
        }

        Map<Long, List<ViewMemberCart>> byMerchant = new LinkedHashMap<>();
        for (ViewMemberCart cart : carts) {
            byMerchant.computeIfAbsent(cart.getMerchantId(), key -> new ArrayList<>()).add(cart);
        }
        Long firstOrderId = null;
        List<Long> orderIds = new ArrayList<>();
        for (Map.Entry<Long, List<ViewMemberCart>> entry : byMerchant.entrySet()) {
            List<ViewMemberCart> group = entry.getValue();
            BigDecimal groupAmount = BigDecimal.ZERO;
            long groupQuantity = 0L;
            for (ViewMemberCart cart : group) {
                groupAmount = groupAmount.add(cart.getPrice().multiply(BigDecimal.valueOf(cart.getQuantity())));
                groupQuantity = addQuantity(groupQuantity, cart.getQuantity());
            }
            OmsOrder order = new OmsOrder();
            order.setOrderType(group.get(0).getCartType());
            order.setMemberId(memberId);
            order.setMmsMerchantId(entry.getKey());
            order.setOrderSn(StringUtils.randomOrderCode());
            order.setProductQuantity(groupQuantity);
            order.setProductTotalAmount(groupAmount);
            order.setOrderTotalAmount(groupAmount);
            order.setPayAmount(groupAmount);
            order.setDeliveryFee(BigDecimal.ZERO);
            order.setPayType(group.get(0).getCartType());
            order.setStatus(1L);
            order.setReceiverName(address.getFullName());
            order.setReceiverPhone(address.getMobilePhone());
            order.setReceiverDetailAddress(address.getDetailAddress());
            order.setReceiverProvince(address.getProvince());
            order.setOrderNote(remark);
            order.setDelFlag(0);
            order.setPaymentTime(new java.util.Date());
            if (omsOrderService.insertOmsOrder(order) != 1 || order.getId() == null) {
                throw new ServiceException("订单创建失败，请重试");
            }
            orderIds.add(order.getId());
            if (firstOrderId == null) {
                firstOrderId = order.getId();
            }
            for (ViewMemberCart cart : group) {
                OmsOrderItem item = new OmsOrderItem();
                item.setOrderId(order.getId());
                item.setProductId(cart.getProductId());
                item.setSkuId(cart.getSkuId());
                item.setPic(cart.getProductPic());
                item.setProductName(cart.getProductName());
                item.setPrice(cart.getPrice());
                item.setOriginalPrice(cart.getOriginalPrice());
                item.setQuantity(cart.getQuantity());
                item.setCreateBy(username);
                item.setSpData(cart.getSkuName());
                if (omsOrderItemService.insertOmsOrderItem(item) != 1 || item.getId() == null) {
                    throw new ServiceException("订单明细创建失败，请重试");
                }
                if (pmsSkuService.deductStockForProduct(cart.getSkuId(), cart.getProductId(), cart.getQuantity()) == 0) {
                    throw new ServiceException("[" + cart.getProductName() + "]商品库存不足,请重试");
                }
                if (umsMemberCartMapper.deleteUmsMemberCartByIdAndMemberId(memberId, cart.getId()) != 1) {
                    throw new ServiceException("购物车数据已变更，请重试");
                }
            }
        }
        CartSettlementReceipt result = receipt == null ? new CartSettlementReceipt() : receipt;
        result.setFirstOrderId(firstOrderId);
        result.setOrderIds(orderIds);
        result.setTotalPoints((long) pointsCost);
        result.setStatus("SUCCEEDED");
        if (receipt != null) {
            String intentHash = intentHash(receipt.getRequestHash(), carts);
            for (int i = 0; i < orderIds.size(); i++) {
                if (cartSettlementReceiptMapper.insertOrder(receipt.getId(), orderIds.get(i), i) != 1) {
                    throw new ServiceException("兑换子单映射创建失败");
                }
            }
            if (cartSettlementReceiptMapper.markSucceeded(receipt.getId(), intentHash, firstOrderId,
                    (long) pointsCost) != 1) {
                throw new ServiceException("兑换结果保存失败");
            }
            result.setIntentHash(intentHash);
        }
        return result;
    }

    private CartSettlementReceipt populateOrders(CartSettlementReceipt receipt)
    {
        receipt.setOrderIds(cartSettlementReceiptMapper.selectOrderIds(receipt.getId()));
        return receipt;
    }

    private void requireRequestId(String requestId)
    {
        if (requestId == null || !REQUEST_ID_PATTERN.matcher(requestId).matches()) {
            throw new ServiceException("requestId格式不正确");
        }
    }

    private String requestHash(Long[] cartIds, String remark, String rawAddressId)
    {
        Long addressId = parsePositiveId(rawAddressId);
        List<Long> ordered = new ArrayList<>();
        Collections.addAll(ordered, cartIds);
        Collections.sort(ordered);
        return sha256(ordered.toString() + "\n" + addressId + "\n"
                + (remark == null ? "N" : "S" + remark.length() + ":" + remark));
    }

    private String intentHash(String requestHash, List<ViewMemberCart> carts)
    {
        List<String> lines = new ArrayList<>();
        for (ViewMemberCart cart : carts) {
            lines.add(cart.getId() + ":" + cart.getMerchantId() + ":" + cart.getProductId() + ":"
                    + cart.getSkuId() + ":" + cart.getQuantity() + ":" + cart.getPrice().toPlainString());
        }
        Collections.sort(lines);
        return sha256(requestHash + "\n" + lines.toString());
    }

    private String sha256(String value)
    {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private List<ViewMemberCart> loadValidatedCarts(Long memberId, Long[] cartIds)
    {
        if (cartIds == null || cartIds.length == 0) {
            throw new ServiceException("商品信息不正确");
        }
        Set<Long> requestedIds = new LinkedHashSet<>();
        for (Long cartId : cartIds) {
            if (cartId == null || cartId <= 0 || !requestedIds.add(cartId)) {
                throw new ServiceException("购物车数据异常");
            }
        }
        List<ViewMemberCart> carts = viewMemberCartService.selectViewMemberCartByIds(cartIds);
        if (carts == null || carts.size() != requestedIds.size()) {
            throw new ServiceException("购物车数据异常");
        }
        Set<Long> returnedIds = new LinkedHashSet<>();
        for (ViewMemberCart cart : carts) {
            if (cart == null || !requestedIds.contains(cart.getId()) || !returnedIds.add(cart.getId())
                    || !memberId.equals(cart.getMemberId())) {
                throw new ServiceException("购物车数据异常");
            }
            requireQuantity(cart.getQuantity());
            PmsProduct product = pmsProductService.selectPmsProductById(cart.getProductId());
            PmsSku sku = pmsSkuService.selectPmsSkuById(cart.getSkuId());
            validateProductAndSku(product, sku, cart.getQuantity());
            cart.setMerchantId(product.getMerchantId());
            applyCurrentSkuSnapshot(cart, sku);
        }
        return carts;
    }

    private void lockCartsForSettlement(Long memberId, Long[] cartIds)
    {
        List<Long> orderedCartIds = new ArrayList<>();
        Collections.addAll(orderedCartIds, cartIds);
        Collections.sort(orderedCartIds);
        for (Long cartId : orderedCartIds) {
            if (umsMemberCartMapper.lockUmsMemberCartByIdAndMemberId(memberId, cartId) == null) {
                throw new ServiceException("购物车数据已变更，请重试");
            }
        }
    }

    private Long[] parseCartIds(String rawCartIds)
    {
        if (StringUtils.isEmpty(rawCartIds)) {
            throw new ServiceException("购物车ID不能为空");
        }
        String[] parts = rawCartIds.split(",", -1);
        Set<Long> ids = new LinkedHashSet<>();
        for (String part : parts) {
            try {
                Long id = Long.valueOf(part);
                if (id <= 0 || !ids.add(id)) {
                    throw new ServiceException("购物车数据异常");
                }
            } catch (NumberFormatException exception) {
                throw new ServiceException("购物车数据异常");
            }
        }
        return ids.toArray(new Long[0]);
    }

    private Long parsePositiveId(String rawValue)
    {
        if (StringUtils.isEmpty(rawValue)) {
            throw new ServiceException("参数错误");
        }
        try {
            Long value = Long.valueOf(rawValue);
            if (value <= 0) {
                throw new ServiceException("参数错误");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ServiceException("参数错误");
        }
    }

    private Long parseIsBuy(String rawIsBuy)
    {
        if (StringUtils.isEmpty(rawIsBuy)) {
            throw new ServiceException("参数错误");
        }
        try {
            Long isBuy = Long.valueOf(rawIsBuy);
            if (isBuy != 0 && isBuy != 1) {
                throw new ServiceException("参数错误");
            }
            return isBuy;
        } catch (NumberFormatException exception) {
            throw new ServiceException("参数错误");
        }
    }

    private void validateProductAndSku(PmsProduct product, PmsSku sku, Long quantity)
    {
        if (product == null || !Integer.valueOf(1).equals(product.getPublishStatus())
                || (product.getDelFlag() != null && product.getDelFlag() != 0)) {
            throw new ServiceException("商品信息不存在或已下架");
        }
        if (sku == null || sku.getProductId() == null || !sku.getProductId().equals(product.getId())) {
            throw new ServiceException("商品规格不匹配");
        }
        if (sku.getPrice() == null || sku.getPrice().signum() < 0) {
            throw new ServiceException("商品价格异常");
        }
        if (sku.getStock() == null || sku.getStock() < quantity) {
            throw new ServiceException("商品库存不足");
        }
    }

    private void applyCurrentSkuSnapshot(ViewMemberCart cart, PmsSku sku)
    {
        cart.setPrice(sku.getPrice());
        cart.setOriginalPrice(sku.getOriginalPrice());
        cart.setSkuName(sku.getSkuName());
        cart.setSkuNameAr(sku.getSkuNameAr());
        cart.setSkuPic(sku.getPic());
    }

    private void requireQuantity(Long quantity)
    {
        if (quantity == null || quantity <= 0 || quantity > MAX_CART_QUANTITY) {
            throw new ServiceException("购买数量不合法");
        }
    }

    private long addQuantity(long current, Long next)
    {
        try {
            long total = Math.addExact(current, next);
            if (total > MAX_CART_QUANTITY) {
                throw new ServiceException("购买数量超过上限");
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new ServiceException("购买数量超过上限");
        }
    }

    private int toPointsCost(BigDecimal totalCost)
    {
        try {
            return totalCost.setScale(0, RoundingMode.CEILING).intValueExact();
        } catch (ArithmeticException exception) {
            throw new ServiceException("订单积分金额超过上限");
        }
    }

}
