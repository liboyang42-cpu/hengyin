package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import java.util.*;

/** Transaction-local rows only. Never returned to callers or retained by the immutable candidate. */
final class ContentCaptureRows {
    CmsTopic topic;
    List<CmsTopicNode> nodes;
    List<CmsTopicChapter> chapters;
    List<ThemeStateKey> keys;
    final Map<Long, CmsMemberTemplate> templates = new LinkedHashMap<>();
    final Map<Long, CmsRegistrationMerchant> merchants = new LinkedHashMap<>();
    final Map<Long, SmsCoupon> coupons = new LinkedHashMap<>();
    TopicSaleSnapshot sale;
    boolean npcScopesCaptured;
    final Map<Long, List<NpcProfile>> nodeNpcs = new LinkedHashMap<>();
    // member_id -> exact merchant row (null records a missing identity, never a guessed ID).
    final Map<Long, MmsMerchant> npcMerchants = new LinkedHashMap<>();
    // mms_merchant.id -> enabled profiles, only for available exact merchant identities.
    final Map<Long, List<NpcProfile>> merchantNpcs = new LinkedHashMap<>();
}
