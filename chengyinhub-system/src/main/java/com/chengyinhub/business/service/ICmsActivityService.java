package com.chengyinhub.business.service;

import java.util.List;
import java.util.Map;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.enums.PublicActivityScope;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动Service接口
 *
 * @author webxh
 * @date 2025-08-31
 */
public interface ICmsActivityService
{

    /**
     * 获取需要结算的活动列表
     * @return 活动列表
     */
    List<CmsActivity> selectActivitiesForSettlement();

    /**
     * 余额三段展示用:按当前活动结算费率估算这笔实付里收款方能拿到的份额(与结算同一算法)。
     * 费率配置非法时返回 null(展示「待确认」,不编数)。
     */
    java.math.BigDecimal estimateMerchantShare(CmsActivity activity, java.math.BigDecimal sales);

    @Transactional(rollbackFor = Exception.class)
    int executeActivitySettlement();

    /**
     * 查询活动
     *
     * @param id 活动主键
     * @return 活动
     */
    /**
     * 该活动下【已付款】的报名数。
     * 用于删除前置闸:删除只软删活动、不退款不回补库存,有已付款报名时删掉活动会让用户的钱悬空,
     * 必须先走「取消活动」(整单全额退)。
     */
    int countPaidRegistrations(Long activityId);

    /**
     * 该活动下【待支付】({@code payment_status=0})的报名数。
     *
     * <p>与 {@link #countPaidRegistrations} 分工:那个是「钱已在平台」,这个是「钱随时可能进来」——
     * 删除都得拦。只拦前者的话,玩家拉起支付、回调还没到时删活动会放行,回调到达把单置 2
     * ⇒ 钱收了、单成了、活动没了(W9)。
     *
     * <p>⚠️ 不按 {@code pay_expire_time} 放过「过期弃单」:该字段后端无强制力,过期单照样付得了。
     * 弃单解堵靠 {@code orderCloseTask} 关单成 3。
     */
    int countPendingRegistrations(Long activityId);

    /**
     * 开卖报名数：cms_registration.owner_type=2 且 payment_status ∈ {0待支付,1支付中,2已支付}。
     * 已关单/失败(3)与已退款(4)不算开卖。未支付也算开卖。
     */
    int countOpenSaleRegistrations(Long activityId);

    /**
     * 已报名人数：cms_registration.owner_type=2 且 registration_status=2。
     *
     * <p>这是「报名数」这个词在本仓的唯一下发口径(活动详情 registrationCount、
     * {@code ClubEventOpsMapper} 的 signup_count 都是它)。给展示位用它,别用
     * {@link #countOpenSaleRegistrations} —— 那个判据是「钱随时可能进来」的支付状态,
     * 待支付也算数,与详情页并排看必然打架(CU-M-175)。
     */
    int countSignedUpRegistrations(Long activityId);

    /** 批量读取项目卡报名人数，与单场详情使用同一报名口径。 */
    Map<Long, Integer> countSignedUpRegistrationsByActivityIds(List<Long> activityIds);

    /** 主题及对应俱乐部场次的有效报名，批量读取项目卡统计。 */
    Map<Long, Integer> countSignedUpRegistrationsByTopicIds(List<Long> topicIds);

    /** 商家承办项目已支付、尚未核销的人数。 */
    Map<Long, Integer> countPendingVerificationByActivityIds(List<Long> activityIds);

    public CmsActivity selectCmsActivityById(Long id);

    /** 查询对普通用户公开可见的活动；隐藏状态统一返回 null。 */
    CmsActivity selectPublicCmsActivityById(Long id);

    /** 普通用户只见公开状态；创建者可复用既有身份 seam 预览自己的未删活动。 */
    CmsActivity selectCmsActivityDetailById(Long id, Long viewerMemberId);

    /**
     * 查询活动列表
     *
     * @param cmsActivity 活动
     * @return 活动集合
     */
    public List<CmsActivity> selectCmsActivityList(CmsActivity cmsActivity);

    /**
     * 查询公开活动。status / publish_status / del_flag / cancel_time 四道闸由 Mapper 写死，
     * 忽略调用方携带的状态字段；「出不出社群活动」「出不出已结束的」两道由 {@code scope} 决定。
     *
     * <p>scope 是必填参数而不是可选开关 —— 参见 {@link PublicActivityScope} 里记的那次实际漏洞：
     * 它们当可选 {@code <if>} 存在时，四个调用方拼出了四套不同的闸。
     */
    List<CmsActivity> selectPublicCmsActivityList(CmsActivity cmsActivity, PublicActivityScope scope);

    /**
     * 新增活动
     *
     * @param cmsActivity 活动
     * @return 结果
     */
    public int insertCmsActivity(CmsActivity cmsActivity);

    /**
     * 修改活动
     *
     * @param cmsActivity 活动
     * @return 结果
     */
    public int updateCmsActivity(CmsActivity cmsActivity);

    /**
     * 批量删除活动
     *
     * @param ids 需要删除的活动主键集合
     * @return 结果
     */
    public int deleteCmsActivityByIds(Long[] ids);

    /**
     * 删除活动信息
     *
     * @param id 活动主键
     * @return 结果
     */
    public int deleteCmsActivityById(Long id);

    /**
     * 发布活动
     */
    public Long publishActivity(CmsActivityPublishRequest request, Long memberId,String username);

    /**
     * 小程序更新活动内容（未开卖）。票务改动不得把已删票 del_flag 改回 0。
     */
    int updateActivityByOwner(CmsActivityPublishRequest request, Long memberId, String username);


    /**
     * 查询用户发布活动数
     *
     * @param member_id 用户ID
     * @return 结果
     */
    public int selectCmsActivityNumByMemberId(Long member_id);
}
