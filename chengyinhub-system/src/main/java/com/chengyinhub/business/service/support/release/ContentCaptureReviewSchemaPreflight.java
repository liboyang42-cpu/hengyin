package com.chengyinhub.business.service.support.release;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Read-only live schema verification; never repairs tables or trusts migration ledger status. */
@Component
public class ContentCaptureReviewSchemaPreflight {
    private final DataSource source;
    public ContentCaptureReviewSchemaPreflight(DataSource source) { this.source=Objects.requireNonNull(source); }

    /** Standalone activation/deployment check through a Spring proxy. No schema writes. */
    @Transactional(readOnly=true,rollbackFor=Exception.class)
    public void preflight() { verifyCurrentTransaction(); }

    /** Each enabled candidate operation calls this within its existing transaction. No positive cache. */
    public void verifyCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw invalid("transaction absent");
        Connection connection=DataSourceUtils.getConnection(source);
        try {
            if (!DataSourceUtils.isConnectionTransactional(connection,source) || connection.getAutoCommit()
                    || !"MySQL".equals(connection.getMetaData().getDatabaseProductName())) throw invalid("bound MySQL transaction required");
            // Hold table metadata locks until caller commit, preventing a DDL gap after validation.
            for(String table:new String[]{"content_capture_review_binding","audit_task"}) {
                try(Statement sql=connection.createStatement()) {
                    sql.setQueryTimeout(3);
                    try(ResultSet rows=sql.executeQuery("SELECT id FROM "+table+" WHERE 1=0")){while(rows.next()){} }
                    try(ResultSet ddl=sql.executeQuery("SHOW CREATE TABLE "+table)) {
                        if(!ddl.next() || !ddl.getString(2).startsWith("CREATE TABLE `"+table+"`"))throw invalid("temporary shadow or non-table object");
                        if(ddl.next())throw invalid("ambiguous table identity");
                    }
                }
            }
            requireEngine(connection,"content_capture_review_binding");requireEngine(connection,"audit_task");
            Map<String,Map<String,String>> columns=columns(connection,"content_capture_review_binding");
            Set<String> expected=new HashSet<>(Arrays.asList("id","snapshot_id","topic_id","member_id","request_id","audit_task_id","source_config_version","content_hash","created_at"));
            if(!columns.keySet().equals(expected))throw invalid("binding columns");
            for(String name:Arrays.asList("id","snapshot_id","topic_id","member_id","audit_task_id","source_config_version")) {
                Map<String,String> c=columns.get(name);require(c,"data_type","bigint");require(c,"is_nullable","NO");
                if(c.get("column_type").toLowerCase(Locale.ROOT).contains("unsigned"))throw invalid("signed identity type");
                require(c,"extra","id".equals(name)?"auto_increment":"");
            }
            character(columns.get("request_id"),"varchar","128");character(columns.get("content_hash"),"char","64");
            Map<String,String> time=columns.get("created_at");require(time,"data_type","timestamp");require(time,"datetime_precision","3");require(time,"is_nullable","NO");
            if(!"CURRENT_TIMESTAMP(3)".equalsIgnoreCase(time.get("column_default")))throw invalid("creation timestamp default");
            require(time,"extra","DEFAULT_GENERATED");
            Map<String,List<String>> indexes=uniqueIndexes(connection,"content_capture_review_binding",null);
            Map<String,List<String>> wanted=new HashMap<>();wanted.put("PRIMARY",Arrays.asList("id"));
            wanted.put("uk_capture_review_request",Arrays.asList("topic_id","member_id","request_id"));
            wanted.put("uk_capture_review_snapshot",Arrays.asList("snapshot_id"));wanted.put("uk_capture_review_task",Arrays.asList("audit_task_id"));
            if(!wanted.equals(indexes))throw invalid("binding unique indexes");
            if(!query(connection,"SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name=? AND constraint_type NOT IN ('PRIMARY KEY','UNIQUE')","content_capture_review_binding").isEmpty())throw invalid("unexpected binding constraints");
            requireTriggers(connection);
            Map<String,Map<String,String>> audit=columns(connection,"audit_task");
            for(String name:Arrays.asList("biz_type","biz_id","status"))require(audit.get(name),"is_nullable","NO");
            Map<String,String> guard=audit.get("open_guard");require(guard,"data_type","tinyint");require(guard,"extra","STORED GENERATED");
            String expression=guard.get("generation_expression");
            if(!supportedGuardExpression(expression))throw invalid("audit open guard expression");
            if(!Arrays.asList("biz_type","biz_id","open_guard").equals(uniqueIndexes(connection,"audit_task","uk_audit_biz_open").get("uk_audit_biz_open")))throw invalid("audit open-task uniqueness");
        } catch(SQLException failure) { throw new IllegalStateException("Candidate review schema preflight failed",failure); }
        finally { DataSourceUtils.releaseConnection(connection,source); }
    }
    private static void character(Map<String,String> c,String type,String length) {
        require(c,"data_type",type);require(c,"character_maximum_length",length);require(c,"is_nullable","NO");
        require(c,"character_set_name","ascii");require(c,"collation_name","ascii_bin");require(c,"extra","");
    }
    private static void requireEngine(Connection c,String table)throws SQLException {
        List<Map<String,String>> rows=query(c,"SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",table);
        if(rows.size()!=1 || !"InnoDB".equals(rows.get(0).get("engine")))throw invalid("InnoDB table required");
    }
    private static Map<String,Map<String,String>> columns(Connection c,String table)throws SQLException {
        Map<String,Map<String,String>> result=new HashMap<>();
        for(Map<String,String> row:query(c,"SELECT column_name,data_type,column_type,is_nullable,character_maximum_length,character_set_name,collation_name,datetime_precision,extra,column_default,generation_expression FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?",table)) {
            if(result.put(row.get("column_name"),row)!=null)throw invalid("duplicate column metadata");
        }
        return result;
    }
    private static Map<String,List<String>> uniqueIndexes(Connection c,String table,String onlyIndex)throws SQLException {
        Map<String,List<String>> result=new HashMap<>();
        for(Map<String,String> row:query(c,"SELECT index_name,column_name,seq_in_index,non_unique,sub_part,index_type,is_visible FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",table)) {
            if(onlyIndex!=null && !onlyIndex.equals(row.get("index_name")))continue;
            if(!"0".equals(row.get("non_unique")))continue;
            if(row.get("sub_part")!=null || !"BTREE".equals(row.get("index_type")) || !"YES".equals(row.get("is_visible")))throw invalid("partial or nonstandard unique index");
            List<String> names=result.computeIfAbsent(row.get("index_name"),ignored->new ArrayList<>());
            if(!String.valueOf(names.size()+1).equals(row.get("seq_in_index")) || row.get("column_name")==null)throw invalid("index sequence");
            names.add(row.get("column_name"));
        }
        return result;
    }
    private static void requireTriggers(Connection c)throws SQLException {
        List<Map<String,String>> triggers=query(c,"SELECT trigger_name,event_manipulation,action_timing,action_orientation,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND event_object_table=?","content_capture_review_binding");
        if(triggers.size()!=2)throw invalid("binding trigger count");
        Set<String> seen=new HashSet<>();
        for(Map<String,String> trigger:triggers) {
            String name=trigger.get("trigger_name");boolean update="content_capture_review_no_update".equals(name);
            if(!update&&!"content_capture_review_no_delete".equals(name))throw invalid("binding trigger name");
            if(!seen.add(name))throw invalid("duplicate trigger");
            require(trigger,"event_manipulation",update?"UPDATE":"DELETE");require(trigger,"action_timing","BEFORE");require(trigger,"action_orientation","ROW");
            String statement=trigger.get("action_statement");
            String expected="SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Candidate review bindings cannot be "+(update?"updated":"deleted")+"'";
            if(statement==null || !normalize(expected).equals(normalize(statement)))throw invalid("binding trigger body");
        }
    }
    static boolean supportedGuardExpression(String value) {
        if(value==null)return false;
        List<String> tokens=new ArrayList<>();
        for(int i=0;i<value.length();) {
            char ch=value.charAt(i);
            if(Character.isWhitespace(ch)){i++;continue;}
            if(ch=='('||ch==')'){i++;continue;}
            if(ch==','){tokens.add(",");i++;continue;}
            if(ch=='`') {
                StringBuilder name=new StringBuilder();boolean closed=false;i++;
                while(i<value.length()) {
                    char c=value.charAt(i++);
                    if(c=='`'){if(i<value.length()&&value.charAt(i)=='`'){name.append('`');i++;}else{closed=true;break;}}
                    else name.append(c);
                }
                if(!closed||!"status".contentEquals(name))return false;
                tokens.add("status");continue;
            }
            int start=i;
            if(ch>='0'&&ch<='9') {while(i<value.length()&&value.charAt(i)>='0'&&value.charAt(i)<='9')i++;tokens.add(value.substring(start,i));continue;}
            if((ch>='a'&&ch<='z')||(ch>='A'&&ch<='Z')) {
                while(i<value.length()&&((value.charAt(i)>='a'&&value.charAt(i)<='z')||(value.charAt(i)>='A'&&value.charAt(i)<='Z')))i++;
                String token=value.substring(start,i);
                if("status".equals(token)){tokens.add(token);continue;}
                String keyword=token.toLowerCase(Locale.ROOT);
                if(!Arrays.asList("case","when","in","then","else","null","end").contains(keyword))return false;
                tokens.add(keyword);continue;
            }
            // String literals, operators, comments and unknown/escaped identifiers are not this contract.
            return false;
        }
        return tokens.equals(Arrays.asList("case","when","status","in","0",",","3","then","1","else","null","end"));
    }
    private static String normalize(String value) {
        // Preserve every quoted byte, including escaped quotes/backticks and literal whitespace.
        StringBuilder result=new StringBuilder();char quote=0;boolean space=false;
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(quote!=0) {
                result.append(c);
                if(c=='\\'&&i+1<value.length()){result.append(value.charAt(++i));continue;}
                if(c==quote){if(i+1<value.length()&&value.charAt(i+1)==quote)result.append(value.charAt(++i));else quote=0;}
                continue;
            }
            if(Character.isWhitespace(c)){space=true;continue;}
            if(space&&result.length()>0)result.append(' ');space=false;
            if(c=='\''||c=='"'||c=='`'){quote=c;result.append(c);}else result.append(Character.toLowerCase(c));
        }
        return quote==0?result.toString():null;
    }
    private static List<Map<String,String>> query(Connection c,String sql,String table)throws SQLException {
        List<Map<String,String>> rows=new ArrayList<>();
        try(PreparedStatement statement=c.prepareStatement(sql)){statement.setQueryTimeout(3);statement.setString(1,table);
            try(ResultSet result=statement.executeQuery()){ResultSetMetaData metadata=result.getMetaData();
                while(result.next()){Map<String,String> row=new HashMap<>();for(int i=1;i<=metadata.getColumnCount();i++)row.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT),result.getString(i));rows.add(row);}
            }
        }
        return rows;
    }
    private static void require(Map<String,String> row,String key,String expected) {if(row==null||!expected.equals(row.get(key)))throw invalid("required "+key);}
    private static IllegalStateException invalid(String reason){return new IllegalStateException("Candidate review schema is not approved: "+reason);}
}
