package com.chengyinhub.business.service.support.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.support.RoamLocationRetention;

class DataDeletionEvidenceTableTest {

    @Test
    void accountDeregisterListsRemovedVersusRetained() {
        assertTrue(DataDeletionEvidenceTable.require("member_identity").removed());
        assertTrue(DataDeletionEvidenceTable.require("member_address").removed());
        assertTrue(DataDeletionEvidenceTable.require("app_session").removed());
        assertTrue(DataDeletionEvidenceTable.require("ums_member").retained());
        assertTrue(DataDeletionEvidenceTable.require("oms_order").retained());
        assertEquals(DataDeletionEvidenceTable.Disposition.ANONYMIZED_RETAINED,
                DataDeletionEvidenceTable.require("oms_order").disposition);
        assertEquals(DataDeletionEvidenceTable.Disposition.RETAINED_LEGAL,
                DataDeletionEvidenceTable.require("business_audit_event").disposition);
        assertEquals(DataDeletionEvidenceTable.Disposition.RETAINED_LEGAL,
                DataDeletionEvidenceTable.require("club_edition_compensation_terms").disposition);

        boolean sawRemoved = false;
        boolean sawRetained = false;
        for (DataDeletionEvidenceTable.Row row : DataDeletionEvidenceTable.byRequestKind("ACCOUNT_DEREGISTER")) {
            if (row.removed()) {
                sawRemoved = true;
            }
            if (row.retained()) {
                sawRetained = true;
            }
        }
        assertTrue(sawRemoved);
        assertTrue(sawRetained);
        assertFalse(DataDeletionEvidenceTable.removed().isEmpty());
        assertFalse(DataDeletionEvidenceTable.retained().isEmpty());
    }

    @Test
    void retentionWindowsMatchExistingCleanupDefaults() {
        assertEquals(180, DataDeletionEvidenceTable.BROWSE_DAYS);
        assertEquals(365, DataDeletionEvidenceTable.EVENT_DAYS);
        assertEquals(90, DataDeletionEvidenceTable.NPC_CHAT_DAYS);
        assertEquals(48, DataDeletionEvidenceTable.NPC_CHAT_REQUEST_HOURS);
        assertEquals(30, DataDeletionEvidenceTable.ROAM_LOCATION_DAYS);
        assertEquals(RoamLocationRetention.THIRTY_DAYS_MILLIS,
                30L * 24 * 60 * 60 * 1000);
        assertEquals(Integer.valueOf(180), DataDeletionEvidenceTable.require("tms_browse_record").retentionDays);
        assertEquals(Integer.valueOf(48), DataDeletionEvidenceTable.require("npc_chat_request").retentionHours);
        assertEquals("cleanRoamLocations", DataDeletionEvidenceTable.require("roam_location").existingWriter);
    }
}
