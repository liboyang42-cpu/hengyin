package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Redemption;
import com.chengyinhub.business.domain.RegistrationEntitlement;

import java.util.List;
import java.util.Map;

/**
 * P0-13/14/15/16/17 权益与核销账本(③ 自由定向的心脏)。
 *
 * <p>模型:玩家买一张通票 → 每个必选章节到一家商家领一份权益。三张表分工:
 * <ul>
 *   <li>{@code merchant_chapter_offer} —— 这一章有谁在供给、还剩多少(执行层;申报层是 coop_perk)</li>
 *   <li>{@code registration_entitlement} —— 每票每必选章的应得/已得/失效(**容量预占的真源**)</li>
 *   <li>{@code redemption} —— 核销流水:不可变事件 + 反向撤销</li>
 * </ul>
 *
 * <p><b>容量定案(§3.2)</b>:{@code 章节可续售容量 = Σ(生效offer的 quota_total − quota_used)
 * − 该章已售未核销 entitlement 数}。售出一张 5 必选章通票的瞬间,平台已欠玩家 5 份章节权益 ——
 * **未核销也占容量**。
 */
public interface IPerkLedgerService {

    /** §3.2 的容量公式。返回该章还能再卖几张票(可能为负,表示已超卖 —— 调用方须当缺口处理)。 */
    int chapterCapacity(Long chapterId);

    /**
     * P0-16 票库存闸:该主题还能再卖几张 = min(各必选章可续售容量)。
     * 无必选章返回 {@link Integer#MAX_VALUE}(不限);任一章为 0 即不能再卖。
     */
    int topicSellableCapacity(Long topicId);

    /**
     * P0-16 安全垫:覆盖率 = Σ(生效offer剩余) ÷ 已售未核销 entitlement 数。
     * &lt;1.2 黄色预警(补招商/催配额)、&lt;1.0 = 缺口事件硬停售。已售未核销为 0 时返回
     * {@link Double#POSITIVE_INFINITY}(还没卖出去,谈不上缺口)。
     */
    double chapterCoverageRatio(Long chapterId);

    /** 覆盖率档:供给充足。 */
    int COVERAGE_OK = 0;
    /** 覆盖率档:安全垫不足(&lt;1.2)—— 黄色预警,补招商/催配额。 */
    int COVERAGE_WARNING = 1;
    /** 覆盖率档:缺口(&lt;1.0)—— 已售权益兑现不了,硬停售。 */
    int COVERAGE_GAP = 2;

    /**
     * P0-16 覆盖率档位判定 —— <b>阈值的唯一真源</b>。
     *
     * <p><b>为什么需要它</b>:{@link #chapterCoverageRatio} 只返一个裸 double,而 javadoc 承诺的
     * 「&lt;1.2 黄色预警、&lt;1.0 缺口硬停售」两条规则**在被调方一行都不存在** ——
     * {@code COVERAGE_WARN} 常量声明了却零读取。规则若由每个调用方各写一遍,
     * 下次改阈值必漏一处。
     *
     * <p>它替调用方处理了两个坑:
     * <ul>
     *   <li>{@code POSITIVE_INFINITY}(还没卖出去,谈不上缺口)⇒ 返 {@link #COVERAGE_OK};
     *       裸拿它做算术/格式化会输出 "Infinity";</li>
     *   <li>{@code chapterId == null} ⇒ {@code chapterCoverageRatio} 内部**无 null 保护**
     *       (不同于 chapterCapacity),会静默返 Infinity ⇒ 这里显式挡掉。</li>
     * </ul>
     *
     * @return {@link #COVERAGE_OK} / {@link #COVERAGE_WARNING} / {@link #COVERAGE_GAP}
     */
    int chapterCoverageStatus(Long chapterId);

    /**
     * P0-16 主题级覆盖率档:取各必选章里**最差**的一档(木桶效应 —— 一章兑现不了,整张票就毁了)。
     *
     * <p>⚠️ 与 {@link #topicSellableCapacity} 的区别:后者算「还能再卖几张」,本方法答
     * 「已经卖出去的兑现得了吗」。无必选章 ⇒ {@link #COVERAGE_OK}(不受权益容量约束)。
     *
     * <p>缺口档会打 warn 日志。⚠️ <b>缺口的真正处置</b>(替代商家 / 整单全额退)是独立工作流,
     * 本方法只负责**让它被看见**。
     */
    int topicCoverageStatus(Long topicId);

    /**
     * P0-15 购票路径:**一个事务内**校验全部必选章容量并创建 entitlement。
     *
     * <p>调用方须自带事务(本方法标 REQUIRED,会加入调用方的事务)。全程按
     * cms_topic_chapter → registration_entitlement 的顺序加锁,章节 id 升序,防 ABBA。
     *
     * @throws RuntimeException 任一必选章容量不足 ⇒ 整单拒绝(不允许只发一半权益)
     */
    void reserveEntitlements(Long registrationId, Long topicId);

    /**
     * 退款/取消:失效该票**未核销**的权益,释放容量。
     * 已核销的不动 —— 玩家已经拿到手,改成失效会让容量凭空多出来一份。
     *
     * @param reason REFUND / ORGANIZER_CANCEL / FULFILLMENT_GAP / MANUAL
     * @return 失效的条数
     */
    int releaseOnRefund(Long registrationId, String reason);

    /**
     * P0-17 批量通关口径的结果:一次查询同时给出「有过核销的人」与「已通关的人」。
     *
     * <p><b>为什么两个集合必须一起返</b>:看板的 participants(分母)与 completed(分子)
     * **必须同口径**,否则 {@code rate()} 会算出 &gt;100%(1 人打卡、2 人核销通关 ⇒ rate(2,1)=200%)
     * 或「通关 1 人 / 完成率 0%」(核销全章但零打卡 ⇒ rate(1,0),分母 0 返 0)。
     * 拆成两个方法会让调用方各查一遍(实测 4 次查询、其中 2 次完全重复),还留了「只取一个」的口子。
     */
    final class ExploreCompletion {
        /** 有过生效核销的 memberId —— participants 的**核销侧**那一半(另一半是打卡侧,由调用方并进来)。 */
        public final java.util.Set<Long> participants;
        /** 全部必选章都已核销的 memberId。 */
        public final java.util.Set<Long> completed;

        public ExploreCompletion(java.util.Set<Long> participants, java.util.Set<Long> completed) {
            this.participants = participants;
            this.completed = completed;
        }
    }

    /**
     * P0-17 批量通关口径(俱乐部看板专用)—— <b>每主题恒 2 次查询,与玩家数无关</b>。
     *
     * <p><b>为什么要这个门面而不是让 controller 直接摸 mapper</b>:{@code required.isEmpty()}
     * 的语义(**没有必选章 ⇒ 全员 false**,不是全员 true)必须与单条版
     * {@link #isExploreCompleted} 一致。散在三个 controller 里迟早分叉。
     *
     * <p><b>为什么必须批量</b>:{@code ApiStatsController.club} 是唯一「一次算 M 玩家 × N 主题」
     * 的场景,手上既没 registrationId 也没固定 member ⇒ 循环调 isExploreCompleted
     * = 2 × M × N 次查询(20 主题 × 200 玩家 = 8000 次)。
     *
     * @return 不会返 null;主题无必选章 ⇒ 两个集合都空(与 isExploreCompleted 同口径)
     */
    ExploreCompletion exploreCompletion(Long topicId);

    /**
     * P0-14 扫码反推可核销章节:{该商家在本主题的**生效**供给章节} ∩ {该票尚欠(status=0)的章节}。
     *
     * <p><b>为什么 chapterId 要反推而不是让前端传</b>:核销码是**玩家**在票夹点「出示入场码」时对自己的
     * registrationId 签的,**那一刻玩家还没走进哪家店** —— chapter 是到店那一刻由**商家身份**决定的,
     * 天然属于扫码方上下文,不属于码。(且码格式 {@code v1.{regId}.{type}.{expMs}.{nonce}.{sig}}
     * 硬判 6 段,加第 7 段会撞 VerifyDynCodeUtil.verify。)
     *
     * <p>返回值三分,调用方必须三分:
     * <ul>
     *   <li><b>0 个</b> —— 该商家在本路线没有生效供给,或该票在他这儿的章节已核销过;</li>
     *   <li><b>1 个</b> —— 直接定 chapterId,前端一行不用改;</li>
     *   <li><b>≥2 个</b> —— 真实可能({@code uk_offer_merchant_chapter} 只约束「一商家一章一行」,
     *       **不阻止一商家承接多章**),必须让商家选,<b>不许默默取第一个</b>。</li>
     * </ul>
     *
     * @return 升序 chapterId;不会返 null
     */
    List<Long> resolveRedeemableChapters(Long registrationId, Long topicId, Long merchantId);

    /** 扫码候选：应得章映射 null；本商家真正已核销章映射原流水，仅供回读选择。 */
    Map<Long, Long> resolveScannableChapters(Long registrationId, Long topicId, Long merchantId);

    /** 调用方须先按 activity→registration 锁序复核有效票；锁读同 key 的真实回执与权益。 */
    Long findReplayableChapterRedemptionIdForUpdate(Long registrationId, Long topicId,
                                                   Long chapterId, Long merchantId);


    /**
     * P0-14 核销(③ 按章):同一事务内 写 redemption(ACTIVE)+ 原子扣 quota_used
     * + entitlement 置已用。任一步失败整单回滚。
     *
     * <p>⚠️ 三步的**顺序是定案的**:先 CAS 抢占 entitlement(status 0→1),再扣配额,最后插流水。
     * entitlement 那一行是核销与退款的唯一序列化点;顺序反了会出现「钱退了货照发」。
     *
     * @param operatorId 实际操作人(店员≠商家主体时可区分)
     * @return 核销流水 id
     * @throws RuntimeException 不是应得态 / 配额用尽 / 已被别人核销(唯一键)
     */
    Long redeemChapter(Long registrationId, Long chapterId, Long merchantId, Long operatorId);

    /**
     * 撤销核销(店员误扫 / 运营撤销 / 权益补发后重新核销)。**只应由平台后台发起** ——
     * 商家自助撤销是新的刷单/骗权益面,未拍板前不给商家端入口。
     * 撤销后 redemption 的生成列 active_key 变 NULL、唯一键释放 ⇒ 可重新核销。
     *
     * <p><b>留痕</b>:撤销事实写 redemption(reversed_at/reverse_reason),**谁撤的**另落一条
     * {@code business_audit_event}(actor_type=SYS_USER, action=REDEMPTION_REVERSE,
     * request_id=redemption-reverse:{redemptionId}),与撤销同事务 —— 审计写不进去就整体回滚,
     * 不允许出现「撤了但查不出谁撤的」。
     *
     * @param operatorUserId 发起撤销的平台后台账号 id(SysUser.userId);必填,用于审计,
     *                       传 null 会因审计 actor_member_id NOT NULL 而整体回滚(fail-closed)
     * @return true=本次撤销;false=已被撤销过(幂等,调用方不该再回补配额)
     */
    /** 原父单全部生效核销逐章撤销；失败全部回滚，返回实际已撤销流水ID。 */
    List<Long> reverseParentOrderRedemptions(String parentOrderNo, String reason, Long operatorUserId,
                                           boolean clawbackRewards);

    boolean reverseRedemption(Long redemptionId, String reason, Long operatorUserId);

    /**
     * 第二轮拍板第 4 条:同上,{@code clawbackRewards=true} 时同事务冲回这次核销发出的积分/经验
     * (勋章不收回,连带通关券未使用则作废),见 {@code RedemptionRewardClawbackService}。
     * 重复撤销在 CAS 那步就返回 false,不会重复冲回。
     */
    boolean reverseRedemption(Long redemptionId, String reason, Long operatorUserId, boolean clawbackRewards);

    /**
     * 平台后台撤销入口的读回列表:某票的全部核销流水,**含已撤销**(撤销后仍看得见
     * 何时、因何被撤),按时间倒序。
     */
    List<Redemption> listRedemptions(Long registrationId);

    /**
     * P0-17 ③ 通关判定:每个 required 章都有生效核销。
     * ⚠️ 与 mode=1 的 {@code done>=total} 不是一回事 —— ③ 认的是核销不是打卡。
     */
    boolean isExploreCompleted(Long registrationId, Long topicId);

    /** 玩家权益钱包:该票每章的应得/已得/失效状态(P1-17 的数据源)。 */
    List<RegistrationEntitlement> listEntitlements(Long registrationId);

    /**
     * P0-14 ★ 这张票**真的消费完了没有** —— {@code verification_status} 单独看不出来。
     *
     * <p><b>这是「已消费」的唯一真源,别在调用方各写一遍</b>。因为 ③ 首次核销就置
     * {@code verification_status=1}(退款闸 {@code RefundPolicy.verifiedVerdict} 的前提),
     * 它对 ③ 的语义退化成「**被核销过至少一次**」,而不是「消费完了」。
     * 任何拿 {@code verification_status==1} 当「这张票没用了」的地方,对 ③ 都会判错:
     * <ul>
     *   <li>{@code issueDynCode} 判错 ⇒ 核销完第 1 章就再也拿不到码,第 2~5 章永远核销不了;</li>
     *   <li>{@code AccountDeregistrationServiceImpl.hasActiveRegistration} 判错 ⇒ 玩家核销 1 章后
     *       就能**注销账号带走 4 章已付未兑的权益**,而退款同时被锁死 = 钱货两空且零告警。</li>
     * </ul>
     * 两处方向相反(一个错拦必要动作、一个错放破坏性动作),但根因同一个 ⇒ 收进这一个方法。
     *
     * @param verificationStatus 该票的 verification_status(调用方手上已有,免一次回查)
     * @return true = 真的消费完了(①② 就是 verification_status==1;③ 还要求没有欠着的章节)
     */
    boolean isTicketFullyConsumed(Integer verificationStatus, Long registrationId);

    /**
     * P0-14 该票是否还有**尚未核销**(status=0)的章节权益。
     *
     * <p><b>为什么需要它</b>:{@code verification_status} 对 ③ 的语义是「这张票被核销过(至少一次)」——
     * **首次核销就置位**,退款闸才拦得住(退款判据的唯一决策点 {@code RefundPolicy.verifiedVerdict}
     * key 在它上面)。但 {@code ApiVerifyController.issueDynCode} 原本也拿
     * {@code verification_status==1} 当「不再出码」的判据 ⇒ 对 ③ 会**当场断链**:
     * 核销完第 1 章就拿不到码去核销第 2~5 章。
     * ⇒ ③ 的出码判据必须换成「还有没有欠的章节」,由本方法回答。
     *
     * <p>只查 entitlement 一次,不需要 topicId —— {@code issueDynCode} 手上只有 registration。
     *
     * @return true = 还有 status=0 的章节 ⇒ 该继续出码。
     *         非 ③ 的票压根没有 entitlement ⇒ 恒 false ⇒ 它们的出码判据不受影响
     */
    boolean hasPendingEntitlements(Long registrationId);
}
