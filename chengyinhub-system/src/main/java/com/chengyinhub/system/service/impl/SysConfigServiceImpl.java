package com.chengyinhub.system.service.impl;

import java.util.Collection;
import java.util.List;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.common.annotation.DataSource;
import com.chengyinhub.common.constant.CacheConstants;
import com.chengyinhub.common.constant.UserConstants;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.DataSourceType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysConfig;
import com.chengyinhub.system.mapper.SysConfigMapper;
import com.chengyinhub.system.service.ISysConfigService;

/**
 * 参数配置 服务层实现
 * 
 * @author ruoyi
 */
@Service
public class SysConfigServiceImpl implements ISysConfigService
{
    private static final Logger log = LoggerFactory.getLogger(SysConfigServiceImpl.class);
    @Autowired
    private SysConfigMapper configMapper;

    @Autowired
    private RedisCache redisCache;

    /**
     * 项目启动时，初始化参数到缓存
     */
    @PostConstruct
    public void init()
    {
        loadingConfigCache();
    }

    /**
     * 查询参数配置信息
     * 
     * @param configId 参数配置ID
     * @return 参数配置信息
     */
    @Override
    @DataSource(DataSourceType.MASTER)
    public SysConfig selectConfigById(Long configId)
    {
        SysConfig config = new SysConfig();
        config.setConfigId(configId);
        return configMapper.selectConfig(config);
    }

    /**
     * 根据键名查询参数配置信息
     * 
     * @param configKey 参数key
     * @return 参数键值
     */
    @Override
    public String selectConfigByKey(String configKey)
    {
        String configValue = Convert.toStr(redisCache.getCacheObject(getCacheKey(configKey)));
        if (StringUtils.isNotEmpty(configValue))
        {
            return configValue;
        }
        SysConfig config = new SysConfig();
        config.setConfigKey(configKey);
        SysConfig retConfig = configMapper.selectConfig(config);
        if (StringUtils.isNotNull(retConfig))
        {
            redisCache.setCacheObject(getCacheKey(configKey), retConfig.getConfigValue());
            return retConfig.getConfigValue();
        }
        return StringUtils.EMPTY;
    }

    /**
     * 获取验证码开关
     * 
     * @return true开启，false关闭
     */
    @Override
    public boolean selectCaptchaEnabled()
    {
        String captchaEnabled = selectConfigByKey("sys.account.captchaEnabled");
        if (StringUtils.isEmpty(captchaEnabled))
        {
            return true;
        }
        return Convert.toBool(captchaEnabled);
    }

    /**
     * 查询参数配置列表
     * 
     * @param config 参数配置信息
     * @return 参数配置集合
     */
    @Override
    public List<SysConfig> selectConfigList(SysConfig config)
    {
        return configMapper.selectConfigList(config);
    }

    /**
     * 新增参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public int insertConfig(SysConfig config)
    {
        // 资金政策键由各自的迁移 seed，通用参数页不得凭空造一个 —— 否则「先改名腾位、再新建同名键」
        // 就绕开了专用入口的理由与前后值审计。
        requireNotFundsPolicyKey(config.getConfigKey());
        int row = configMapper.insertConfig(config);
        if (row > 0)
        {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
        return row;
    }

    /**
     * 修改参数配置
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public int updateConfig(SysConfig config)
    {
        // ★通用参数页对资金政策键 fail-closed。它只写新值、不留前值、不采集理由，
        //   而「谁把 48 改成 1、为什么」正是审计 2 对冷却配置点名要的三件事。
        //   走 updateFundsPolicyConfigByKey（专用权限 + 理由必填 + 前后值落审计表）那一条。
        //   两侧都查：改成资金政策键、以及把资金政策键改名腾走，都是同一条旁路。
        requireNotFundsPolicyKey(config.getConfigKey());
        SysConfig temp = configMapper.selectConfigById(config.getConfigId());
        requireNotFundsPolicyKey(temp == null ? null : temp.getConfigKey());
        if (!StringUtils.equals(temp.getConfigKey(), config.getConfigKey()))
        {
            redisCache.deleteObject(getCacheKey(temp.getConfigKey()));
        }

        int row = configMapper.updateConfig(config);
        if (row > 0)
        {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
        return row;
    }

    /**
     * 资金政策键的专用改写：先按键名把整行读回来（拿到 config_id），再更新、核行数、回读核值。
     */
    @Override
    @DataSource(DataSourceType.MASTER)
    @Transactional(rollbackFor = Exception.class)
    public int updateFundsPolicyConfigByKey(String configKey, String configValue, String updateBy)
    {
        if (StringUtils.isEmpty(configKey) || StringUtils.isEmpty(configValue))
        {
            throw new ServiceException("参数键名与键值都不能为空");
        }
        requireFundsPolicyKey(configKey);
        SysConfig current = configMapper.selectFundsPolicyConfigForUpdate(configKey);
        if (StringUtils.isNull(current))
        {
            // 键不存在时绝不当成「改成功了」：调用方的审计行已经写了，必须一并回滚。
            throw new ServiceException("参数【" + configKey + "】不存在，无法改写");
        }
        current.setConfigValue(configValue);
        current.setUpdateBy(updateBy);
        int row = configMapper.updateConfig(current);
        if (row != 1)
        {
            throw new ServiceException("参数【" + configKey + "】更新命中 " + row + " 行，预期 1 行");
        }
        // ★回读问的是数据库那一行，不是自己刚写进 Redis 的值 —— 后者是恒真断言。
        SysConfig readBack = configMapper.selectFundsPolicyConfigForUpdate(configKey);
        if (StringUtils.isNull(readBack) || !StringUtils.equals(configValue, readBack.getConfigValue()))
        {
            throw new ServiceException("参数【" + configKey + "】回读与写入不一致");
        }
        invalidateFundsPolicyCacheAfterCommit(configKey);
        return row;
    }

    @Override
    @DataSource(DataSourceType.MASTER)
    public String selectFundsPolicyValueForUpdate(String configKey)
    {
        requireFundsPolicyKey(configKey);
        SysConfig current = configMapper.selectFundsPolicyConfigForUpdate(configKey);
        return current == null ? StringUtils.EMPTY : current.getConfigValue();
    }

    private static void requireFundsPolicyKey(String configKey)
    {
        if (!ISysConfigService.FUNDS_POLICY_CONFIG_KEYS.contains(configKey))
        {
            throw new ServiceException("参数【" + configKey + "】不是资金政策专用键");
        }
    }

    private void invalidateFundsPolicyCacheAfterCommit(final String configKey)
    {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { invalidateFundsPolicyCache(configKey); }
            });
        }
        else
        {
            // 无外层事务的直接调用已按数据库自动提交完成；不能注册虚假的提交回调。
            invalidateFundsPolicyCache(configKey);
        }
    }

    private void invalidateFundsPolicyCache(String configKey)
    {
        try { redisCache.deleteObject(getCacheKey(configKey)); }
        catch (RuntimeException unavailable)
        {
            // 数据库已经提交；资金业务当前读不依赖此缓存，不把失效故障伪装成回滚。
            log.warn("资金政策缓存失效失败 key={}", configKey, unavailable);
        }
    }

    private static void requireNotFundsPolicyKey(String configKey)
    {
        if (configKey != null && ISysConfigService.FUNDS_POLICY_CONFIG_KEYS.contains(configKey))
        {
            throw new ServiceException("参数【" + configKey + "】是资金政策参数，必须走专用入口改写"
                    + "（需专用权限 + 填写理由，前值/后值会落审计表），通用参数管理不受理");
        }
    }

    /**
     * 批量删除参数信息
     * 
     * @param configIds 需要删除的参数ID
     */
    @Override
    public void deleteConfigByIds(Long[] configIds)
    {
        for (Long configId : configIds)
        {
            SysConfig config = selectConfigById(configId);
            requireNotFundsPolicyKey(config.getConfigKey());
            if (StringUtils.equals(UserConstants.YES, config.getConfigType()))
            {
                throw new ServiceException(String.format("内置参数【%1$s】不能删除 ", config.getConfigKey()));
            }
            configMapper.deleteConfigById(configId);
            redisCache.deleteObject(getCacheKey(config.getConfigKey()));
        }
    }

    /**
     * 加载参数缓存数据
     */
    @Override
    public void loadingConfigCache()
    {
        List<SysConfig> configsList = configMapper.selectConfigList(new SysConfig());
        for (SysConfig config : configsList)
        {
            redisCache.setCacheObject(getCacheKey(config.getConfigKey()), config.getConfigValue());
        }
    }

    /**
     * 清空参数缓存数据
     */
    @Override
    public void clearConfigCache()
    {
        Collection<String> keys = redisCache.keys(CacheConstants.SYS_CONFIG_KEY + "*");
        redisCache.deleteObject(keys);
    }

    /**
     * 重置参数缓存数据
     */
    @Override
    public void resetConfigCache()
    {
        clearConfigCache();
        loadingConfigCache();
    }

    /**
     * 校验参数键名是否唯一
     * 
     * @param config 参数配置信息
     * @return 结果
     */
    @Override
    public boolean checkConfigKeyUnique(SysConfig config)
    {
        Long configId = StringUtils.isNull(config.getConfigId()) ? -1L : config.getConfigId();
        SysConfig info = configMapper.checkConfigKeyUnique(config.getConfigKey());
        if (StringUtils.isNotNull(info) && info.getConfigId().longValue() != configId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    /**
     * 设置cache key
     * 
     * @param configKey 参数键
     * @return 缓存键key
     */
    private String getCacheKey(String configKey)
    {
        return CacheConstants.SYS_CONFIG_KEY + configKey;
    }
}
