package com.chengyinhub.business.service.impl;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.mapper.BizEventLogMapper;
import com.chengyinhub.business.mapper.NpcChatLogMapper;
import com.chengyinhub.business.mapper.NpcChatRequestMapper;
import com.chengyinhub.business.mapper.TmsBrowseRecordMapper;
import com.chengyinhub.business.service.IDataRetentionService;

/**
 * 数据留存清理服务实现。
 * 逐批删除,方法不加 @Transactional —— 每批 delete 独立提交,避免一次删百万行的长事务/锁表,
 * 中途异常也已提交前面若干批(幂等可重跑)。
 */
@Service
public class DataRetentionServiceImpl implements IDataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionServiceImpl.class);

    @Autowired
    private TmsBrowseRecordMapper tmsBrowseRecordMapper;

    @Autowired
    private BizEventLogMapper bizEventLogMapper;

    @Autowired
    private NpcChatLogMapper npcChatLogMapper;

    @Autowired
    private NpcChatRequestMapper npcChatRequestMapper;

    @Autowired
    private com.chengyinhub.business.mapper.NpcAsrIntentMapper npcAsrIntents;

    @Autowired
    private com.chengyinhub.business.mapper.RoamMapper roamMapper;
    @Autowired
    private com.chengyinhub.business.mapper.RoamShareSnapshotMapper roamShareSnapshotMapper;
    @Autowired
    private RoamLocationRetentionService roamLocationRetentionService;
    @Autowired
    private RoamOrphanLocationRetentionService roamOrphanLocationRetentionService;

    @Override
    public int cleanRoamLocations(Date cutoff, int batchSize, int maxBatches) {
        if (cutoff == null || batchSize <= 0 || maxBatches <= 0) return 0;
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            java.util.List<com.chengyinhub.business.domain.RoamSession> rows = roamMapper.selectLocationRetentionCandidates(cutoff, batchSize);
            for (com.chengyinhub.business.domain.RoamSession row : rows)
                total += roamLocationRetentionService.clear(row.getId(), row.getMemberId(), cutoff);
            if (rows.size() < batchSize) break;
        }
        total += loopDelete("roam_orphan_tiles", cutoff, batchSize, maxBatches,
                (c,l) -> roamOrphanLocationRetentionService.clearTiles(c,l));
        total += loopDelete("roam_orphan_pois", cutoff, batchSize, maxBatches,
                (c,l) -> roamOrphanLocationRetentionService.clearPois(c,l));
        total += loopDelete("roam_orphan_shops", cutoff, batchSize, maxBatches,
                (c,l) -> roamOrphanLocationRetentionService.clearShops(c,l));
        total += loopDelete("roam_poi_reward_log_coordinates", cutoff, batchSize, maxBatches,
                (c,l) -> roamLocationRetentionService.clearRewardLocations(c,l));
        return total + loopDelete("roam_share_snapshot_unbound", cutoff, batchSize, maxBatches,
                (c, l) -> roamShareSnapshotMapper.deleteExpiredUnbound(c, l));
    }

    @Override
    public int cleanBrowseRecords(Date cutoff, int batchSize, int maxBatches) {
        return loopDelete("tms_browse_record", cutoff, batchSize, maxBatches,
                (c, l) -> tmsBrowseRecordMapper.deleteBrowseRecordsBefore(c, l));
    }

    @Override
    public int cleanBizEventLogs(Date cutoff, int batchSize, int maxBatches) {
        return loopDelete("biz_event_log", cutoff, batchSize, maxBatches,
                (c, l) -> bizEventLogMapper.deleteBizEventLogsBefore(c, l));
    }

    @Override
    public int cleanNpcChatLogs(Date cutoff, int batchSize, int maxBatches) {
        return loopDelete("npc_chat_log", cutoff, batchSize, maxBatches,
                (c, l) -> npcChatLogMapper.deleteNpcChatLogsBefore(c, l));
    }

    @Override
    public int cleanNpcChatRequests(Date cutoff, int batchSize, int maxBatches) {
        int deleted=loopDelete("npc_chat_request", cutoff, batchSize, maxBatches,
                (c, l) -> npcChatRequestMapper.deleteNpcChatRequestsBefore(c, l));
        loopDelete("npc_asr_body",cutoff,batchSize,maxBatches,(c,l)->npcAsrIntents.redactExpired(l));
        return deleted; // Preserve the original HTTP/task count: original text receipt rows only.
    }

    /** 逐批删除直至某批删除数 &lt; batchSize(删干净)或达到 maxBatches 护栏。 */
    private int loopDelete(String table, Date cutoff, int batchSize, int maxBatches, BatchDeleter deleter) {
        if (cutoff == null || batchSize <= 0 || maxBatches <= 0) {
            log.warn("留存清理参数非法,跳过 table={} cutoff={} batchSize={} maxBatches={}", table, cutoff, batchSize, maxBatches);
            return 0;
        }
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int affected = deleter.delete(cutoff, batchSize);
            total += affected;
            if (affected < batchSize) {
                break; // 已删干净
            }
        }
        log.info("留存清理完成 table={} cutoff={} 删除 {} 行", table, cutoff, total);
        return total;
    }

    @FunctionalInterface
    private interface BatchDeleter {
        int delete(Date cutoff, int limit);
    }
}
