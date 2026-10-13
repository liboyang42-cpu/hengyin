package com.chengyinhub.business.service;

import java.math.BigDecimal;
import java.util.List;
import com.chengyinhub.business.domain.MemberCashWithdraw;

/**
 * 微信零钱提现 Service(T5 真实出金)。
 */
public interface ICashWithdrawService {

    /** 最低提现金额(元) */
    BigDecimal MIN_AMOUNT = new BigDecimal("1.00");

    /**
     * 一键把可用余额提现到微信零钱:扣余额(原子)→ 建单 → 微信商家转账 → 成功回写/失败退余额。
     * @return 提现单(成功 status=1;失败会抛异常并已退回余额)
     * @throws IllegalStateException 余额不足/未绑微信/金额不合法/出金失败
     */
    MemberCashWithdraw withdraw(Long memberId, BigDecimal amountYuan);

    /** 某会员提现记录 */
    List<MemberCashWithdraw> myWithdraws(Long memberId);

    /**
     * 是否有在途提现(处理中/失败待人工)。供账号注销拦截等跨域只读判断使用,
     * 调用方无需知道提现内部状态枚举。
     */
    boolean hasPendingWithdraw(Long memberId);

    /**
     * 定时查询处理中的提现单,向微信确认最终状态并推进状态机(修复「已受理即成功」)。
     * @param limit 单次处理条数上限
     * @return 本次处理条数
     */
    int syncPendingWithdrawals(int limit);

    /** 后台列表(按状态/会员过滤,均可空) */
    List<MemberCashWithdraw> adminList(MemberCashWithdraw query);

    /** 后台按 id 查询单条 */
    MemberCashWithdraw getById(Long id);

    /** 后台手动重新查询某单的微信最终状态并推进状态机(用于卡在处理中的单) */
    MemberCashWithdraw requery(Long id);

    /**
     * 后台人工补单:第一人登记待复核，第二个不同账号复核后才回补余额。
     * @return 是否补单成功
     */
    boolean manualRefund(Long id, String operator, String note);

    /**
     * 后台人工补单:要求确认时看到的状态仍未变化，避免陈旧确认改走另一条资金分支。
     */
    boolean manualRefund(Long id, String operator, String note, Integer expectedStatus);

    /**
     * 提现出金日对账/健康巡检:处理中积压(巡检健康)、待人工积压(财务待办)、成功无批次号(数据异常)超阈值告警。
     * @param stuckHours 处理中超过此时长计入积压
     * @return 对账摘要
     */
    String reconcile(int stuckHours);

    /**
     * P块冻结:暂停该提现单的自动化处理(定时查询/自动退款均跳过),不移动任何资金。
     * @return false = 已在冻结中(重复操作)
     * @throws IllegalStateException 提现单不存在
     */
    boolean freezeWithdraw(Long id, String operator, String reason);

    /**
     * P块解冻:恢复该提现单的自动化处理。
     * @return false = 本就未冻结
     */
    boolean unfreezeWithdraw(Long id, String operator);
}
