package com.chengyinhub.business.service.coupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.TopicCompletionCouponReceiptMapper;
import com.chengyinhub.business.mapper.TopicCompletionCouponReceiptMapper.CurrentLink;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Always installed; performs no startup query and grants no reward capability.
 * It inspects only the fixed current-catalog table/column names, using the existing
 * transaction's connection. Disabling the issuance profile cannot remove this guard.
 */
@Component("topicCompletionCouponHistoryResolver")
public final class TopicCompletionCouponHistoryResolver implements TopicCompletionCouponHistoryResolving {
    private static final String LOG="cms_topic_completion_coupon_reward_log", RECEIPT="cms_topic_completion_coupon_receipt";
    private final TopicCompletionCouponReceiptMapper mapper;
    private final DataSource dataSource;
    public TopicCompletionCouponHistoryResolver(TopicCompletionCouponReceiptMapper mapper,DataSource dataSource) {
        this.mapper=Objects.requireNonNull(mapper);this.dataSource=Objects.requireNonNull(dataSource);
    }
    private void transaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(dataSource))
            throw new IllegalStateException("Current completion protocol requires the original datasource transaction");
    }
    @Override public boolean protocolInstalled() {
        transaction();Connection connection=DataSourceUtils.getConnection(dataSource);
        try {
            DatabaseMetaData metadata=connection.getMetaData();String catalog=connection.getCatalog(),schema=null;
            try { schema=connection.getSchema(); } catch(SQLFeatureNotSupportedException unsupported) { /* JDBC catalog still restricts MySQL. */ }
            String log=name(metadata,LOG),receipt=name(metadata,RECEIPT),column=name(metadata,"receipt_contract_version");
            if(!table(metadata,catalog,schema,log))throw new IllegalStateException("Completion log schema unavailable");
            boolean hasReceipt=table(metadata,catalog,schema,receipt),hasMarker=false;
            try(ResultSet columns=metadata.getColumns(catalog,schema,log,column)) {
                while(columns.next()) if(log.equals(columns.getString("TABLE_NAME")) && column.equals(columns.getString("COLUMN_NAME"))) {
                    if(hasMarker)throw new IllegalStateException("Ambiguous completion marker schema");hasMarker=true;
                }
            }
            if(hasReceipt!=hasMarker)throw new IllegalStateException("Partial completion receipt schema");
            return hasReceipt;
        } catch(SQLException unavailable) { throw new IllegalStateException("Completion receipt schema cannot be verified",unavailable); }
        finally { DataSourceUtils.releaseConnection(connection,dataSource); }
    }
    private static String name(DatabaseMetaData metadata,String value)throws SQLException {
        return metadata.storesUpperCaseIdentifiers()?value.toUpperCase(Locale.ROOT):metadata.storesLowerCaseIdentifiers()?value.toLowerCase(Locale.ROOT):value;
    }
    private static boolean table(DatabaseMetaData metadata,String catalog,String schema,String table)throws SQLException {
        boolean found=false;
        // JDBC patterns treat '_' as a wildcard. Exact returned names are checked again.
        try(ResultSet rows=metadata.getTables(catalog,schema,table,null)) {
            while(rows.next()) if(table.equals(rows.getString("TABLE_NAME"))) {
                String kind=rows.getString("TABLE_TYPE");
                if(found || !("TABLE".equals(kind) || "BASE TABLE".equals(kind)))throw new IllegalStateException("Unexpected completion receipt schema object");
                found=true;
            }
        }
        return found;
    }
    /** NULL is allowed only for old schema or a positively verified NULL/unlinked old slot. */
    @Override public Long currentHistoryForClawback(long topic,long member,long coupon) {
        if(topic<=0 || member<=0 || coupon<=0)throw new IllegalArgumentException("Invalid completion identity");
        if(!protocolInstalled())return null;
        CurrentLink link=mapper.lockCurrentLink(topic,member,coupon);
        if(link==null || link.logId==null || !Long.valueOf(topic).equals(link.topicId) || !Long.valueOf(member).equals(link.memberId)
                || !Long.valueOf(coupon).equals(link.couponId))throw new IllegalStateException("Current completion slot missing");
        if(link.receiptVersion==null && link.receiptLogId==null)return null;
        if(!Integer.valueOf(1).equals(link.receiptVersion) || !link.logId.equals(link.receiptLogId)
                || !link.topicId.equals(link.receiptTopicId) || !link.memberId.equals(link.receiptMemberId)
                || !link.couponId.equals(link.receiptCouponId) || link.historyId==null || link.historyId<=0 || link.grantedAt==null)
            throw new IllegalStateException("Completion receipt marker or correlation conflict");
        SmsCouponHistory history=mapper.lockLinkedHistory(link.historyId);
        if(history==null || !link.historyId.equals(history.getId()) || !link.memberId.equals(history.getMemberId())
                || !link.couponId.equals(history.getCouponId()) || !Integer.valueOf(0).equals(history.getGetType())
                || history.getDelFlag()!=null && history.getDelFlag()!=0 || history.getGetTime()==null
                || history.getGetTime().getTime()!=link.grantedAt.getTime())throw new IllegalStateException("Linked completion history unavailable");
        return link.historyId;
    }
}
