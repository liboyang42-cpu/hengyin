package com.chengyinhub.business.mapper;

import java.util.Date;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.MemberCashWithdraw;
import com.chengyinhub.business.domain.CashWithdrawFundFact;

/**
 * 微信零钱提现单 Mapper(T5 真实出金)
 */
public interface MemberCashWithdrawMapper {

    /** Caller holds the original withdrawal row lock. Facts have no update/delete API. */
    int insertFundFact(CashWithdrawFundFact fact);

    List<CashWithdrawFundFact> selectFundFacts(@Param("id") Long id);

    int bindDebitEvidence(MemberCashWithdraw withdrawal);

    /** SUCCESS can resolve investigations/review, but can never overwrite a committed refund. */
    int confirmChannelSuccess(MemberCashWithdraw withdrawal);

    public int insertMemberCashWithdraw(MemberCashWithdraw w);

    public int updateMemberCashWithdraw(MemberCashWithdraw w);

    /** Channel observations only; terminal/frozen original receipts are authoritative. */
    public int updateReceiptIfProcessing(MemberCashWithdraw w);

    public MemberCashWithdraw selectByIdForUpdate(@Param("id") Long id);

    public MemberCashWithdraw selectById(@Param("id") Long id);

    /** 某会员提现记录(倒序) */
    public List<MemberCashWithdraw> selectByMember(@Param("memberId") Long memberId);

    /** 在途提现计数(status 0处理中/3待人工/4待复核),供 hasPendingWithdraw 与注销拦截 */
    public int countPendingByMember(@Param("memberId") Long memberId);

    /** 扫处理中的提现单供定时查询微信最终状态 */
    public List<MemberCashWithdraw> selectPendingForQuery(@Param("limit") int limit);

    /** 按商户批次号查(幂等/对账) */
    public MemberCashWithdraw selectByOutBatchNo(@Param("outBatchNo") String outBatchNo);

    /** 仅当仍处理中(status=0)时更新为终态,保证 SUCCESS/退款单次性;返回受影响行数 */
    public int updateStatusIfProcessing(MemberCashWithdraw w);

    /** A matching original channel success may also resolve an escalated/manual-review withdrawal. */
    int updateSuccessIfPending(MemberCashWithdraw w);

    /** 后台列表:按状态/会员过滤(均可空) */
    public List<MemberCashWithdraw> selectForAdmin(MemberCashWithdraw query);

    /** 仅当失败待人工(status=3)时更新,保证人工补单单次;返回受影响行数 */
    public int updateStatusIfManual(MemberCashWithdraw w);

    /** 仅当待第二人复核(status=4)时完成；SQL 同时约束复核人不得等于处理人。 */
    public int updateStatusIfRefundReview(MemberCashWithdraw w);

    /** 对账:处理中(status=0)且提交/建单早于 before 的积压数(巡检健康) */
    public int countStuckProcessing(@Param("before") Date before);

    /** 对账:失败待人工/待复核(status in 3,4)积压数 */
    public int countManualPending();

    /** 对账:标记成功(status=1)却无微信批次号的数据异常数 */
    public int countSuccessWithoutBatch();

    public List<Long> sampleStuckProcessingIds(@Param("before") Date before);
    public List<Long> sampleManualPendingIds();
    public List<Long> sampleSuccessWithoutBatchIds();

    /** P块冻结:仅未冻结的单可冻结(CAS);返回受影响行数 */
    public int freezeWithdraw(MemberCashWithdraw w);

    /** P块解冻:仅冻结中的单可解冻(CAS);返回受影响行数 */
    public int unfreezeWithdraw(@Param("id") Long id);
}
