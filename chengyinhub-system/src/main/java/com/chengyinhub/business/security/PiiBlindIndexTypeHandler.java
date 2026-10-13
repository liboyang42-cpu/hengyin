package com.chengyinhub.business.security;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 手机号盲索引 TypeHandler:写入/查询参数取 HMAC 值(供加密后仍按手机号精确检索)。
 * 绑定到 *_hash 列(写入)与手机号搜索参数(查询);读取返回原始 hash(不解密)。
 *
 * ★fail-closed:未配 chengyin.pii.key 时【拒绝】写入/查询,而非把明文手机号直存进 hash 列
 *   (否则明文泄进 hash 列,且回填以 hash IS NULL 判「未迁移」会永久跳过这些行)。部署前须先配密钥。
 */
public class PiiBlindIndexTypeHandler extends BaseTypeHandler<String> {

    private static PiiCryptoService svc() {
        return PiiCryptoService.getInstance();
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        PiiCryptoService s = svc();
        if (s == null || !s.isEnabled()) {
            // fail-closed:无密钥拒绝,不把明文手机号写进 hash 列。部署前须先配 chengyin.pii.key。
            throw new SQLException("PII 盲索引密钥未配置(chengyin.pii.key),拒绝明文写入 hash 列");
        }
        ps.setString(i, s.blindIndex(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
