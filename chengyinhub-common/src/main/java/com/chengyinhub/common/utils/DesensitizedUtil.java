package com.chengyinhub.common.utils;

/**
 * 脱敏工具类
 *
 * @author ruoyi
 */
public class DesensitizedUtil
{
    /**
     * 姓名脱敏：只保留首字，其余一律单个 *。张三→张*、张三丰→张*、欧阳建国→欧*。
     *
     * <p>全站姓名掩码的唯一实现。此前有三套语义各异的实现，同一个人在不同页面长得不一样：
     * <ul>
     *   <li>WithdrawalPiiProtector：张*（本实现所本）</li>
     *   <li>PiiCryptoService：张**（等长星，会暴露姓名长度）</li>
     *   <li>DesensitizedType.USERNAME 的旧正则：只替换第 2 个字，欧阳建国→欧*建国，
     *       四字名泄 3 字，对中文名基本等于没遮</li>
     * </ul>
     * 三者只在两字名上一致，所以长期没被发现。
     *
     * <p>为何以「张*」为准而不是「张**」：① 不暴露姓名长度，隐私更强；
     * ② 提现的掩码是<b>持久化列</b>（realname_mask，且明文已被置 null），是三者里最难改的，
     * 故它当锚点；报名/订单的掩码是读时现算，跟着它走无需任何数据迁移。
     * 反过来若统一成「张**」，就得拿 PII 密钥去解密回写财务记录。
     *
     * @param name 姓名
     * @return 脱敏后的姓名；null/空串一律返回空串
     */
    public static String username(String name)
    {
        if (StringUtils.isEmpty(name))
        {
            return StringUtils.EMPTY;
        }
        return name.charAt(0) + "*";
    }

    /**
     * 密码的全部字符都用*代替，比如：******
     *
     * @param password 密码
     * @return 脱敏后的密码
     */
    public static String password(String password)
    {
        if (StringUtils.isBlank(password))
        {
            return StringUtils.EMPTY;
        }
        return StringUtils.repeat('*', password.length());
    }

    /**
     * 车牌中间用*代替，如果是错误的车牌，不处理
     *
     * @param carLicense 完整的车牌号
     * @return 脱敏后的车牌
     */
    public static String carLicense(String carLicense)
    {
        if (StringUtils.isBlank(carLicense))
        {
            return StringUtils.EMPTY;
        }
        // 普通车牌
        if (carLicense.length() == 7)
        {
            carLicense = StringUtils.hide(carLicense, 3, 6);
        }
        else if (carLicense.length() == 8)
        {
            // 新能源车牌
            carLicense = StringUtils.hide(carLicense, 3, 7);
        }
        return carLicense;
    }
}
