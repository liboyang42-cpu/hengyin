package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMember;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantOperatorOwnerFenceMapperContractTest {
    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("UmsMember", UmsMember.class);
        configuration.getTypeAliasRegistry().registerAlias("MmsMerchant", MmsMerchant.class);
        parse("mapper/business/MerchantOperatorMapper.xml");
        parse("mapper/business/MerchantOperatorInviteMapper.xml");
        parse("mapper/business/UmsMemberMapper.xml");
        parse("mapper/business/MmsMerchantMapper.xml");
    }

    @Test
    void ownerChangeBulkRevokesOnlyCurrentActiveDelegationsAndInvalidatesPendingTokens() {
        Map<String, Object> params = new HashMap<>();
        params.put("merchantId", 77L);
        params.put("revokeReason", "MERCHANT_OWNER_CHANGED");
        params.put("updateBy", "SYSTEM_OWNER_CHANGE");

        String operatorSql = sql("com.chengyinhub.business.mapper.MerchantOperatorMapper"
                + ".revokeAllActiveByMerchantId", params);
        assertTrue(operatorSql.contains("set status = 'revoked'"));
        assertTrue(operatorSql.contains("where merchant_id = ? and status = 'active'"));
        assertTrue(operatorSql.contains("version = version + 1"));

        String inviteSql = sql("com.chengyinhub.business.mapper.MerchantOperatorInviteMapper"
                + ".revokeAllPendingByMerchantId", params);
        assertTrue(inviteSql.contains("set status = 'revoked'"));
        assertTrue(inviteSql.contains("where merchant_id = ? and status = 'pending'"));
        assertTrue(inviteSql.contains(":owner-changed:"));
        assertTrue(inviteSql.contains("token_hash = lower(sha2"));
    }

    @Test
    void tokenDiscoveryReadsAreNonlockingButExactRechecksUseForUpdate() {
        String preview = sql("com.chengyinhub.business.mapper.MerchantOperatorInviteMapper"
                + ".selectByTokenHash", "hash");
        String locked = sql("com.chengyinhub.business.mapper.MerchantOperatorInviteMapper"
                + ".selectByTokenHashForUpdate", "hash");

        assertFalse(preview.contains("for update"));
        assertTrue(locked.contains("for update"));
    }

    @Test
    void activeMemberLockChecksCurrentStatusAndFlushesEarlierIdentityRead() {
        String statement = "com.chengyinhub.business.mapper.UmsMemberMapper.lockActiveMemberById";
        String activeLock = sql(statement, 900L);

        assertTrue(activeLock.contains("where id = ? and status = 1 and del_flag = 0"));
        assertTrue(activeLock.contains("for update"));
        assertFalse(configuration.getMappedStatement(statement).isUseCache());
        assertTrue(configuration.getMappedStatement(statement).isFlushCacheRequired());
    }

    @Test
    void authorizedProfileWriteLocksCurrentActiveOperatorAndFlushesPreviewCache() {
        String statement = "com.chengyinhub.business.mapper.MerchantOperatorMapper"
                + ".selectActiveByMemberIdForUpdate";
        String activeOperatorLock = sql(statement, 901L);

        assertTrue(activeOperatorLock.contains("where mo.active_operator_member_id = ?"));
        assertTrue(activeOperatorLock.contains("for update"));
        assertFalse(configuration.getMappedStatement(statement).isUseCache());
        assertTrue(configuration.getMappedStatement(statement).isFlushCacheRequired());
    }

    @Test
    void ownerRegistrationUpdateIsOwnerScopedAndCannotWritePrivilegedMerchantFields() {
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(77L);
        merchant.setName("重提门店");
        merchant.setStatus(0L);
        merchant.setUpdateBy("900");
        Map<String, Object> params = new HashMap<>();
        params.put("merchant", merchant);
        params.put("expectedOwnerMemberId", 900L);

        String ownerSql = sql("com.chengyinhub.business.mapper.MmsMerchantMapper"
                + ".updateOwnerRegistrationProfile", params);
        String setClause = ownerSql.substring(0, ownerSql.indexOf(" where "));

        assertTrue(ownerSql.contains("where id = ? and member_id = ?"));
        assertTrue(ownerSql.contains("and del_flag = 0"));
        assertTrue(ownerSql.contains("and account_status is not null and account_status <> 2"));
        assertTrue(ownerSql.contains("and status in (0, 2)"));
        assertTrue(setClause.contains("status = 0"));
        assertFalse(setClause.contains("member_id"));
        assertFalse(setClause.contains("account_status"));
        assertFalse(setClause.contains("business_status"));
        assertFalse(setClause.contains("del_flag"));
        assertFalse(setClause.contains("featured_id"));
    }

    private void parse(String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private String sql(String statement, Object parameter) {
        BoundSql boundSql = configuration.getMappedStatement(statement).getBoundSql(parameter);
        return boundSql.getSql().replaceAll("\\s+", " ").trim().toLowerCase();
    }
}
