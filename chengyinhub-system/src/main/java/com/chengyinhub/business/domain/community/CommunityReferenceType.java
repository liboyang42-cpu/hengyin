package com.chengyinhub.business.domain.community;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 帖文一等业务关联(community_post_reference.reference_type)的唯一真源。
 *
 * <p>C:U-042:可选项白名单、发帖校验、Mapper 各 choose 分支、DDL
 * {@code chk_community_post_reference_type} 曾三处各写一份(MEMBER 在一处、CLUB 缺一处)。
 * 现在服务层只认这里;{@code CommunityReferenceTypeConsistencyTest} 把 Mapper 与 DDL 钉在同一集合上。
 */
public enum CommunityReferenceType {
    ACTIVITY, TOPIC, ROUTE, CLUB, POI;

    /** 全部取值的大写字面量,顺序与声明一致。 */
    public static final Set<String> CODES = codes();

    private static Set<String> codes() {
        Set<String> out = new LinkedHashSet<>();
        for (CommunityReferenceType t : values()) out.add(t.name());
        return Collections.unmodifiableSet(out);
    }
}
