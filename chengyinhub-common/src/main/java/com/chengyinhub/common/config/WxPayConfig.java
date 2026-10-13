package com.chengyinhub.common.config;

import com.wechat.pay.java.core.Config;
import com.wechat.pay.java.core.RSAAutoCertificateConfig;
import com.wechat.pay.java.core.RSAConfig;
import com.wechat.pay.java.core.notification.NotificationConfig;
import com.wechat.pay.java.core.notification.RSAPublicKeyNotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 微信配置类
 * 包含微信支付和小程序内容检查配置
 */
@Component
public class WxPayConfig {

    // ==================== 微信支付配置 ====================
    // 是否启用微信支付
    @Value("${wx.pay.enabled:false}")
    private boolean wechatPayEnabled;

    // 商户API私钥路径（本地.pem文件路径）
    @Value("${wx.pay.private-key-path:}")
    private String privateKeyPath;

    // 商户号（替换为自己的商户号）
    @Value("${wx.pay.merchant-id:}")
    private String merchantId;

    // 商户证书序列号（在微信支付商户平台获取）
    @Value("${wx.pay.merchant-serial-number:}")
    private String merchantSerialNumber;

    // APIv3密钥（在微信支付商户平台设置）
    @Value("${wx.pay.api-v3-key:}")
    private String apiV3Key;

    // 公众号/小程序APPID（替换为自己的APPID）
    @Value("${wx.pay.appid:}")
    private String appid;

    /**
     * 微信开放平台「移动应用」appid,用于 App 支付(trade_type=APP)。
     *
     * ★ 与上面的 wx.pay.appid(小程序 appid)是**两个不同的应用**,不能混用:
     *   App 支付的下单 appid 必须是开放平台移动应用的,且该 appid 需在商户号
     *   下开通 App 支付权限并完成绑定。未配置时 App 下单必须明确报错,
     *   不得回落到小程序 appid —— 那会让微信侧签名校验失败且难以定位。
     */
    @Value("${wx.pay.app-appid:}")
    private String appAppid;

    //支付回调地址
    @Value("${wx.pay.notify-url:}")
    private String notifyUrl;

    //退款结果通知回调地址(不配则依赖商户平台默认退款回调配置)
    @Value("${wx.pay.refund-notify-url:}")
    private String refundNotifyUrl;

    @Value("${wx.pay.public-key-path:}")
    private String publicKeyPath;

    @Value("${wx.pay.public-key-id:}")
    private String publicKeyId;

    // ==================== 微信小程序内容检查配置 ====================
    // 是否启用内容检查
    @Value("${wx.sec-check.enabled:true}")
    private boolean secCheckEnabled;

    // 微信小程序AppId（用于内容检查）
    @Value("${wx.mini.appid:}")
    private String miniAppId;

    // 微信小程序Secret（用于内容检查）
    @Value("${wx.mini.secret:}")
    private String miniSecret;

    private static final Logger logger = LoggerFactory.getLogger(WxPayConfig.class);

    // ==================== 微信支付getter方法 ====================
    public boolean isWechatPayEnabled() {
        return wechatPayEnabled;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getMerchantSerialNumber() {
        return merchantSerialNumber;
    }

    public String getApiV3Key() {
        return apiV3Key;
    }

    public String getAppid() {
        return appid;
    }

    public String getAppAppid() {
        return appAppid;
    }

    public String getNotifyUrl() {
        return notifyUrl;
    }

    public String getRefundNotifyUrl() {
        return refundNotifyUrl;
    }

    public String getPublicKeyPath() {
        return publicKeyPath;
    }

    public String getPublicKeyId() {
        return publicKeyId;
    }

    // ==================== 微信小程序内容检查getter方法 ====================
    public boolean isSecCheckEnabled() {
        return secCheckEnabled;
    }

    public String getMiniAppId() {
        return miniAppId;
    }

    public String getMiniSecret() {
        return miniSecret;
    }

    // 初始化支付配置
    public Config getConfig() {
        try {
            //  使用平台证书模式
            logger.info("尝试使用RSAAutoCertificateConfig...");
            Config config = new RSAAutoCertificateConfig.Builder()
                    .merchantId(merchantId)
                    .privateKeyFromPath(privateKeyPath)
                    .merchantSerialNumber(merchantSerialNumber)
                    .apiV3Key(apiV3Key)
                    .build();

            logger.info("✓ 证书模式配置构建成功");
            return config;

        } catch (Exception e) {
            logger.error("平台证书模式方案失败：", e);
            throw new RuntimeException("无法初始化微信支付配置，请检查SDK版本或商户配置", e);
        }
    }

    public void logConfigDetails() {
        logger.info("=== 微信支付配置详情 ===");
        logger.info("wechatPayEnabled: {}", wechatPayEnabled);
        logger.info("merchantId: {}", merchantId);
        logger.info("merchantSerialNumber: {}", merchantSerialNumber);
        logger.info("privateKeyPath: {}", privateKeyPath);
        logger.info("apiV3Key: {}", apiV3Key != null ? "***" + apiV3Key.substring(Math.max(0, apiV3Key.length() - 4)) : "null");
        logger.info("appid: {}", appid);
        logger.info("notifyUrl: {}", notifyUrl);
        logger.info("publicKeyPath: {}", publicKeyPath);
        logger.info("publicKeyId: {}", publicKeyId);

        logger.info("=== 微信小程序内容检查配置详情 ===");
        logger.info("secCheckEnabled: {}", secCheckEnabled);
        logger.info("miniAppId: {}", miniAppId);
        logger.info("miniSecret: {}", miniSecret != null ? "***" + miniSecret.substring(Math.max(0, miniSecret.length() - 4)) : "null");
        logger.info("=== 配置详情结束 ===");

        // 文件检查
        File privateKeyFile = new File(privateKeyPath);
        logger.info("privateKeyFile exists: {}", privateKeyFile.exists());
        if (privateKeyFile.exists()) {
            logger.info("privateKeyFile absolute path: {}", privateKeyFile.getAbsolutePath());
            logger.info("privateKeyFile can read: {}", privateKeyFile.canRead());
            logger.info("privateKeyFile size: {} bytes", privateKeyFile.length());
        }
    }

    public Config getConfig2() {
        try {
            logger.info("=== 尝试解决方案 1: 简化配置构建 ===");

            // 读取私钥
            byte[] keyBytes = Files.readAllBytes(Paths.get(privateKeyPath));
            String privateKeyContent = new String(keyBytes).trim();

            logger.info("使用简化参数构建配置...");

            // 方法1: 最简化构建
            Config config = new RSAConfig.Builder()
                    .merchantId(merchantId)
                    .privateKey(privateKeyContent)
                    .merchantSerialNumber(merchantSerialNumber)
                    .build();

            logger.info("✓ 配置构建成功");
            return config;

        } catch (Exception e) {
            logger.error("方案1失败，尝试方案2", e);
            return tryAlternativeConfig();
        }
    }

    private Config tryAlternativeConfig() {
        try {
            logger.info("=== 尝试解决方案 2: 使用文件路径方式 ===");

            // 方法2: 直接使用文件路径，让SDK自己处理
            Config config = new RSAConfig.Builder()
                    .merchantId(merchantId)
                    .privateKeyFromPath(privateKeyPath)
                    .merchantSerialNumber(merchantSerialNumber)
                    .build();

            logger.info("✓ 文件路径方式配置构建成功");
            return config;

        } catch (Exception e) {
            logger.error("方案2失败，尝试方案3", e);
            return tryCertificateConfig();
        }
    }

    private Config tryCertificateConfig() {
        try {
            logger.info("=== 尝试解决方案 3: 切换到平台证书模式 ===");

            // 方法3: 尝试使用证书模式
            logger.info("尝试使用RSAAutoCertificateConfig...");
            Config config = new RSAAutoCertificateConfig.Builder()
                    .merchantId(merchantId)
                    .privateKeyFromPath(privateKeyPath)
                    .merchantSerialNumber(merchantSerialNumber)
                    .apiV3Key(apiV3Key)
                    .build();

            logger.info("✓ 证书模式配置构建成功");
            return config;

        } catch (Exception e) {
            logger.error("所有方案都失败", e);
            throw new RuntimeException("无法初始化微信支付配置，请检查SDK版本或商户配置", e);
        }
    }

    private void analyzeConfigError(Exception e) {
        // 分析可能的错误原因
        if (e.getMessage().contains("file") || e.getMessage().contains("path")) {
            logger.error("错误原因: 文件路径或读取问题");
            checkFileDetails();
        } else if (e.getMessage().contains("key") || e.getMessage().contains("format")) {
            logger.error("错误原因: 密钥格式问题");
        } else if (e.getMessage().contains("null")) {
            logger.error("错误原因: 参数为空");
        }
    }

    private void checkFileDetails() {
        File keyFile = new File(privateKeyPath);
        if (!keyFile.exists()) {
            logger.error("私钥文件不存在: {}", privateKeyPath);
            // 检查当前工作目录
            logger.error("当前工作目录: {}", System.getProperty("user.dir"));
            return;
        }

        logger.info("私钥文件详情:");
        logger.info(" - 文件大小: {} bytes", keyFile.length());
        logger.info(" - 可读: {}", keyFile.canRead());
        logger.info(" - 路径类型: {}", keyFile.isAbsolute() ? "绝对路径" : "相对路径");

    }

    /**
     * 获取通知配置
     */
    public NotificationConfig getNotificationConfig() {

        // 2.RSAAutoCertificateConfig (平台证书自动模式)
        return new RSAAutoCertificateConfig.Builder()
                .merchantId(merchantId)
                .privateKeyFromPath(privateKeyPath)
                .merchantSerialNumber(merchantSerialNumber)
                .apiV3Key(apiV3Key)
                .build();

        // 使用公钥模式 1.如果你使用的是微信支付公私钥，则使用 RSAPublicKeyNotificationconfig
//        return new RSAPublicKeyNotificationConfig.Builder()
//                .publicKeyFromPath(publicKeyPath)
//                .publicKeyId(publicKeyId)
//                .apiV3Key(apiV3Key)
//                .build();
    }
}
