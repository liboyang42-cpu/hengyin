package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPayable;
import com.chengyinhub.business.domain.ClubPayableBatch;
import com.chengyinhub.business.domain.TopicEditionFinalization;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** F3 FINAL / 应付 / 批次。台账没有任何改金额的语句，只有 insert 与状态 CAS。 */
public interface ClubPayableMapper {

    int insertFinalization(TopicEditionFinalization finalization);

    TopicEditionFinalization selectFinalization(@Param("topicId") Long topicId);

    TopicEditionFinalization selectFinalizationForUpdate(@Param("topicId") Long topicId);

    int insertPayable(ClubPayable payable);

    ClubPayable selectPayableById(@Param("id") Long id);

    /**
     * 红冲路径专用的<b>锁定读</b>（{@code for update}）。
     *
     * <p>不锁的话「读到 batch_id 为空 → 插反向行」两步之间，并发的 createBatch 能把这一行挂进批次
     * 并提交（纯 INSERT 的反向行不与它的行锁冲突）⇒ 正额进了付款单、抵消它的反向行留在批次之外
     * ⇒ 多付一笔，零告警。行锁让 attachUnbatchedPayables 阻塞到本事务提交，届时它命中的行数
     * 与自己算出的 pending 数对不上 ⇒ 已有的 CLUB_PAYABLE_BATCH_CONTENT_CHANGED 接住。
     */
    ClubPayable selectPayableByIdForUpdate(@Param("id") Long id);

    List<ClubPayable> selectPayables(@Param("topicId") Long topicId, @Param("clubId") Long clubId);

    /**
     * 尚未进批次的应付行；批次只收这些。
     *
     * <p>★ 按 <b>(topic, club)</b> 收窄：E4 是「一期一批次一张发票」，少一个 topic 谓词就会把
     * 两期两份合同的钱扫进同一张付款单，而批次自己无法自报它属于哪一期。
     */
    List<ClubPayable> selectUnbatchedPayables(@Param("topicId") Long topicId, @Param("clubId") Long clubId);

    /** 只读操作面：本期全部应付原始行（含红冲），按 topic 收窄。 */
    List<ClubPayable> selectPayablesByTopic(@Param("topicId") Long topicId);

    /** 只读操作面：本期全部批次（付款轴 + 发票轴），按 topic 收窄。 */
    List<ClubPayableBatch> selectBatchesByTopic(@Param("topicId") Long topicId);

    int insertBatch(ClubPayableBatch batch);

    ClubPayableBatch selectBatch(@Param("id") Long id);

    /** 只把仍未进批次的行挂进来，避免并发建批次把同一行收两次；同样按 (topic, club) 收窄。 */
    int attachUnbatchedPayables(@Param("batchId") Long batchId, @Param("topicId") Long topicId,
                                @Param("clubId") Long clubId);

    int confirmBatchCas(@Param("id") Long id, @Param("confirmedBy") Long confirmedBy,
                        @Param("confirmedAt") Date confirmedAt);

    /** 付款登记：SQL 里额外带 confirmed_by <> #{paidBy}，确认人自己登记付款拿不到行。 */
    int markBatchPaidCas(@Param("id") Long id, @Param("paidBy") Long paidBy,
                         @Param("paidAt") Date paidAt, @Param("paymentReference") String paymentReference);

    /** 发票轴：NONE → ISSUED。与付款状态无关，「已打款未开票」照样开得出来。 */
    int markBatchInvoiceIssued(@Param("id") Long id, @Param("invoiceNo") String invoiceNo);

    /** 发票轴：ISSUED → RED。没开过票就红冲拿不到行（where invoice_status = 'ISSUED'）。 */
    int markBatchInvoiceRed(@Param("id") Long id, @Param("redInvoiceNo") String redInvoiceNo);

    /** 同一期同一俱乐部的净额重计提用：读回该 component 的全部台账行（含红冲）。只读展示用。 */
    List<ClubPayable> selectPayablesByComponent(@Param("topicId") Long topicId,
                                                @Param("clubId") Long clubId,
                                                @Param("component") String component);

    /**
     * 重计提路径的<b>锁定读</b>：与 {@link #selectPayableByIdForUpdate} 同一把尺子。
     *
     * <p>快照读会让「读到这批行都还没进批次 → 插差额行」两步之间被并发 {@code createBatch}
     * 整批插进去：正额进了付款单，抵消它的负向差额行落在批次<b>之外</b> ⇒ 多付，且零告警。
     * 行锁让 {@code attachUnbatchedPayables} 阻塞到本事务提交，届时它命中的行数就对不上
     * 自己算的 {@code pending.size()} ⇒ 已有的 {@code CLUB_PAYABLE_BATCH_CONTENT_CHANGED} 接住。
     */
    List<ClubPayable> selectPayablesByComponentForUpdate(@Param("topicId") Long topicId,
                                                         @Param("clubId") Long clubId,
                                                         @Param("component") String component);

    /**
     * 部署诊断（只读）：当前<b>真的能按下</b>某个资金动作的非超管账号有哪些。
     *
     * <p>为什么要这条查询：`club_payable_batch` 的「确认人 ≠ 付款登记人」是 DB CHECK + SQL CAS +
     * Java 三层，做得对；但既有裁决是「后台只用超管单账号、四角色不建号」⇒ 两个动作只能同一个
     * {@code userId=1} 去点 ⇒ 第一笔真实对公打款登记必然被拒，而系统里没有任何一处说明
     * 「本环境降级为单人确认」。审计 2 明写「若环境尚无法提供第二账号，必须以配置化降级状态
     * 显式显示，不能偷偷放弃复核」。这条查询就是那个显式状态的事实来源 ——
     * <b>问的是权限授给了谁，不是问我自己刚写了什么</b>。
     *
     * <p>超管（{@code user_id = 1}）被显式排除：他持 {@code *:*:*} 两头都点得到，
     * 把他算进来会让「可分离」恒真，正是那种不会红的读数。
     * 停用的角色/用户（{@code status}）与软删（{@code del_flag}）一并排除 —— 授权行还在
     * 但人已停用，不叫「有人能按」。
     */
    List<Long> selectPermHolderUserIds(@Param("perms") String perms);
}
