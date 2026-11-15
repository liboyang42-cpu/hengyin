package com.chengyinhub.business.push;

/** push_device 行投影（发送端只需要 token / 环境 / 语言）。 */
public class PushDevice
{
    private Long id;
    private Long memberId;
    private String token;
    private String environment;
    private String locale;

    public PushDevice() { }

    public PushDevice(Long id, Long memberId, String token, String environment, String locale)
    {
        this.id = id;
        this.memberId = memberId;
        this.token = token;
        this.environment = environment;
        this.locale = locale;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }

    /** 中文以外的系统语言用英文兜底文案。 */
    public boolean english()
    {
        return locale != null && locale.toLowerCase(java.util.Locale.ROOT).startsWith("en");
    }
}
