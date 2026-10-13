package com.chengyinhub.business.domain.vo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.chengyinhub.business.domain.PlayerObjectCard;

/**
 * 小程序藏品册安全视图:只给渲染一张卡要用的字段。
 *
 * <p>刻意不带 memberId —— 卡的归属在服务端 WHERE 里已经用过了,回包里再带一份
 * 等于把「这是谁的卡」交给客户端,而客户端会把它传回来。
 */
public class PlayerObjectCardVO {
    private static final String READY = "READY";
    private static final String NONE = "NONE";

    private Long id;
    private String sourceUrl;
    /** 抠图贴纸,可空:null 就是没抠出来,客户端继续用 sourceUrl 那张照片卡面。 */
    private String cutoutUrl;
    /** 物品在原照片里的位置 [x, y, w, h](0 到 1 的比例),可空:没抠图或位置不对就不给。 */
    private List<Double> cutoutBox;
    private String title;
    /** AI 认物的分类,可空:没认出来就不归类,不兜成「其他」。 */
    private String category;
    /** 拍下这件的地方「主题名 · 节点名」,可空:节点删了就没有。 */
    private String place;
    private String caption;
    private String cardStyle;
    private String originType;
    private String originId;
    private Date createTime;
    /** NONE / QUEUED / GENERATING / READY / FAILED */
    private String genStatus;
    /** 客户端只认这个:1 张 = 照片卡,24 张 = 转台。永不为空。 */
    private List<String> frames;

    public static PlayerObjectCardVO from(PlayerObjectCard source) {
        PlayerObjectCardVO target = new PlayerObjectCardVO();
        target.id = source.getId();
        target.sourceUrl = source.getSourceUrl();
        target.cutoutUrl = source.getCutoutUrl();
        target.cutoutBox = boxOf(source.getCutoutBox());
        target.title = source.getTitle();
        target.category = source.getCategory();
        target.place = source.getPlace();
        target.caption = source.getCaption();
        target.cardStyle = source.getCardStyle();
        target.originType = source.getOriginType();
        target.originId = source.getOriginId();
        target.createTime = source.getCreateTime();
        target.genStatus = source.getGenStatus() == null ? NONE : source.getGenStatus();
        target.frames = framesOf(source);
        return target;
    }

    /** 「x,y,w,h」→ 四个比例。格式不对、越出 0 到 1、宽高为 0 都当没有:位置错了比没有更难看。 */
    private static List<Double> boxOf(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(",");
        if (parts.length != 4) return null;
        double[] v = new double[4];
        try {
            for (int i = 0; i < 4; i++) v[i] = Double.parseDouble(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (v[0] < 0 || v[1] < 0 || v[2] <= 0 || v[3] <= 0 || v[0] + v[2] > 1.0001 || v[1] + v[3] > 1.0001) {
            return null;
        }
        return Arrays.asList(v[0], v[1], v[2], v[3]);
    }

    /** 只有 READY 且帧数据齐全才出转台帧;其余一律退回原照片,绝不出空数组。 */
    private static List<String> framesOf(PlayerObjectCard card) {
        String prefix = card.getFramesPrefix();
        Integer count = card.getFrameCount();
        if (!READY.equals(card.getGenStatus()) || prefix == null || prefix.isEmpty() || count == null || count <= 0) {
            return Collections.singletonList(card.getSourceUrl());
        }
        List<String> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(prefix + String.format("%02d.webp", i));
        }
        return frames;
    }

    public Long getId() { return id; }
    public String getSourceUrl() { return sourceUrl; }
    public String getPlace() { return place; }
    public List<Double> getCutoutBox() { return cutoutBox; }
    public String getCutoutUrl() { return cutoutUrl; }
    public String getTitle() { return title; }
    public String getCategory() { return category; }
    public String getCaption() { return caption; }
    public String getCardStyle() { return cardStyle; }
    public String getOriginType() { return originType; }
    public String getOriginId() { return originId; }
    public Date getCreateTime() { return createTime; }
    public String getGenStatus() { return genStatus; }
    public List<String> getFrames() { return frames; }
}
