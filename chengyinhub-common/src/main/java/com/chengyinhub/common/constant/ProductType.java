package com.chengyinhub.common.constant;

/**
 * P0-02 产品真源:主题级产品类型(cms_topic.product_type)。
 *
 * <p>施工图 §3.1 的落地。在此之前「这是哪种产品」没有任何字段能回答,靠五个间接信号共同猜,
 * 其中唯一的「权威」是 {@code oms_ticket.mode} —— 语义是主题级、存储却在票级,于是混票成了默认行为。
 * 本类把产品类型上收到主题,{@code oms_ticket.mode} 随之降级为由本值派生的冗余列。
 *
 * <p>值域刻意与 {@link PlayMode} / {@code oms_ticket.mode} 同值(1/2),使派生是恒等映射、
 * 存量回填无需换算:
 * <ul>
 *   <li>{@link #ORIENTEERING} 1 = 经典定向 —— ① 有人带票 与 ② 自玩通行证**共存于同一主题**,
 *       所以四种产品不是四种主题类型(施工图 §3.1);对应 {@link PlayMode#LINEAR_ORIENTEERING}</li>
 *   <li>{@link #FREE_EXPLORE} 2 = 自由定向 —— ③;对应 {@link PlayMode#FREE_COLLECT}</li>
 * </ul>
 *
 * <p>④ ROAM_FREE 是独立栈(ApiRoamController/RoamSession/RoamPoi),不进 cms_topic,故无对应值。
 *
 * <p><b>NULL 的含义</b>:存量未回填,或混票主题待人工定型(迁移
 * {@code migration_product_type_purchase_kind_20260716.sql} 刻意不静默回填这批)。
 * 读侧须按「非空用真源、为空回落票 mode 推断」双读,见 {@code ApiPlayProgressController#resolveMode}。
 */
public final class ProductType {
    private ProductType() {}

    /** 1 = 经典定向(ORIENTEERING):①有人带票 + ②自玩通行证共存 */
    public static final int ORIENTEERING = 1;

    /** 2 = 自由定向(FREE_EXPLORE):③ */
    public static final int FREE_EXPLORE = 2;

    /** 产品类型 → 票 mode(恒等映射;票 mode 已是派生列,不再是真源)。 */
    public static Integer toTicketMode(Integer productType) {
        return productType == null ? null : productType;
    }

    /** 是否合法取值(仅 1/2;NULL 视为不合法 —— 校验入参时用,别拿它判存量)。 */
    public static boolean isValid(Integer productType) {
        return productType != null && (productType == ORIENTEERING || productType == FREE_EXPLORE);
    }
}
