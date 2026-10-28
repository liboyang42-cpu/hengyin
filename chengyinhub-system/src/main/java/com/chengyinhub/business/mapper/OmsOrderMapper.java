package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Date;
import java.util.Map;
import com.chengyinhub.business.domain.OmsOrder;
import org.apache.ibatis.annotations.Param;

/**
 * 订单Mapper接口
 * 
 * @author ruoyi
 * @date 2025-08-25
 */
public interface OmsOrderMapper 
{
    /**
     * 查询订单
     * 
     * @param id 订单主键
     * @return 订单
     */
    public OmsOrder selectOmsOrderById(Long id);

    /** Original row/current scope for generic write admission; caller retains the transaction lock. */
    OmsOrder selectOmsOrderByIdForUpdate(@Param("id") Long id);

    public OmsOrder selectOmsOrderByOrderSn(String orderSn);

    public OmsOrder selectCommerceOrderByRequest(@Param("memberId") Long memberId,
                                                  @Param("merchantId") Long merchantId,
                                                  @Param("bizType") String bizType,
                                                  @Param("requestId") String requestId);

    /** 商业化支付回调 CAS，只有待支付订单能首次置为已支付。 */
    public int markCommerceOrderPaid(@org.apache.ibatis.annotations.Param("id") Long id,
                                     @org.apache.ibatis.annotations.Param("transactionId") String transactionId);

    List<OmsOrder> selectPendingCommerceOrders(@Param("afterId") long afterId,
                                              @Param("before") Date before,
                                              @Param("limit") int limit);

    int markCommerceOrderClosed(@Param("id") Long id);

    /**
     * 查询订单列表
     * 
     * @param omsOrder 订单
     * @return 订单集合
     */
    public List<OmsOrder> selectOmsOrderList(OmsOrder omsOrder);

    /** 查询商家经营台账订单，供分页插件拦截。 */
    public List<OmsOrder> selectMerchantLedgerOrders(@Param("merchantId") Long merchantId,
                                                      @Param("status") Long status,
                                                      @Param("aftersaleOnly") Boolean aftersaleOnly,
                                                      @Param("beginTime") Date beginTime,
                                                      @Param("endTime") Date endTime);

    /** 查询与商家经营台账订单相同口径的汇总数据。 */
    public Map<String, Object> selectMerchantLedgerSummary(@Param("merchantId") Long merchantId,
                                                            @Param("status") Long status,
                                                            @Param("aftersaleOnly") Boolean aftersaleOnly,
                                                            @Param("beginTime") Date beginTime,
                                                            @Param("endTime") Date endTime);

    /**
     * 新增订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    public int insertOmsOrder(OmsOrder omsOrder);

    /**
     * 修改订单
     * 
     * @param omsOrder 订单
     * @return 结果
     */
    public int updateOmsOrder(OmsOrder omsOrder);

    /**
     * 删除订单
     * 
     * @param id 订单主键
     * @return 结果
     */
    public int deleteOmsOrderById(Long id);

    /**
     * 批量删除订单
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteOmsOrderByIds(Long[] ids);

    /** PII 加密历史回填:未迁移(receiver_phone_hash 为空且有收货手机)的订单 id,限批。 */
    public List<Long> selectLegacyReceiverPiiIds(@Param("limit") int limit);

    /** PII 加密历史回填:按 id 用密文覆盖收货 PII + 写盲索引(值解密后传入,typeHandler 负责加密)。 */
    public int backfillOrderReceiverPii(@Param("id") Long id,
                                        @Param("receiverName") String receiverName,
                                        @Param("receiverPhone") String receiverPhone,
                                        @Param("receiverDetailAddress") String receiverDetailAddress);
    OmsOrder selectCommerceOrderForUpdate(@Param("orderSn") String orderSn);
    int bindCommercePaymentProof(@Param("order") OmsOrder order, @Param("transactionId") String transactionId,
            @Param("factId") Long factId, @Param("factHash") String factHash);
}
