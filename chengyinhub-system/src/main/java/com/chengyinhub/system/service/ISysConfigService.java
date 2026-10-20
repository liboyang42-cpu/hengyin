package com.chengyinhub.system.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.chengyinhub.system.domain.SysConfig;

/**
 * 参数配置 服务层
 *
 * @author ruoyi
 */
public interface ISysConfigService
{
    /**
     * 资金政策参数键：这些键<b>不是普通编辑接口</b>（探店日施工审计 2 §「权限、审计和失败语义」
     * 开头点名「冷却配置」）。通用参数页只写新值、不留前值，「理由」它本来就不采集 ——
     * 走那条路，「谁在什么时候把 48 改成了 1、为什么」一条都查不出来。
     *
     * <p>所以 {@link #insertConfig} 与 {@link #updateConfig} 对这些键一律 fail-closed，
     * 只留 {@link #updateFundsPolicyConfigByKey} 这一个出口，由各自的专用端点
     * （专用权限 + 理由必填 + 前值/后值落审计表）调用。
     *
     * <p>⚠️ 这里的串必须与消费方的键常量逐字一致（如
     * {@code IPlayerWithdrawalCooldownService.CONFIG_KEY}）。两处各写一遍字面量、改一处漏一处，
     * 就是「闸还在、但它守的是另一个键」的静默失效 ——
     * {@code CooldownConfigWriteThroughRealConfigServiceTest} 把两者钉在一起。
     */
    public static final Set<String> FUNDS_POLICY_CONFIG_KEYS = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("withdrawal.player-complaint-cooldown-hours")));

    /**
     * 查询参数配置信息
     * 
     * @param configId 参数配置ID
     * @return 参数配置信息
     */
    public SysConfig selectConfigById(Long configId);

    /**
     * 根据键名查询参数配置信息
     * 
     * @param configKey 参数键名
     * @return 参数键值
     */
    public String selectConfigByKey(String configKey);

    /**
     * 获取验证码开关
     * 
     * @return true开启，false关闭
     */
    public boolean selectCaptchaEnabled();

    /**
     * 查询参数配置列表
     * 
     * @param config 参数配置信息
     * @return 参数配置集合
     */
    public List<SysConfig> selectConfigList(SysConfig config);

    /**
     * 新增参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    public int insertConfig(SysConfig config);

    /**
     * 修改参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    public int updateConfig(SysConfig config);

    /**
     * 资金政策参数键的<b>专用</b>改写：按键名读回整行（拿到 config_id）后再更新。
     *
     * <p>为什么不能直接 new 一个 SysConfig 丢给 {@link #updateConfig}：那条实现第一句就
     * {@code selectConfigById(config.getConfigId())} 再解引用，主键为 null ⇒ NPE；就算绕过 NPE，
     * UPDATE 的 {@code where config_id = #{configId}} 同样恒命中 0 行，而返回值一旦被丢弃，
     * 就成了「审计说改了、配置没动」的静默假成功。
     *
     * <p>本方法三样都做：命中行数必须为 1、改完<b>回读数据库那一行</b>（不是回读自己刚写的缓存）
     * 核对值一致、任一不满足即抛并让调用方整笔回滚。
     *
     * @param configKey   {@link #FUNDS_POLICY_CONFIG_KEYS} 里的键
     * @param configValue 新值
     * @param updateBy    操作人
     * @return 实际更新行数（恒为 1，否则已抛）
     */
    public int updateFundsPolicyConfigByKey(String configKey, String configValue, String updateBy);

    /** 资金政策专用当前锁读，绕过普通参数缓存；缺行返回空串。 */
    public String selectFundsPolicyValueForUpdate(String configKey);

    /**
     * 批量删除参数信息
     * 
     * @param configIds 需要删除的参数ID
     */
    public void deleteConfigByIds(Long[] configIds);

    /**
     * 加载参数缓存数据
     */
    public void loadingConfigCache();

    /**
     * 清空参数缓存数据
     */
    public void clearConfigCache();

    /**
     * 重置参数缓存数据
     */
    public void resetConfigCache();

    /**
     * 校验参数键名是否唯一
     * 
     * @param config 参数信息
     * @return 结果
     */
    public boolean checkConfigKeyUnique(SysConfig config);
}
