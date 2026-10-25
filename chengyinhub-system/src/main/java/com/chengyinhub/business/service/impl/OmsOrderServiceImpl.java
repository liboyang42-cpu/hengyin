package com.chengyinhub.business.service.impl;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.math.BigDecimal;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.common.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.OmsOrderMapper;
import com.chengyinhub.business.domain.OmsOrder;
import com.chengyinhub.business.service.IOmsOrderService;

/**
 * 订单Service业务层处理
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
@Service
public class OmsOrderServiceImpl implements IOmsOrderService 
{
    @Autowired
    private OmsOrderMapper omsOrderMapper;

    /**
     * 查询订单
     * 
     * @param id 订单主键
     * @return 订单
     */
    @Override
    public OmsOrder selectOmsOrderById(Long id)
    {
        return omsOrderMapper.selectOmsOrderById(id);
    }

    /**
     * 查询订单列表
     * 
     * @param omsOrder 订单
     * @return 订单
     */
    @Override
    public List<OmsOrder> selectOmsOrderList(OmsOrder omsOrder)
    {
        return omsOrderMapper.selectOmsOrderList(omsOrder);
    }

    /**
     * 新增订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    @Override
    public int insertOmsOrder(OmsOrder omsOrder)
    {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        if (omsOrder == null) throw invalidOrder();
        requireNonCommerceInput(omsOrder);
        omsOrder.setCreateTime(DateUtils.getNowDate());
        return omsOrderMapper.insertOmsOrder(omsOrder);
    }

    /**
     * 修改订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateOmsOrder(OmsOrder omsOrder)
    {
        requireTransaction();
        if (omsOrder == null || omsOrder.getId() == null || omsOrder.getId() <= 0) throw invalidOrder();
        OmsOrder current = omsOrderMapper.selectOmsOrderByIdForUpdate(omsOrder.getId());
        if (current == null) return 0;
        if (!isCommerce(current)) {
            // A normal order cannot be retagged into the dedicated checkout's namespace.
            requireNonCommerceInput(omsOrder);
            omsOrder.setUpdateTime(DateUtils.getNowDate());
            return omsOrderMapper.updateOmsOrder(omsOrder);
        }
        requireFrozenCommerce(current, omsOrder);
        // Never forward the submitted entity to the broad Mapper for a Commerce order.
        if ((omsOrder.getOrderNote() == null || Objects.equals(omsOrder.getOrderNote(), current.getOrderNote()))
                && (omsOrder.getMerchantNote() == null || Objects.equals(omsOrder.getMerchantNote(), current.getMerchantNote()))) return 1;
        OmsOrder notes = new OmsOrder();
        notes.setId(current.getId());
        notes.setOrderNote(omsOrder.getOrderNote());
        notes.setMerchantNote(omsOrder.getMerchantNote());
        notes.setUpdateBy(omsOrder.getUpdateBy());
        notes.setUpdateTime(DateUtils.getNowDate());
        if (omsOrderMapper.updateOmsOrder(notes) != 1) throw conflict();
        return 1;
    }

    /**
     * 批量删除订单
     * 
     * @param ids 需要删除的订单主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOmsOrderByIds(Long[] ids)
    {
        return deleteGenericOrders(ids);
    }

    /**
     * 删除订单信息
     * 
     * @param id 订单主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteOmsOrderById(Long id)
    {
        return deleteGenericOrders(new Long[]{id});
    }

    private int deleteGenericOrders(Long[] ids)
    {
        requireTransaction();
        if (ids == null || ids.length == 0) throw invalidOrder();
        TreeSet<Long> ordered = new TreeSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) throw invalidOrder();
            ordered.add(id);
        }
        List<OmsOrder> locked = new ArrayList<>();
        for (Long id : ordered) {
            OmsOrder row = omsOrderMapper.selectOmsOrderByIdForUpdate(id);
            if (row != null) locked.add(row);
        }
        // Finish all current reads before deciding or writing; a mixed batch is all-or-nothing.
        for (OmsOrder row : locked) if (isCommerce(row)) throw conflict();
        if (locked.isEmpty()) return 0;
        Long[] present = locked.stream().map(OmsOrder::getId).toArray(Long[]::new);
        int deleted = omsOrderMapper.deleteOmsOrderByIds(present);
        if (deleted != present.length) throw conflict();
        return deleted;
    }

    private static boolean isCommerce(OmsOrder row)
    {
        String type = row.getBizType() == null ? "" : row.getBizType().trim().toLowerCase(Locale.ROOT);
        String no = row.getOrderSn() == null ? "" : row.getOrderSn().trim().toUpperCase(Locale.ROOT);
        return "premium_template".equals(type) || "promotion_slot".equals(type) || "custom_event".equals(type)
                || no.startsWith("TPLTM_") || no.startsWith("PROMOT_") || no.startsWith("CUSTOM_")
                || row.getEntitlementDays() != null || row.getEntitlementMaxUsage() != null
                || (row.getCommerceRequestId() != null && !row.getCommerceRequestId().trim().isEmpty())
                || row.getVerifiedPaymentFactId() != null || row.getVerifiedPaymentFactHash() != null;
    }

    private static void requireNonCommerceInput(OmsOrder row)
    {
        if (isCommerce(row)) throw conflict();
    }

    private static void requireFrozenCommerce(OmsOrder original, OmsOrder submitted)
    {
        if (changed(submitted.getMemberId(), original.getMemberId())
                || changed(submitted.getMmsMerchantId(), original.getMmsMerchantId())
                || changed(submitted.getOrderSn(), original.getOrderSn())
                || changed(submitted.getOrderType(), original.getOrderType())
                || changed(submitted.getBizType(), original.getBizType())
                || changed(submitted.getPayId(), original.getPayId())
                || changed(submitted.getProductQuantity(), original.getProductQuantity())
                || changed(submitted.getProductTotalAmount(), original.getProductTotalAmount())
                || changed(submitted.getDeliveryFee(), original.getDeliveryFee())
                || changed(submitted.getPayAmount(), original.getPayAmount())
                || changed(submitted.getTaxFee(), original.getTaxFee())
                || changed(submitted.getOrderTotalAmount(), original.getOrderTotalAmount())
                || changed(submitted.getPointsAmount(), original.getPointsAmount())
                || changed(submitted.getCouponAmount(), original.getCouponAmount())
                || changed(submitted.getMemberCouponId(), original.getMemberCouponId())
                || changed(submitted.getPayType(), original.getPayType())
                || changed(submitted.getStatus(), original.getStatus())
                || changed(submitted.getAftersaleStatus(), original.getAftersaleStatus())
                || changed(submitted.getConfirmStatus(), original.getConfirmStatus())
                || changed(submitted.getAutoConfirmDay(), original.getAutoConfirmDay())
                || changed(submitted.getPaymentTime(), original.getPaymentTime())
                || changed(submitted.getDeliveryTime(), original.getDeliveryTime())
                || changed(submitted.getReceiveTime(), original.getReceiveTime())
                || changed(submitted.getDelFlag(), original.getDelFlag())
                || changed(submitted.getPaymentTransactionId(), original.getPaymentTransactionId())
                || changed(submitted.getEntitlementDays(), original.getEntitlementDays())
                || changed(submitted.getEntitlementMaxUsage(), original.getEntitlementMaxUsage())
                || changed(submitted.getCommerceRequestId(), original.getCommerceRequestId())
                || changed(submitted.getVerifiedPaymentFactId(), original.getVerifiedPaymentFactId())
                || changed(submitted.getVerifiedPaymentFactHash(), original.getVerifiedPaymentFactHash())) throw conflict();
    }

    private static boolean changed(Object submitted, Object original)
    {
        if (submitted == null) return false; // Existing dynamic UPDATE does not write a null input.
        if (submitted instanceof BigDecimal a && original instanceof BigDecimal b) return a.compareTo(b) != 0;
        return !Objects.equals(submitted, original);
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("通用订单当前读与写入必须在同一事务");
    }

    private static ServiceException invalidOrder()
    {
        return new ServiceException("订单参数不合法", HttpStatus.BAD_REQUEST);
    }

    private static ServiceException conflict()
    {
        return new ServiceException("商业化订单由专用结账与支付流程维护，不能通过通用入口修改或删除", HttpStatus.CONFLICT);
    }
}
