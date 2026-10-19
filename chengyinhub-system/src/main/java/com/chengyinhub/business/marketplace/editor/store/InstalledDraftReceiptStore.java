package com.chengyinhub.business.marketplace.editor.store;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** SELECT-only mapper, no source/publication/content/terms byte loading. */
public interface InstalledDraftReceiptStore {
    InstalledDraftTargetRow findOwnedTarget(@Param("actor") long actor,@Param("draftId") long draftId);
    List<InstalledDraftReceiptRow> findReceipts(@Param("actor") long actor,@Param("draftId") long draftId);
}
