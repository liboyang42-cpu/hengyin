package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopicNode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 自由定向终章:按完成顺序把节点碎片拼成结局信。纯函数,无依赖,便于单测。 */
public final class PlayNarrativeSupport {

    private PlayNarrativeSupport() {}

    private static final String[] OPENERS = {
        "你从%s开始,在%s收尾——这座城市的故事,你是这样读的。",
        "第一站你选了%s,最后停在%s。别人的路线和你的,不会一样。",
        "%s是起点,%s是终点。中间那些脚步,拼成了只属于你的一段城事。"
    };

    /** @param completedInOrder 已按完成时间升序的完成节点 */
    public static Map<String, Object> buildEnding(List<CmsTopicNode> completedInOrder) {
        Map<String, Object> out = new HashMap<>();
        List<Map<String, Object>> frags = new ArrayList<>();
        if (completedInOrder == null || completedInOrder.isEmpty()) {
            out.put("opener", "");
            out.put("fragments", frags);
            return out;
        }
        int step = 1;
        for (CmsTopicNode n : completedInOrder) {
            Map<String, Object> f = new HashMap<>();
            f.put("step", step++);
            f.put("nodeId", n.getId());
            f.put("name", n.getName());
            String frag = n.getFragmentText();
            f.put("text", (frag != null && frag.trim().length() > 0) ? frag : n.getDescription());
            frags.add(f);
        }
        CmsTopicNode first = completedInOrder.get(0);
        CmsTopicNode last = completedInOrder.get(completedInOrder.size() - 1);
        long fid = first.getId() == null ? 0L : Math.abs(first.getId());
        int idx = (int) (fid % OPENERS.length);
        out.put("opener", String.format(OPENERS[idx], first.getName(), last.getName()));
        out.put("fragments", frags);
        return out;
    }

    // ===== S3: 自由定向"途中台词"位置槽(纯函数,零 LLM,按完成比例分三档) =====

    public static final String SLOT_EXPLORE_FIRST = "explore_first";
    public static final String SLOT_EXPLORE_MIDDLE = "explore_middle";
    public static final String SLOT_EXPLORE_LAST = "explore_last";

    /** 分档阈值:done/total < 1/3 → first;< 2/3 → middle;否则 → last */
    private static final double SLOT_FIRST_THRESHOLD = 1.0 / 3;
    private static final double SLOT_MIDDLE_THRESHOLD = 2.0 / 3;

    /**
     * 按玩家进度(已完成节点数/总节点数)挑选途中台词的位置槽。
     * total<=0(无节点/尚未加载)兜底 explore_first。
     */
    public static String pickExploreSlot(int done, int total) {
        if (total <= 0) return SLOT_EXPLORE_FIRST;
        double ratio = (double) done / total;
        if (ratio < SLOT_FIRST_THRESHOLD) return SLOT_EXPLORE_FIRST;
        if (ratio < SLOT_MIDDLE_THRESHOLD) return SLOT_EXPLORE_MIDDLE;
        return SLOT_EXPLORE_LAST;
    }

    // ===== S4: 服务端营业态三态(纯函数,不依赖真实时钟,便于单测) =====

    public static final String OPEN_NOW = "营业中";
    public static final String CLOSING_SOON = "即将打烊";
    public static final String CLOSED = "已打烊";

    /** 距打烊 ≤ 该阈值(分钟)判为"即将打烊" */
    public static final int CLOSING_SOON_MIN = 30;

    /** businessTime 格式:"HH:mm[-~—至]HH:mm"(与前端 inBizWindow 同正则),支持跨午夜 */
    private static final Pattern BIZ_TIME = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*[-~—至]\\s*(\\d{1,2}):(\\d{2})");

    /**
     * 由营业时间串 + 当前"当日分钟数"(时*60+分)计算三态营业态。
     * 解析不了/空 → "";营业窗内且距打烊 > 阈值 → "营业中";窗内且距打烊 ≤ 阈值 → "即将打烊";窗外 → "已打烊"。
     * 跨午夜(如 20:00-02:00,start>end)正确处理"在窗内"与"距打烊"。
     */
    public static String computeOpenStatus(String businessTime, int nowMinuteOfDay) {
        if (businessTime == null) return "";
        Matcher mm = BIZ_TIME.matcher(businessTime);
        if (!mm.find()) return "";
        int a = Integer.parseInt(mm.group(1)) * 60 + Integer.parseInt(mm.group(2)); // 开门
        int b = Integer.parseInt(mm.group(3)) * 60 + Integer.parseInt(mm.group(4)); // 打烊
        int now = nowMinuteOfDay;
        boolean inWindow;
        int minutesToClose;
        if (b >= a) {
            // 同日窗
            inWindow = now >= a && now <= b;
            minutesToClose = b - now;
        } else {
            // 跨午夜窗(如 20:00-02:00)
            inWindow = now >= a || now <= b;
            minutesToClose = (now >= a) ? (b + 1440 - now) : (b - now);
        }
        if (!inWindow) return CLOSED;
        return minutesToClose <= CLOSING_SOON_MIN ? CLOSING_SOON : OPEN_NOW;
    }
}
