package com.chengyinhub.common.enums;

import java.util.function.Function;
import com.chengyinhub.common.utils.DesensitizedUtil;

/**
 * 脱敏类型
 *
 * @author ruoyi
 */
public enum DesensitizedType
{
    /**
     * 姓名，只保留首字。
     *
     * <p>原实现是 s.replaceAll("(\\S)\\S(\\S*)", "$1*$2")，只替换第 2 个字：
     * 欧阳建国→欧*建国、司马相如之→司*相如之，四字名泄 3 字、五字名泄 4 字，对中文名基本等于没遮。
     * 它挂在 @Sensitive 上——即「用框架标准做法」的人一标注就中招。改前 @Sensitive 零使用，
     * 故本次修正零回归。
     */
    USERNAME(DesensitizedUtil::username),

    /**
     * 密码，全部字符都用*代替
     */
    PASSWORD(DesensitizedUtil::password),

    /**
     * 身份证，中间10位星号替换
     */
    ID_CARD(s -> s.replaceAll("(\\d{4})\\d{10}(\\d{3}[Xx]|\\d{4})", "$1** **** ****$2")),

    /**
     * 手机号，中间4位星号替换
     */
    PHONE(s -> s.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2")),

    /**
     * 电子邮箱，仅显示第一个字母和@后面的地址显示，其他星号替换
     */
    EMAIL(s -> s.replaceAll("(^.)[^@]*(@.*$)", "$1****$2")),

    /**
     * 银行卡号，保留最后4位，其他星号替换
     */
    BANK_CARD(s -> s.replaceAll("\\d{15}(\\d{3})", "**** **** **** **** $1")),

    /**
     * 车牌号码，包含普通车辆、新能源车辆
     */
    CAR_LICENSE(DesensitizedUtil::carLicense);

    private final Function<String, String> desensitizer;

    DesensitizedType(Function<String, String> desensitizer)
    {
        this.desensitizer = desensitizer;
    }

    public Function<String, String> desensitizer()
    {
        return desensitizer;
    }
}
