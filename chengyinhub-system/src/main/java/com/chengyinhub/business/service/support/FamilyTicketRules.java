package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.OmsTicket;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * F12 亲子票(1 大 1 小)规则,照搬携程/美团门票「亲子票」:
 * 一张票、一个码、一次核销服务两个人;票种写明构成和儿童年龄段;只登记儿童年龄,不要姓名。
 *
 * <p>首版只允许 1+0(默认)与 1+1。以后要放开 2+1,只改 {@link #validateComposition}。
 */
public final class FamilyTicketRules {
    /** 儿童年龄段的合理上下界(含)。 */
    public static final int AGE_FLOOR = 0;
    public static final int AGE_CEILING = 17;
    public static final int PERK_TEXT_MAX = 200;
    public static final String ONLY_ONE_PLUS_ONE = "亲子票限 1 大 1 小";

    private static final ObjectMapper JSON = new ObjectMapper();

    private FamilyTicketRules() { }

    public static int adults(OmsTicket ticket) {
        return ticket == null || ticket.getAdultCount() == null ? 1 : ticket.getAdultCount();
    }

    public static int children(OmsTicket ticket) {
        return ticket == null || ticket.getChildCount() == null ? 0 : ticket.getChildCount();
    }

    public static boolean isFamily(OmsTicket ticket) {
        return children(ticket) > 0;
    }

    public static boolean anyFamily(Collection<OmsTicket> tickets) {
        if (tickets == null) return false;
        for (OmsTicket ticket : tickets) if (isFamily(ticket)) return true;
        return false;
    }

    /**
     * 票种保存时校验:只允许 1+0 / 1+1;亲子票年龄段必填且 min ≤ max;普通票不得带年龄段。
     * 校验失败抛 IllegalArgumentException(消息给作者看)。
     */
    public static void validateComposition(Integer adultCount, Integer childCount, Integer ageMin, Integer ageMax) {
        int adults = adultCount == null ? 1 : adultCount;
        int children = childCount == null ? 0 : childCount;
        if (adults != 1 || (children != 0 && children != 1)) {
            throw new IllegalArgumentException(ONLY_ONE_PLUS_ONE);
        }
        if (children == 0) {
            if (ageMin != null || ageMax != null) {
                throw new IllegalArgumentException("只有亲子票需要填写儿童年龄段");
            }
            return;
        }
        if (ageMin == null || ageMax == null) {
            throw new IllegalArgumentException("请填写亲子票的儿童年龄段");
        }
        if (ageMin < AGE_FLOOR || ageMax > AGE_CEILING || ageMin > ageMax) {
            throw new IllegalArgumentException("儿童年龄段需在 " + AGE_FLOOR + "–" + AGE_CEILING + " 岁之间，且起始不大于结束");
        }
    }

    public static void validateComposition(OmsTicket ticket) {
        if (ticket == null) return;
        validateComposition(ticket.getAdultCount(), ticket.getChildCount(),
                ticket.getChildAgeMin(), ticket.getChildAgeMax());
    }

    /**
     * 下单时校验儿童年龄并返回要落库的 party_snapshot_json;非亲子票返回 null(且不得带 childAge)。
     */
    public static String partySnapshotForOrder(OmsTicket ticket, Integer childAge) {
        if (!isFamily(ticket)) {
            if (childAge != null) throw new IllegalArgumentException("所选票种不是亲子票");
            return null;
        }
        validateComposition(ticket);
        if (childAge == null) throw new IllegalArgumentException("请选择儿童年龄");
        if (childAge < ticket.getChildAgeMin() || childAge > ticket.getChildAgeMax()) {
            throw new IllegalArgumentException("儿童年龄需在 " + ticket.getChildAgeMin() + "–"
                    + ticket.getChildAgeMax() + " 岁");
        }
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("adultCount", adults(ticket));
        party.put("childCount", children(ticket));
        party.put("childAge", childAge);
        try {
            return JSON.writeValueAsString(party);
        } catch (Exception e) {
            throw new IllegalStateException("亲子票人数构成写入失败", e);
        }
    }

    /** party_snapshot_json → {adultCount, childCount, childAge};空或坏数据返回 null(读面不拦订单)。 */
    public static Map<String, Object> partyOf(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.trim().isEmpty()) return null;
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            if (node == null || !node.isObject() || node.path("childCount").asInt(0) <= 0) return null;
            Map<String, Object> party = new LinkedHashMap<>();
            party.put("adultCount", node.path("adultCount").asInt(1));
            party.put("childCount", node.path("childCount").asInt(0));
            party.put("childAge", node.hasNonNull("childAge") ? node.get("childAge").asInt() : null);
            return party;
        } catch (Exception e) {
            return null;
        }
    }

    /** 店员核销结果页的人数构成:亲子票带儿童份说明;非亲子票返回 null。 */
    public static Map<String, Object> redemptionParty(String snapshotJson, MerchantChapterOffer offer) {
        Map<String, Object> party = partyOf(snapshotJson);
        if (party == null) return null;
        String perkText = offer == null ? null : offer.getFamilyPerkText();
        party.put("familyPerkText", perkText == null || perkText.trim().isEmpty() ? null : perkText.trim());
        party.put("label", label(party));
        return party;
    }

    /** 「亲子票：1 大 1 小（儿童 7 岁）· 儿童份：xx」 */
    public static String label(Map<String, Object> party) {
        if (party == null) return null;
        StringBuilder out = new StringBuilder("亲子票：").append(party.get("adultCount")).append(" 大 ")
                .append(party.get("childCount")).append(" 小");
        if (party.get("childAge") != null) out.append("（儿童 ").append(party.get("childAge")).append(" 岁）");
        Object perk = party.get("familyPerkText");
        if (perk != null) out.append("· 儿童份：").append(perk);
        return out.toString();
    }

    /** 商家设置接受亲子票时的字段校验。 */
    public static String normalizePerkText(Integer familyAccepted, String familyPerkText) {
        if (familyAccepted != null && familyAccepted != 0 && familyAccepted != 1) {
            throw new IllegalArgumentException("接受亲子票只能是开或关");
        }
        String text = familyPerkText == null ? null : familyPerkText.trim();
        if (text != null && text.isEmpty()) text = null;
        if (text != null && text.length() > PERK_TEXT_MAX) {
            throw new IllegalArgumentException("儿童份说明最多 " + PERK_TEXT_MAX + " 个字");
        }
        return text;
    }

    public static boolean accepts(MerchantChapterOffer offer) {
        return offer != null && Integer.valueOf(1).equals(offer.getFamilyAccepted());
    }
}
