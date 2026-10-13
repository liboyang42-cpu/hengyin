package com.chengyinhub.business.service.ai;

/**
 * 抠图结果落到本站可控的存储。第三方回来的字节不能直接进数据库。
 *
 * @author chengyinhub
 */
public interface CardCutoutStorage {

    /**
     * 把供应商返回的临时抠图地址变成我们桶里的对象:下载 → 校验 → 转存。
     *
     * <p>刻意收成一次调用:第三方结果地址只保留 30 分钟(施工文档 §2.3),「拿到」与「转走」
     * 之间不留可以被忘掉的中间步骤 —— 分成两个方法调用就等于给失败留了一个不报错的窗口。
     *
     * @param tempPngUrl 第三方结果地址,带签名,只在这一次传输里用,不落库、不进日志
     * @param ownerId    这张卡的卡主,写进对象元数据,便于将来按人核对与清理
     * @return 自家地址 + 物品在原图里的位置;{@code null} = 没有抠图,卡面退回照片档
     */
    CardCutout transfer(String tempPngUrl, long ownerId);

    /**
     * 把玩家照片变成商品分割吃得下的地址(施工文档 §2.3:JPEG/PNG/BMP、≤ 3MB、宽高都 &lt; 2000、
     * 阿里云取得到的绝对地址)。合规的原图原样交地址;超限的缩到长边 1600、站内相对路径,都转存一张交新地址。
     *
     * @param photoUrl 已过铸卡来源闸的照片地址
     * @param ownerId  卡主,转存时写进对象元数据
     * @return 可以直接交给接口的地址;{@code null} = 读不出或缩不了,这次不抠图
     */
    String segmentInputOf(String photoUrl, long ownerId);
}
