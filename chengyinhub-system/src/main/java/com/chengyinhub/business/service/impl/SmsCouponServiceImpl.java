package com.chengyinhub.business.service.impl;

import java.util.List;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.CmsConstants;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.service.ISmsCouponService;

/**
 * 优惠券Service业务层处理
 * 
 * @author webxh
 * @date 2025-09-01
 */
@Service
public class SmsCouponServiceImpl implements ISmsCouponService 
{
    @Autowired
    private SmsCouponMapper smsCouponMapper;

    /** 券出示码 HMAC 密钥:只从配置/环境变量注入,不入库。见 application.yml coupon.qr-secret */
    @Value("${coupon.qr-secret:}")
    private String couponQrSecret;

    /** 缺配置只关闭券码功能；应用其他业务仍可启动。 */
    public void requireCouponQrSecret() {
        if (couponQrSecret == null || couponQrSecret.trim().isEmpty()) {
            throw new ServiceException("优惠券出示码暂不可用：独立签名密钥未配置，请联系管理员", 503);
        }
    }

    /**
     * 查询优惠券
     * 
     * @param id 优惠券主键
     * @return 优惠券
     */
    @Override
    public SmsCoupon selectSmsCouponById(Long id)
    {
        return smsCouponMapper.selectSmsCouponById(id);
    }

    /**
     * 查询优惠券列表
     * 
     * @param smsCoupon 优惠券
     * @return 优惠券
     */
    @Override
    public List<SmsCoupon> selectSmsCouponList(SmsCoupon smsCoupon)
    {
        return smsCouponMapper.selectSmsCouponList(smsCoupon);
    }

    /**
     * 新增优惠券
     * 
     * @param smsCoupon 优惠券
     * @return 结果
     */
    @Override
    public int insertSmsCoupon(SmsCoupon smsCoupon)
    {
        smsCoupon.setCreateTime(DateUtils.getNowDate());
        return smsCouponMapper.insertSmsCoupon(smsCoupon);
    }

    /**
     * 修改优惠券
     * 
     * @param smsCoupon 优惠券
     * @return 结果
     */
    @Override
    public int updateSmsCoupon(SmsCoupon smsCoupon)
    {
        smsCoupon.setUpdateTime(DateUtils.getNowDate());
        return smsCouponMapper.updateSmsCoupon(smsCoupon);
    }

    /**
     * 批量删除优惠券
     * 
     * @param ids 需要删除的优惠券主键
     * @return 结果
     */
    @Override
    public int deleteSmsCouponByIds(Long[] ids)
    {
        return smsCouponMapper.deleteSmsCouponByIds(ids);
    }

    /**
     * 删除优惠券信息
     * 
     * @param id 优惠券主键
     * @return 结果
     */
    @Override
    public int deleteSmsCouponById(Long id)
    {
        return smsCouponMapper.deleteSmsCouponById(id);
    }

    /**
     * 批量更新指定用户的过期优惠券状态
     * @param memberId 用户ID（发布优惠券的用户）
     */
    @Override
    public int updateExpiredCouponsByMemberId(Long memberId){
        return smsCouponMapper.updateExpiredCouponsByMemberId(memberId);
    }

    @Override
    public int selectSmsCouponNumByMemberId(Long memberId){
        return smsCouponMapper.selectSmsCouponNumByMemberId(memberId);
    }

    @Override
    public int incrementUseCount(Long id){
        return smsCouponMapper.incrementUseCount(id);
    }

    @Override
    public java.util.Map<Long, Integer> selectCouponStatuses(java.util.List<Long> ids) {
        java.util.Map<Long, Integer> statusById = new java.util.HashMap<>();
        if (ids == null || ids.isEmpty()) {
            return statusById;
        }
        java.util.List<SmsCoupon> rows = smsCouponMapper.selectCouponStatusByIds(ids);
        if (rows == null) {
            return statusById;
        }
        for (SmsCoupon row : rows) {
            if (row != null && row.getId() != null) {
                statusById.put(row.getId(), row.getStatus());
            }
        }
        return statusById;
    }

    @Override
    public int stopIssuingCoupon(Long id, Long ownerMemberId) {
        if (id == null || id <= 0 || ownerMemberId == null || ownerMemberId <= 0) {
            return 0;
        }
        return smsCouponMapper.stopIssuingCoupon(id, ownerMemberId);
    }

    @Override
    public String generateCouponQrToken(Long historyId){
        requireCouponQrSecret();
        return VerifyDynCodeUtil.issueCoupon(historyId, System.currentTimeMillis() / 1000L, couponQrSecret);
    }

    @Override
    public Long verifyCouponQrToken(String token){
        requireCouponQrSecret();
        return VerifyDynCodeUtil.verifyCoupon(token, System.currentTimeMillis() / 1000L,
                CmsConstants.COUPON_QR_TTL_SEC, couponQrSecret);
    }
}
