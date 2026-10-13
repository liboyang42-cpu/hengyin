package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmExportTask;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
import java.util.List;

public interface MerchantCrmExportTaskMapper {
    MerchantCrmExportTask selectByRequestId(@Param("merchantId") Long merchantId,
                                            @Param("requestId") String requestId);
    MerchantCrmExportTask selectOwnedTask(@Param("merchantId") Long merchantId,
                                          @Param("ownerMemberId") Long ownerMemberId,
                                          @Param("taskId") Long taskId);
    MerchantCrmExportTask selectTaskInternal(@Param("taskId") Long taskId);
    MerchantCrmExportTask selectMerchantTaskInternal(@Param("merchantId") Long merchantId,
                                                      @Param("taskId") Long taskId);
    int insertTask(MerchantCrmExportTask task);
    MerchantCrmExportTask selectNextRunnableTask(@Param("now") Date now,
                                                 @Param("staleBefore") Date staleBefore);
    int claimRunnableTask(@Param("taskId") Long taskId,
                          @Param("expectedVersion") Integer expectedVersion,
                          @Param("startedAt") Date startedAt,
                          @Param("staleBefore") Date staleBefore,
                          @Param("storageKey") String storageKey);
    int finishSuccess(@Param("taskId") Long taskId, @Param("expectedVersion") Integer expectedVersion,
                      @Param("rowCount") Integer rowCount, @Param("storageKey") String storageKey,
                      @Param("finishedAt") Date finishedAt);
    int finishFailed(@Param("taskId") Long taskId, @Param("expectedVersion") Integer expectedVersion,
                     @Param("errorMessage") String errorMessage, @Param("finishedAt") Date finishedAt);
    int invalidateOwnerMismatch(@Param("taskId") Long taskId,
                                @Param("expectedVersion") Integer expectedVersion,
                                @Param("finishedAt") Date finishedAt);
    List<MerchantCrmExportTask> selectExpirableTasks(@Param("now") Date now, @Param("limit") Integer limit);
    int countByStorageKey(@Param("storageKey") String storageKey);
    int markExpired(@Param("taskId") Long taskId, @Param("now") Date now);
    int markDownloaded(@Param("merchantId") Long merchantId,
                       @Param("ownerMemberId") Long ownerMemberId,
                       @Param("taskId") Long taskId,
                       @Param("downloadedAt") Date downloadedAt);
}
