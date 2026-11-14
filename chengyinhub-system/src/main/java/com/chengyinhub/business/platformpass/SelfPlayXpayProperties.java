package com.chengyinhub.business.platformpass;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自玩票（平台自有主题）走小程序虚拟支付的开关与商品映射。默认关闭：
 * 关闭时所有自玩单与现状逐字一致（普通微信支付 JSAPI）。
 * OfferID / AppKey / env / apiBase 复用通行证的 {@code platform-pass.xpay.*}，这里不另立一套支付配置。
 *
 * <p>{@code chengyin.selfplay.xpay.goods} 形如 {@code 3990=goods_a,4990=goods_b}：键是应付「分」，值是虚拟支付后台的道具 ID。
 * 应付金额不在映射里的单不走虚拟支付（也不降级成 JSAPI 的虚拟支付单：该单按开关关闭时的 JSAPI 处理）。</p>
 */
@Component
@ConfigurationProperties(prefix = "chengyin.selfplay.xpay")
public class SelfPlayXpayProperties {
    private boolean enabled;
    private String goods = "";
    /** 已签发但未付的虚拟支付单，超过此分钟数后允许由对账确认未付并放行关单。 */
    private int closeGraceMinutes = 120;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getGoods() { return goods; }
    public void setGoods(String v) { goods = v == null ? "" : v; }
    public int getCloseGraceMinutes() { return closeGraceMinutes; }
    public void setCloseGraceMinutes(int v) { closeGraceMinutes = Math.max(1, v); }

    /** 解析后的 分→道具ID；格式错误的项被忽略（该价位视为未配置，不走虚拟支付）。 */
    public Map<Long, String> goodsByFen() {
        Map<Long, String> out = new LinkedHashMap<>();
        for (String item : goods.split(",")) {
            String[] kv = item.trim().split("=", 2);
            if (kv.length != 2 || kv[1].isBlank()) continue;
            try {
                long fen = Long.parseLong(kv[0].trim());
                String id = kv[1].trim();
                if (fen > 0 && id.matches("[A-Za-z0-9_.:-]{1,64}")) out.put(fen, id);
            } catch (NumberFormatException ignored) {
                // 该项视为未配置
            }
        }
        return out;
    }

    public String goodsIdFor(long fen) { return goodsByFen().get(fen); }
}
