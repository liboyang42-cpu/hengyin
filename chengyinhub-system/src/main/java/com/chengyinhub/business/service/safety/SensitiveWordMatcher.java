package com.chengyinhub.business.service.safety;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变的敏感词字典树(Round5D)。词与放行短语都按 {@link SensitiveTextNormalizer} 归一后入树,
 * 匹配同样在归一文本上做,所以插在字间的符号/空格/表情、全角、大小写、繁体都绕不过去。
 * 放行短语(如「成人票」「血量」)完整覆盖住的命中不算命中。
 * 结果只带分类与处置动作,不带命中词本身 —— 命中词绝不回给用户、也不写日志。
 */
public final class SensitiveWordMatcher {

    public static final String ACTION_BLOCK = "BLOCK";
    public static final String ACTION_LOG = "LOG";

    /** 一个字典词条。 */
    public static final class Entry {
        final String word;
        final String category;
        final String action;

        public Entry(String word, String category, String action) {
            this.word = word;
            this.category = category;
            this.action = action;
        }
    }

    /** 命中结果:只有分类与动作。 */
    public static final class Hit {
        private final String category;
        private final String action;

        Hit(String category, String action) {
            this.category = category;
            this.action = action;
        }

        public String getCategory() { return category; }
        public String getAction() { return action; }
        public boolean isBlock() { return ACTION_BLOCK.equals(action); }
    }

    private static final class Node {
        final Map<Integer, Node> next = new HashMap<Integer, Node>();
        String category;
        String action;
        boolean allow;
    }

    private final Node root = new Node();
    private final int size;

    private SensitiveWordMatcher(int size) {
        this.size = size;
    }

    public static SensitiveWordMatcher empty() {
        return new SensitiveWordMatcher(0);
    }

    public static SensitiveWordMatcher build(Collection<Entry> words, Collection<String> allows) {
        int count = 0;
        SensitiveWordMatcher m;
        List<Object[]> normalized = new ArrayList<Object[]>();
        if (words != null) for (Entry e : words) {
            if (e == null) continue;
            String w = SensitiveTextNormalizer.normalize(e.word);
            if (w.isEmpty()) continue;
            normalized.add(new Object[]{w, e});
            count++;
        }
        m = new SensitiveWordMatcher(count);
        for (Object[] pair : normalized) {
            Entry e = (Entry) pair[1];
            Node n = m.walk((String) pair[0]);
            // 同一个词出现多次:BLOCK 优先于 LOG,别让一条试运行词条把正式拦截降级。
            if (n.action == null || ACTION_BLOCK.equals(e.action)) {
                n.category = e.category;
                n.action = ACTION_LOG.equals(e.action) ? ACTION_LOG : ACTION_BLOCK;
            }
        }
        if (allows != null) for (String a : allows) {
            String p = SensitiveTextNormalizer.normalize(a);
            if (!p.isEmpty()) m.walk(p).allow = true;
        }
        return m;
    }

    private Node walk(String s) {
        Node n = root;
        int[] cps = s.codePoints().toArray();
        for (int cp : cps) n = n.next.computeIfAbsent(cp, k -> new Node());
        return n;
    }

    public int size() {
        return size;
    }

    /** 第一个不在放行短语里的命中;BLOCK 命中优先于 LOG 命中。没有命中返回 null。 */
    public Hit find(String text) {
        if (size == 0 || text == null || text.isEmpty()) return null;
        int[] cps = SensitiveTextNormalizer.normalize(text).codePoints().toArray();
        if (cps.length == 0) return null;
        // 先标出放行短语覆盖的区间,再找词;被完整覆盖的词命中丢弃。
        int[] allowEnd = new int[cps.length]; // allowEnd[i] = 从 i 起最长放行短语的终点(不含),0 表示无
        for (int i = 0; i < cps.length; i++) {
            Node n = root;
            for (int j = i; j < cps.length; j++) {
                n = n.next.get(cps[j]);
                if (n == null) break;
                if (n.allow) allowEnd[i] = j + 1;
            }
        }
        Hit logged = null;
        for (int i = 0; i < cps.length; i++) {
            Node n = root;
            for (int j = i; j < cps.length; j++) {
                n = n.next.get(cps[j]);
                if (n == null) break;
                if (n.action != null && !coveredByAllow(allowEnd, i, j + 1)) {
                    Hit hit = new Hit(n.category, n.action);
                    if (hit.isBlock()) return hit;
                    if (logged == null) logged = hit;
                }
            }
        }
        return logged;
    }

    /**
     * 仅供离线误伤报告脚本使用:列出文本里命中(且不在放行短语内)的归一后词条。
     * 线上路径一律用 {@link #find},命中词不得回给用户或写进日志。
     */
    public List<String> matchedWordsForReport(String text) {
        List<String> out = new ArrayList<String>();
        if (size == 0 || text == null || text.isEmpty()) return out;
        int[] cps = SensitiveTextNormalizer.normalize(text).codePoints().toArray();
        int[] allowEnd = new int[cps.length];
        for (int i = 0; i < cps.length; i++) {
            Node n = root;
            for (int j = i; j < cps.length; j++) {
                n = n.next.get(cps[j]);
                if (n == null) break;
                if (n.allow) allowEnd[i] = j + 1;
            }
        }
        for (int i = 0; i < cps.length; i++) {
            Node n = root;
            for (int j = i; j < cps.length; j++) {
                n = n.next.get(cps[j]);
                if (n == null) break;
                if (n.action != null && !coveredByAllow(allowEnd, i, j + 1)) {
                    out.add(new String(cps, i, j + 1 - i));
                }
            }
        }
        return out;
    }

    private static boolean coveredByAllow(int[] allowEnd, int start, int end) {
        for (int s = 0; s <= start; s++) {
            if (allowEnd[s] >= end) return true;
        }
        return false;
    }
}
