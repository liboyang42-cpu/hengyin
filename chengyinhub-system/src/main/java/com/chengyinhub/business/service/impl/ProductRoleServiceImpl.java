package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ProductRole;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.ProductRoleMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.RoleEntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** P0-35 角色槽(D6)。 */
@Service
public class ProductRoleServiceImpl implements IProductRoleService {

    private static final Logger log = LoggerFactory.getLogger(ProductRoleServiceImpl.class);

    @Autowired
    private ProductRoleMapper productRoleMapper;

    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Override
    public void declareContentSlot(Long topicId, String entityType, Long entityId) {
        if (topicId == null) {
            return;
        }
if (!RoleEntityType.isContentOwner(entityType)) {
            // 建题不该因为槽没落上而失败(槽是新轴,建题是老链路)——但必须留声。
            // 静默 return 会让槽表恒空,而 P0-50 的范围闸读的就是它 ⇒ 计酬档整个用不了且零告警。
            log.error("[P0-35] 内容槽主体类型非法,topicId={} entityType={} —— 只允许 PLATFORM/MEMBER/CLUB;"
                    + "商家只能承接供给,不能成为内容或票款归属主体", topicId, entityType);
            return;
        }
        if (!RoleEntityType.entityIdConsistent(entityType, entityId)) {
            // PLATFORM 带 entity_id / 其余不带,都是自相矛盾的行。库有 ck_role_entity_id 兜底,
            // 这里先拦是为了给出能定位的日志,而不是让调用方吃一个 3819。
            log.error("[P0-35] 内容槽 entityId 与 entityType 不匹配,topicId={} entityType={} entityId={} "
                    + "—— PLATFORM 必须不带 entityId,其余必须带", topicId, entityType, entityId);
            return;
        }

        ProductRole role = new ProductRole();
        role.setTopicId(topicId);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(entityType);
        role.setEntityId(entityId);
        role.setStatus(1);
        ProductSlot.requireValid(role.getSlot());
        try {
            productRoleMapper.insertProductRole(role);
        } catch (Exception e) {
            // 撞唯一键 = 这个主题已经有生效内容槽了(重复建题/重试)。不该把建题炸掉。
            // ⚠️ 但也不能吞掉就算了 —— 槽表是票款归属的真源(D8),这里静默失败 = 归属说不清。
            log.error("[P0-35] 内容槽落库失败,topicId={} entityType={} entityId={};"
                    + "该主题的票款归属与 REVSHARE 资格将无从判定", topicId, entityType, entityId, e);
        }
    }

    @Override
    public ProductRole activeContentSlot(Long topicId) {
        if (topicId == null) {
            return null;
        }
        List<ProductRole> rows = productRoleMapper.selectActiveByTopicAndSlot(topicId, ProductSlot.CONTENT);
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public ProductRole activeOrganizerSlot(Long topicId) {
        if (topicId == null) {
            return null;
        }
        List<ProductRole> rows = productRoleMapper.selectActiveByTopicAndSlot(topicId, ProductSlot.ORGANIZER);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("SALE_ORGANIZER_SINGLETON_BROKEN");
        }
        return rows.get(0);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean declareRequiredOrganizerSlot(Long topicId, Long operatorId) {
        if (topicId == null || operatorId == null || operatorId.longValue() <= 0L) {
            throw new IllegalArgumentException("organizer actor 声明参数不完整");
        }
        if (!topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            throw new IllegalArgumentException("主题不存在或当前账号无操作范围");
        }
        CmsTopic topic = productRoleMapper.selectTopicForOrganizerUpdate(topicId);
        if (topic == null) {
            throw new IllegalArgumentException("主题不存在或已删除");
        }
        TopicLifecycleState lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        if (lifecycle.isSaleStarted()) {
            throw new IllegalStateException("SALE_ORGANIZER_IMMUTABLE");
        }
        if (!Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) {
            throw new IllegalArgumentException("SALE_ORGANIZER_ONLY_FREE_EXPLORE");
        }

        ProductRole before = activeOrganizerSlot(topicId);
        if (before != null && RoleEntityType.PLATFORM.equals(before.getEntityType())
                && before.getEntityId() == null && before.getDeclaredBy() != null) {
            log.info("[4-3] ORGANIZER actor 幂等声明 topicId={} roleId={} originalOperatorId={} retryOperatorId={}",
                    topicId, before.getId(), before.getDeclaredBy(), operatorId);
            return true;
        }
        int deactivated = productRoleMapper.deactivateByTopicAndSlot(topicId, ProductSlot.ORGANIZER);
        int expected = before == null ? 0 : 1;
        if (deactivated != expected) {
            throw new IllegalStateException("SALE_ORGANIZER_CONCURRENT_CHANGE");
        }

        ProductRole write = new ProductRole();
        write.setTopicId(topicId);
        write.setSlot(ProductSlot.ORGANIZER);
        write.setEntityType(RoleEntityType.PLATFORM);
        write.setEntityId(null);
        write.setStatus(1);
        write.setDeclaredBy(operatorId);
        ProductSlot.requireValid(write.getSlot());
        if (productRoleMapper.insertProductRole(write) != 1) {
            throw new IllegalStateException("SALE_ORGANIZER_INSERT_FAILED");
        }
        ProductRole readback = activeOrganizerSlot(topicId);
        if (readback == null || readback.getId() == null
                || !RoleEntityType.PLATFORM.equals(readback.getEntityType())
                || readback.getEntityId() != null
                || !operatorId.equals(readback.getDeclaredBy())) {
            throw new IllegalStateException("SALE_ORGANIZER_READBACK_MISMATCH");
        }
        log.warn("[4-3] ORGANIZER actor 改判 topicId={} {}({}) -> {}({}),operatorId={}", topicId,
                before == null ? "无" : before.getEntityType(),
                before == null ? "-" : before.getEntityId(), RoleEntityType.PLATFORM, null, operatorId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductRole declareDistributionSlotForSale(Long topicId, Long clubId, Long operatorId) {
        if (topicId == null || clubId == null || clubId <= 0L || operatorId == null || operatorId <= 0L) {
            throw new IllegalArgumentException("SALE_DISTRIBUTION_ARGUMENT_REQUIRED");
        }
        List<ProductRole> existing = productRoleMapper.selectActiveByTopicAndSlot(
                topicId, ProductSlot.DISTRIBUTION);
        if (existing != null && !existing.isEmpty()) {
            if (existing.size() != 1 || !isAuditedDistributionFor(existing.get(0), topicId, clubId)) {
                throw new IllegalStateException("SALE_DISTRIBUTION_CONFLICT");
            }
            return existing.get(0);
        }

        ProductRole write = new ProductRole();
        write.setTopicId(topicId);
        write.setSlot(ProductSlot.DISTRIBUTION);
        write.setEntityType(RoleEntityType.CLUB);
        write.setEntityId(clubId);
        write.setStatus(1);
        write.setDeclaredBy(operatorId);
        ProductSlot.requireValid(write.getSlot());
        if (productRoleMapper.insertProductRole(write) != 1) {
            throw new IllegalStateException("SALE_DISTRIBUTION_INSERT_FAILED");
        }
        List<ProductRole> readback = productRoleMapper.selectActiveByTopicAndSlot(
                topicId, ProductSlot.DISTRIBUTION);
        if (readback == null || readback.size() != 1
                || !isAuditedDistributionFor(readback.get(0), topicId, clubId)
                || !operatorId.equals(readback.get(0).getDeclaredBy())) {
            throw new IllegalStateException("SALE_DISTRIBUTION_READBACK_MISMATCH");
        }
        log.info("[E2] 开售冻结 DISTRIBUTION topicId={} clubId={} roleId={} declaredBy={}",
                topicId, clubId, readback.get(0).getId(), readback.get(0).getDeclaredBy());
        return readback.get(0);
    }

    private static boolean isAuditedDistributionFor(ProductRole role, Long topicId, Long clubId) {
        return role != null && topicId.equals(role.getTopicId())
                && ProductSlot.DISTRIBUTION.equals(role.getSlot())
                && RoleEntityType.CLUB.equals(role.getEntityType())
                && clubId.equals(role.getEntityId()) && Integer.valueOf(1).equals(role.getStatus())
                && role.getId() != null && role.getDeclaredBy() != null && role.getDeclaredBy() > 0L;
    }

    @Override
    public Map<Long, ProductRole> activeContentSlots(Collection<Long> topicIds) {
        if (topicIds == null || topicIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        for (Long topicId : topicIds) {
            if (topicId != null) {
                ids.add(topicId);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<ProductRole> rows = productRoleMapper.selectActiveContentByTopicIds(new ArrayList<Long>(ids));
        Map<Long, ProductRole> result = new LinkedHashMap<Long, ProductRole>();
        if (rows == null) {
            return result;
        }
        for (ProductRole role : rows) {
            if (role == null || role.getTopicId() == null
                    || !ProductSlot.CONTENT.equals(role.getSlot())
                    || !Integer.valueOf(1).equals(role.getStatus())) {
                continue;
            }
            result.put(role.getTopicId(), role);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reassignContentSlot(Long topicId, String entityType, Long entityId) {
        if (topicId == null || !RoleEntityType.isContentOwner(entityType)
                || !RoleEntityType.entityIdConsistent(entityType, entityId)) {
            return false;
        }
        // 先失效再插:两步必须同事务。否则失效成功、插入失败 = 这个主题没有内容槽了，
        // 票款归属凭空消失，而 isPlatformOwned 会 fail-closed 把它当第三方。
        ProductRole before = activeContentSlot(topicId);
        int deactivated = productRoleMapper.deactivateByTopicAndSlot(topicId, ProductSlot.CONTENT);
        int expectedDeactivated = before == null ? 0 : 1;
        if (deactivated != expectedDeactivated) {
            throw new IllegalStateException("内容主体状态已变化，请刷新后重试");
        }

        ProductRole role = new ProductRole();
        role.setTopicId(topicId);
        role.setSlot(ProductSlot.CONTENT);
        role.setEntityType(entityType);
        role.setEntityId(entityId);
        role.setStatus(1);
        ProductSlot.requireValid(role.getSlot());
        if (productRoleMapper.insertProductRole(role) != 1) {
            throw new IllegalStateException("内容主体保存失败，请刷新后重试");
        }

        // 改的是钱的归属(D8:票款归内容槽持有者),必须留一条能追溯的记录。
        // @Log 记的是「谁调了这个接口」,这条记的是「值从什么变成了什么」—— 两者都要。
        log.warn("[P0-35] 内容槽改判 topicId={} {}({}) -> {}({})", topicId,
                before == null ? "无" : before.getEntityType(),
                before == null ? "-" : before.getEntityId(), entityType, entityId);
        return true;
    }

    @Override
    public boolean isPlatformOwned(Long topicId) {
        ProductRole content = activeContentSlot(topicId);
        // fail-closed:没有内容槽 ⇒ 不是自营。存量主题一行都没有(迁移刻意不回填),
        // 于是它们的 REVSHARE 全部被拒 —— 这是**故意的**,不是遗漏。
        return content != null && RoleEntityType.PLATFORM.equals(content.getEntityType());
    }
}
