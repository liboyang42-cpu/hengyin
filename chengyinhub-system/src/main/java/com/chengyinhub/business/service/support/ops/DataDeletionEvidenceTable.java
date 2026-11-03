package com.chengyinhub.business.service.support.ops;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.chengyinhub.business.support.RoamLocationRetention;

/**
 * C11 法务 / 数据删除证据表。只登记既有 writer 实际删除与保留的口径，不另跑清理、不写 DDL。
 *
 * <p>账号注销行来自 {@code AccountDeregistrationServiceImpl#executeOne}；
 * 留存窗口来自 {@code DataRetentionCleanupTask} 默认值与 {@link RoamLocationRetention#THIRTY_DAYS_MILLIS}。
 * 资金 / 订单行匿名化后保留，不把当前空投影当成「从未存在」。
 */
public final class DataDeletionEvidenceTable {

    public enum Disposition {
        /** 行已删除，不可再读出 PII。 */
        REMOVED,
        /** 行保留但 PII 已剥，资金 / 履约仍可对账。 */
        ANONYMIZED_RETAINED,
        /** 法务 / 审计必须留着的原事件，不删。 */
        RETAINED_LEGAL,
        /** 到期后由既有留存任务删除。 */
        RETENTION_WINDOW
    }

    public static final int BROWSE_DAYS = 180;
    public static final int EVENT_DAYS = 365;
    public static final int NPC_CHAT_DAYS = 90;
    public static final int NPC_CHAT_REQUEST_HOURS = 48;
    public static final int ROAM_LOCATION_DAYS = (int) (RoamLocationRetention.THIRTY_DAYS_MILLIS / (24L * 60 * 60 * 1000));

    private static final List<Row> TABLE = buildTable();

    private DataDeletionEvidenceTable() {
    }

    public static List<Row> rows() {
        return TABLE;
    }

    public static List<Row> removed() {
        return byDisposition(Disposition.REMOVED);
    }

    public static List<Row> retained() {
        List<Row> out = new ArrayList<Row>();
        for (Row row : TABLE) {
            if (row.disposition == Disposition.ANONYMIZED_RETAINED
                    || row.disposition == Disposition.RETAINED_LEGAL) {
                out.add(row);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static List<Row> byRequestKind(String requestKind) {
        if (requestKind == null || requestKind.trim().isEmpty()) {
            throw new IllegalArgumentException("requestKind 不能为空");
        }
        String kind = requestKind.trim();
        List<Row> out = new ArrayList<Row>();
        for (Row row : TABLE) {
            if (kind.equals(row.requestKind)) {
                out.add(row);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static Row require(String artifact) {
        for (Row row : TABLE) {
            if (row.artifact.equals(artifact)) {
                return row;
            }
        }
        throw new IllegalArgumentException("证据表无此行 artifact=" + artifact);
    }

    private static List<Row> byDisposition(Disposition disposition) {
        List<Row> out = new ArrayList<Row>();
        for (Row row : TABLE) {
            if (row.disposition == disposition) {
                out.add(row);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static List<Row> buildTable() {
        List<Row> rows = new ArrayList<Row>();
        // 账号注销：删除 PII / 会话 / 互动动作（AccountDeregistrationServiceImpl.executeOne）
        rows.add(row("ACCOUNT_DEREGISTER", "member_identity", Disposition.REMOVED,
                "deleteMemberIdentity", "实名姓名+身份证密文无留存理由"));
        rows.add(row("ACCOUNT_DEREGISTER", "member_address", Disposition.REMOVED,
                "deleteMemberAddresses", "收货地址整行删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "app_session", Disposition.REMOVED,
                "appSessionRegistryService.revokeAll", "注销后旧登录态全部撤销"));
        rows.add(row("ACCOUNT_DEREGISTER", "community_post_actions", Disposition.REMOVED,
                "deleteAccountPostActions", "点赞等动作行删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "community_comment_actions", Disposition.REMOVED,
                "deleteAccountCommentActions", "评论动作行删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "community_mutes", Disposition.REMOVED,
                "deleteAccountMutes", "屏蔽关系删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "notifications", Disposition.REMOVED,
                "deleteAccountNotifications", "通知本体删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "notification_preferences", Disposition.REMOVED,
                "deleteAccountNotificationPreferences", "通知偏好删除"));
        rows.add(row("ACCOUNT_DEREGISTER", "mentions", Disposition.REMOVED,
                "deleteAccountMentions", "提及行删除"));
        // 账号注销：匿名化后保留（资金 / 订单 / 报名不得消失）
        rows.add(row("ACCOUNT_DEREGISTER", "ums_member", Disposition.ANONYMIZED_RETAINED,
                "anonymizeMember", "会员行保留、PII 剥离、delFlag=2"));
        rows.add(row("ACCOUNT_DEREGISTER", "oms_order", Disposition.ANONYMIZED_RETAINED,
                "anonymizeOrders", "订单行保留以便资金对账，不删金额"));
        rows.add(row("ACCOUNT_DEREGISTER", "cms_registration", Disposition.ANONYMIZED_RETAINED,
                "anonymizeRegistrations", "报名行保留、联系方式剥离"));
        rows.add(row("ACCOUNT_DEREGISTER", "member_withdrawal", Disposition.ANONYMIZED_RETAINED,
                "anonymizeMemberWithdrawal", "银行卡提现行保留、姓名卡号剥离"));
        rows.add(row("ACCOUNT_DEREGISTER", "cash_withdraw", Disposition.ANONYMIZED_RETAINED,
                "anonymizeCashWithdraw", "微信提现行保留、openid 剥离"));
        rows.add(row("ACCOUNT_DEREGISTER", "community_post_authors", Disposition.ANONYMIZED_RETAINED,
                "anonymizeAccountPostAuthors", "历史帖作者匿名，正文按治理规则另处理"));
        // 法务必须留的原事件
        rows.add(row("ACCOUNT_DEREGISTER", "business_audit_event", Disposition.RETAINED_LEGAL,
                "BusinessAuditEvent 只追加", "原 request 审计不可删，支持台回查用"));
        rows.add(row("ACCOUNT_DEREGISTER", "club_edition_compensation_terms", Disposition.RETAINED_LEGAL,
                "freezeTermsForSale 不可变", "开售冻结补偿条款不随注销改写"));
        rows.add(row("ACCOUNT_DEREGISTER", "audit_record", Disposition.RETAINED_LEGAL,
                "AuditRecord 审核留痕", "审核裁决原记录不删"));
        // 留存窗口：既有 DataRetentionCleanupTask / RoamLocationRetention，不在本适配器执行
        rows.add(retention("RETENTION_CLEANUP", "tms_browse_record", BROWSE_DAYS,
                "cleanBrowseRecords", "浏览记录含 IP/UA，默认 180 天"));
        rows.add(retention("RETENTION_CLEANUP", "biz_event_log", EVENT_DAYS,
                "cleanBizEventLogs", "行为事件默认 365 天"));
        rows.add(retention("RETENTION_CLEANUP", "npc_chat_log", NPC_CHAT_DAYS,
                "cleanNpcChatLogs", "NPC 原文默认 90 天"));
        rows.add(retentionHours("RETENTION_CLEANUP", "npc_chat_request", NPC_CHAT_REQUEST_HOURS,
                "cleanNpcChatRequests", "幂等终态 48 小时，不替代 npc_chat_log"));
        rows.add(retention("RETENTION_CLEANUP", "roam_location", ROAM_LOCATION_DAYS,
                "cleanRoamLocations", "漫游详细位置 30 天，聚合 XP/距离保留"));
        return Collections.unmodifiableList(rows);
    }

    private static Row row(String requestKind, String artifact, Disposition disposition,
                           String existingWriter, String note) {
        return new Row(requestKind, artifact, disposition, existingWriter, note, null, null);
    }

    private static Row retention(String requestKind, String artifact, int days,
                                 String existingWriter, String note) {
        return new Row(requestKind, artifact, Disposition.RETENTION_WINDOW, existingWriter, note, days, null);
    }

    private static Row retentionHours(String requestKind, String artifact, int hours,
                                      String existingWriter, String note) {
        return new Row(requestKind, artifact, Disposition.RETENTION_WINDOW, existingWriter, note, null, hours);
    }

    public static final class Row {
        public final String requestKind;
        public final String artifact;
        public final Disposition disposition;
        public final String existingWriter;
        public final String note;
        public final Integer retentionDays;
        public final Integer retentionHours;

        Row(String requestKind, String artifact, Disposition disposition, String existingWriter,
            String note, Integer retentionDays, Integer retentionHours) {
            this.requestKind = Objects.requireNonNull(requestKind);
            this.artifact = Objects.requireNonNull(artifact);
            this.disposition = Objects.requireNonNull(disposition);
            this.existingWriter = existingWriter;
            this.note = note;
            this.retentionDays = retentionDays;
            this.retentionHours = retentionHours;
        }

        public boolean removed() {
            return disposition == Disposition.REMOVED;
        }

        public boolean retained() {
            return disposition == Disposition.ANONYMIZED_RETAINED
                    || disposition == Disposition.RETAINED_LEGAL;
        }
    }
}
