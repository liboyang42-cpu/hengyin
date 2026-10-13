package com.chengyinhub.business.security;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PII 列透明加解密 TypeHandler:写入自动加密、读取自动解密。
 * 绑定到报名/收货的敏感列(real_name/phone/email/address 等),覆盖所有读写路径,
 * 领域对象与业务代码仍见明文,库里只落密文。
 *
 * ★写入 fail-closed:未配 chengyin.pii.key 时【拒绝写入】而非静默降级明文
 *   (否则明文 PII 落库、与密文行混杂、回填 sentinel 漏行)。上线铁律:部署 PII jar 前必须先配密钥。
 * 读取仍兼容:历史明文(无 v1: 前缀)原样返回,迁移窗口内不炸。
 *
 * 用法(mapper XML):resultMap 里 typeHandler="com...PiiCryptoTypeHandler",
 * insert/update 值写 #{phone,typeHandler=com...PiiCryptoTypeHandler}。
 */
public class PiiCryptoTypeHandler extends BaseTypeHandler<String> {

    private static PiiCryptoService svc() {
        return PiiCryptoService.getInstance();
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        PiiCryptoService s = svc();
        if (s == null || !s.isEnabled()) {
            // fail-closed:无密钥拒绝写入,不静默降级明文。部署前须先配 chengyin.pii.key。
            throw new SQLException("PII 加密密钥未配置(chengyin.pii.key),拒绝明文写入敏感资料");
        }
        ps.setString(i, s.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decode(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decode(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decode(cs.getString(columnIndex));
    }

    private String decode(String stored) {
        PiiCryptoService s = svc();
        // decrypt 对历史明文(无 v1: 前缀)原样返回;未初始化则原样返回,均不阻断读取。
        return s == null ? stored : s.decrypt(stored);
    }
}
