package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantChapterApplication;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/** 商家章节承接申请 Mapper。 */
public interface MerchantChapterApplicationMapper {

    MerchantChapterApplication selectMerchantChapterApplicationById(Long id);

    /** Current round after the original begin-attempt CAS; caller already holds that application write lock. */
    Long selectApplicationRoundForUpdate(@Param("id") Long id);

    /** 撤回幂等判定专用：包含 del_flag=2 的历史申请。 */
    MerchantChapterApplication selectByIdIncludingDeleted(Long id);

    /** 撤回与点位提交/审核共用的行锁，避免撤回后漏进或重新放行点位。 */
    MerchantChapterApplication selectByIdIncludingDeletedForUpdate(Long id);

    MerchantChapterApplication selectByChapterAndMerchant(@Param("chapterId") Long chapterId,
                                                          @Param("merchantId") Long merchantId);

    /** 点位提交/审核专用：与申请撤回串行。 */
    MerchantChapterApplication selectByChapterAndMerchantForUpdate(@Param("chapterId") Long chapterId,
                                                                   @Param("merchantId") Long merchantId);

    /** 重申专用：唯一键覆盖当前与已撤回记录。 */
    MerchantChapterApplication selectAnyByChapterAndMerchant(@Param("chapterId") Long chapterId,
                                                             @Param("merchantId") Long merchantId);

    List<MerchantChapterApplication> selectByTopicId(@Param("topicId") Long topicId);

    List<MerchantChapterApplication> selectByPublisherId(@Param("publisherMemberId") Long publisherMemberId);

    List<MerchantChapterApplication> selectByMerchantId(@Param("merchantId") Long merchantId);

    List<MerchantChapterApplication> selectByChapterAndStatus(@Param("chapterId") Long chapterId,
                                                               @Param("status") Integer status);

    /**
     * 批量版 {@link #selectByChapterAndStatus}:列表页一次取回多章的申请,避免逐章查询。
     *
     * <p>过滤条件与单章版**逐字一致**(status + del_flag=0),两者口径不能分叉 ——
     * 一旦分叉,列表页算出的名额会和详情页对不上。
     */
    List<MerchantChapterApplication> selectByChapterIdsAndStatus(@Param("chapterIds") List<Long> chapterIds,
                                                                 @Param("status") Integer status);

    /** 章节删除前的引用保护：逻辑存在的申请都必须先处理，不能留下孤儿审核记录。 */
    int countCurrentByChapter(@Param("chapterId") Long chapterId);

    int insertMerchantChapterApplication(MerchantChapterApplication application);

    /** 同一章被拒后重新申请：复位待审核并清空历史审核痕迹。 */
    int reapply(@Param("id") Long id, @Param("message") String message, @Param("createBy") String createBy);

    /** 恢复商家主动撤回的待审申请，复用唯一键下的原记录。 */
    int restoreWithdrawn(@Param("id") Long id,
                         @Param("message") String message,
                         @Param("createBy") String createBy);

    /** 发布者重新邀请主动撤回的待审申请，复用原记录并原子更新邀请来源。 */
    int reinviteWithdrawn(MerchantChapterApplication application);

    /** 仅本人可把仍为待审的当前申请软删除；状态条件同时防住并发审核。 */
    int withdrawPending(@Param("id") Long id, @Param("merchantId") Long merchantId);

    /** 实际供给退出后释放该商家的审批占位；保留审批历史行，不覆盖审核记录。 */
    int retireApprovedForSupplyExit(@Param("topicId") Long topicId,
                                    @Param("chapterId") Long chapterId,
                                    @Param("merchantId") Long merchantId);

    /** W2 招募窗口关闭:数【本期】还挂着的待审申请。判据与 rejectPendingByTopic 的 where 逐字同口径。 */
    int countPendingByTopic(@Param("topicId") Long topicId);

    /**
     * W2 招募窗口关闭:把【本期】还挂着的待审申请置 2已拒绝,返回受影响行数。
     * audit_member_id 保持 NULL —— 没有人审过它,不许伪造一个审核人。
     * 幂等靠 where 里的 status = 0:重放时第二次命中 0 行。
     */
    int rejectPendingByTopic(@Param("topicId") Long topicId,
                             @Param("auditRemark") String auditRemark,
                             @Param("handleTime") java.util.Date handleTime);

    int updateAudit(@Param("id") Long id,
                    @Param("status") Integer status,
                    @Param("auditRemark") String auditRemark,
                    @Param("auditMemberId") Long auditMemberId,
                    @Param("handleTime") Date handleTime);
}
