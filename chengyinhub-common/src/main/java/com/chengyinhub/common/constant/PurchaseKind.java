package com.chengyinhub.common.constant;

/**
 * P0-02 产品真源:订单级购买类型(cms_registration.purchase_kind)。
 *
 * <p>施工图 §3.1:由「ticketId 有无 + product_type」推导,**报名落库时固化**。
 * 固化而非每次现算,是因为主题的 product_type 可能被改,而已售出的订单该按买的时候算 ——
 * 退款档位/结算口径都挂在它上面。
 *
 * <ul>
 *   <li>{@link #GUIDED_TICKET} 1 = ① 有人带票:有 ticketId 且主题为 {@link ProductType#ORIENTEERING}</li>
 *   <li>{@link #SELF_PLAY_PASS} 2 = ② 自玩通行证:无 ticketId(createSelfPlayPass 不落 ticketId)</li>
 *   <li>{@link #EXPLORE_PASS} 3 = ③ 自由探索票:有 ticketId 且主题为 {@link ProductType#FREE_EXPLORE}</li>
 * </ul>
 *
 * <p><b>NULL</b> 有三个来源,别以为「新单必非空」:
 * <ul>
 *   <li>存量未回填(迁移刻意不静默回填判不清的)</li>
 *   <li>**后台手工建单** —— CmsRegistrationController 的通用 add 走 insertCmsRegistration,
 *       没有 topic/activity 上下文可推,故留空。这是第三条建单路径,不在 createRegistration /
 *       createSelfPlayPass 两条之内。</li>
 *   <li>独立活动(无 topicId)的订单 —— 没有主题真源可溯</li>
 * </ul>
 * 读侧一律回落既有的隐式推断(owner_type + ticket_id + expires_at 组合约定),
 * **不许写「新单必非空」这类判定**。
 */
public final class PurchaseKind {
    private PurchaseKind() {}

    /** 1 = ① 有人带票 */
    public static final int GUIDED_TICKET = 1;

    /** 2 = ② 自玩通行证 */
    public static final int SELF_PLAY_PASS = 2;

    /** 3 = ③ 自由探索票 */
    public static final int EXPLORE_PASS = 3;

    /**
     * 施工图 §1 的过渡谓词落码:由 ticketId 有无 + 主题 product_type 推导购买类型。
     *
     * @param ticketId    报名单的票 id(null = 自玩通行证)
     * @param productType 主题产品类型;为 null(存量未回填)时按经典定向处理 ——
     *                    与 resolveMode 的历史兜底同口径({@link PlayMode} 契约已写明)
     * @return 购买类型
     */
    public static int derive(Long ticketId, Integer productType) {
        if (ticketId == null) {
            return SELF_PLAY_PASS;
        }
        return productType != null && productType == ProductType.FREE_EXPLORE
                ? EXPLORE_PASS : GUIDED_TICKET;
    }
}
