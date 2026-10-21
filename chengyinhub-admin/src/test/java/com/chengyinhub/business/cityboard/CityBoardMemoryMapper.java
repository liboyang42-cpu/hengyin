package com.chengyinhub.business.cityboard;

import com.chengyinhub.business.cityboard.mapper.CityBoardMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 本地隔离验收用的映射，不连接数据库。 */
public class CityBoardMemoryMapper implements CityBoardMapper {
    private final Map<Long, CityBoardDraftRow> drafts = new ConcurrentHashMap<Long, CityBoardDraftRow>();
    private final Map<Long, CityBoardReleaseRow> releases = new ConcurrentHashMap<Long, CityBoardReleaseRow>();
    private final Map<String, CityBoardOperationRow> operations = new ConcurrentHashMap<String, CityBoardOperationRow>();

    @Override
    public synchronized CityBoardDraftRow selectDraftById(long id) {
        CityBoardDraftRow row = drafts.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized int insertDraft(CityBoardDraftRow row) {
        if (row.getId() == null || drafts.containsKey(row.getId())) {
            return 0;
        }
        drafts.put(row.getId(), row.copy());
        return 1;
    }

    @Override
    public synchronized int updateDraft(CityBoardDraftRow row) {
        CityBoardDraftRow stored = drafts.get(row.getId());
        if (stored == null || stored.getVersion() == null || !stored.getVersion().equals(row.getExpectedVersion())) {
            return 0;
        }
        drafts.put(row.getId(), row.copy());
        return 1;
    }

    @Override
    public synchronized CityBoardReleaseRow selectCurrentRelease(String regionId) {
        CityBoardReleaseRow found = null;
        for (CityBoardReleaseRow row : releases.values()) {
            if (regionId.equals(row.getRegionId()) && Integer.valueOf(1).equals(row.getCurrentFlag())) {
                found = row;
            }
        }
        return found == null ? null : found.copy();
    }

    @Override
    public synchronized CityBoardReleaseRow selectReleaseById(long id) {
        CityBoardReleaseRow row = releases.get(id);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized Long selectMaxRevision(String regionId) {
        Long max = null;
        for (CityBoardReleaseRow row : releases.values()) {
            if (regionId.equals(row.getRegionId()) && (max == null || row.getRevision() > max)) {
                max = row.getRevision();
            }
        }
        return max;
    }

    @Override
    public synchronized int clearCurrent(String regionId) {
        int count = 0;
        for (CityBoardReleaseRow row : releases.values()) {
            if (regionId.equals(row.getRegionId()) && Integer.valueOf(1).equals(row.getCurrentFlag())) {
                row.setCurrentFlag(0);
                count++;
            }
        }
        return count;
    }

    @Override
    public synchronized int insertRelease(CityBoardReleaseRow row) {
        if (row.getId() == null || releases.containsKey(row.getId())) {
            return 0;
        }
        releases.put(row.getId(), row.copy());
        return 1;
    }

    @Override
    public synchronized CityBoardOperationRow selectOperation(String requestId) {
        CityBoardOperationRow row = operations.get(requestId);
        return row == null ? null : row.copy();
    }

    @Override
    public synchronized int insertOperation(CityBoardOperationRow row) {
        if (row.getRequestId() == null || operations.containsKey(row.getRequestId())) {
            return 0;
        }
        operations.put(row.getRequestId(), row.copy());
        return 1;
    }

    public synchronized int draftCount() {
        return drafts.size();
    }

    public synchronized int releaseCount() {
        return releases.size();
    }
}
