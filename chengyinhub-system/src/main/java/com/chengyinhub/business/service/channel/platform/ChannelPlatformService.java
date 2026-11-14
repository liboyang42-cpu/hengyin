package com.chengyinhub.business.service.channel.platform;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.mapper.channel.platform.ChannelPlatformMapper;
import com.chengyinhub.business.service.channel.foundation.ChannelDefinition;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.GuildStanding;
import com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.Overwrite;

import static com.chengyinhub.business.service.channel.platform.ChannelCapability.*;
import static com.chengyinhub.business.service.channel.platform.ChannelGuildAccess.toLong;
import static com.chengyinhub.business.service.channel.platform.ChannelPermissionCalculator.has;

/**
 * 频道目录与管理（App NativeChannelManagement* 合同）。写操作：先实时校验空间关系与能力，
 * 再按 request_id 原回执一次落库；元数据版本(meta_version)与权限纪元(perm_version)分开 CAS。
 */
@Service
public class ChannelPlatformService
{
    static final int PURGE_DAYS = 7;
    static final int MEMBER_COUNT_CAP = 50;
    static final String DEFAULT_CATEGORY = "文字频道";
    static final String DEFAULT_CHANNEL = "综合";

    @Autowired
    private ChannelPlatformMapper mapper;
    @Autowired
    private ChannelGuildAccess access;
    @Autowired(required = false)
    private com.chengyinhub.business.mapper.channel.platform.ChannelGovernanceMapper governance;
    @Autowired(required = false)
    private ChannelGovernanceService.MuteBridge muteBridge;

    public ChannelPlatformService() { }

    ChannelPlatformService(ChannelPlatformMapper mapper, ChannelGuildAccess access)
    {
        this.mapper = mapper;
        this.access = access;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> directory(long viewer, int guildKind, long guildId, boolean withUnread)
    {
        GuildStanding standing = access.requireMember(guildKind, guildId, viewer);
        List<ChannelRow> rows = mapper.selectGuildChannels(guildKind, guildId, false);
        if (rows.isEmpty())
        {
            provisionDefaults(viewer, guildKind, guildId);
            rows = mapper.selectGuildChannels(guildKind, guildId, false);
        }
        Map<Long, List<Overwrite>> overwrites = overwritesOf(rows);
        ChannelGuildAccess.RoleProfile profile = access.roles(guildKind, guildId, viewer);
        int memberCount = mapper.countGuildMembers(guildKind, guildId);
        Map<Long, Long> unread = new HashMap<>();
        Map<Long, ChannelRow> categories = new LinkedHashMap<>();
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<Map<String, Object>> ungrouped = new ArrayList<>();
        List<ChannelRow> visible = new ArrayList<>();
        Map<Long, Long> bitsById = new HashMap<>();
        for (ChannelRow row : rows)
        {
            if (row.isCategory())
            {
                if (row.getStatus() == 0) categories.put(row.getId(), row);
                continue;
            }
            long bits = ChannelPermissionCalculator.effective(standing, profile.permissions(), profile.roleIds(),
                    overwrites.getOrDefault(row.getId(), List.of()), row.getStatus());
            if (!has(bits, VIEW_CHANNEL)) continue;
            visible.add(row);
            bitsById.put(row.getId(), bits);
        }
        if (withUnread && !visible.isEmpty())
        {
            List<Long> ids = new ArrayList<>();
            for (ChannelRow r : visible) ids.add(r.getId());
            for (Map<String, Object> u : mapper.selectUnread(viewer, ids))
                unread.put(toLong(u.get("conversationId")), toLong(u.get("unread")));
        }
        for (ChannelRow row : visible)
        {
            Map<String, Object> view = channelView(row, bitsById.get(row.getId()), memberCount, true);
            if (withUnread) view.put("unread_count", unread.getOrDefault(row.getId(), 0L));
            if (row.getParentId() != 0 && categories.containsKey(row.getParentId()))
                grouped.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(view);
            else
                ungrouped.add(view);
        }
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<Long, ChannelRow> c : categories.entrySet())
        {
            List<Map<String, Object>> channels = grouped.get(c.getKey());
            if (channels == null) continue;
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("id", String.valueOf(c.getKey()));
            category.put("name", c.getValue().getName());
            category.put("kind", 0);
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("category", category);
            group.put("channels", channels);
            groups.add(group);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("guild_kind", guildKind);
        data.put("guild_id", String.valueOf(guildId));
        data.put("groups", groups);
        data.put("ungrouped", ungrouped);
        return data;
    }

    public List<Map<String, Object>> manageInventory(long viewer, int guildKind, long guildId)
    {
        requireGuildBit(viewer, guildKind, guildId, MANAGE_CHANNEL);
        List<Map<String, Object>> list = new ArrayList<>();
        Date now = new Date();
        for (ChannelRow row : mapper.selectGuildChannels(guildKind, guildId, true))
        {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", String.valueOf(row.getId()));
            view.put("guild_kind", row.getGuildKind());
            view.put("guild_id", String.valueOf(row.getGuildId()));
            view.put("parent_id", String.valueOf(row.getParentId()));
            view.put("kind", row.getKind());
            view.put("name", row.getName());
            view.put("position", row.getPosition());
            view.put("is_default", row.getIsDefault());
            view.put("status", row.getStatus());
            view.put("version", String.valueOf(row.getMetaVersion()));
            view.put("perm_version", String.valueOf(row.getPermVersion()));
            view.put("archive_reason", row.getArchiveReason());
            view.put("purge_at", format(row.getPurgeAt()));
            view.put("recoverable", row.getStatus() == 3 && row.getPurgeAt() != null && row.getPurgeAt().after(now));
            list.add(view);
        }
        return list;
    }

    public Map<String, Object> detail(long viewer, long channelId)
    {
        ChannelRow row = visibleChannel(viewer, channelId);
        long bits = access.effective(row, viewer);
        Map<String, Object> view = channelView(row, bits, mapper.countGuildMembers(row.getGuildKind(), row.getGuildId()), true);
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("id", String.valueOf(row.getId()));
        container.put("type", ChannelDefinition.REQUIRED_CONVERSATION_TYPE);
        view.put("message_container", container);
        if (governance != null) view.put("my_state", ChannelGovernanceService.myState(governance, muteBridge, viewer, channelId));
        return view;
    }

    public Map<String, Object> permissions(long viewer, long channelId)
    {
        ChannelRow row = existing(channelId);
        requireGuildBit(viewer, row.getGuildKind(), row.getGuildId(), MANAGE_ROLE);
        List<Map<String, Object>> overrides = new ArrayList<>();
        for (Map<String, Object> o : mapper.selectOverwrites(channelId)) overrides.add(overwriteView(o));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", String.valueOf(channelId));
        data.put("version", String.valueOf(row.getMetaVersion()));
        data.put("perm_version", String.valueOf(row.getPermVersion()));
        data.put("overrides", overrides);
        return data;
    }

    public Map<String, Object> effective(long viewer, long channelId, long memberId)
    {
        ChannelRow row = existing(channelId);
        if (memberId != viewer) requireGuildBit(viewer, row.getGuildKind(), row.getGuildId(), MANAGE_ROLE);
        else access.requireMember(row.getGuildKind(), row.getGuildId(), viewer);
        long bits = access.effective(row, memberId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("channel_id", String.valueOf(channelId));
        data.put("member_id", String.valueOf(memberId));
        data.put("permissions", hex(bits));
        data.put("permissions_list", names(bits));
        data.put("version", String.valueOf(row.getMetaVersion()));
        return data;
    }

    public Map<String, Object> opLog(long viewer, long channelId, Long beforeId, int limit)
    {
        ChannelRow row = existing(channelId);
        requireGuildBit(viewer, row.getGuildKind(), row.getGuildId(), VIEW_AUDIT);
        int size = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> rows = mapper.selectOpLog(channelId, beforeId, size + 1);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows)
        {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", String.valueOf(toLong(r.get("id"))));
            v.put("channel_id", String.valueOf(channelId));
            v.put("action", r.get("action"));
            v.put("from_status", r.get("fromStatus"));
            v.put("to_status", r.get("toStatus"));
            v.put("reason", r.get("reason"));
            v.put("operator_id", r.get("operatorId") == null ? null : String.valueOf(toLong(r.get("operatorId"))));
            v.put("create_time", format(r.get("createTime")));
            list.add(v);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", more);
        data.put("next_cursor", more ? list.get(list.size() - 1).get("id") : null);
        return data;
    }

    public Map<String, Object> members(long viewer, long channelId, String cursor, int limit)
    {
        ChannelRow row = visibleChannel(viewer, channelId);
        long after = 0;
        if (cursor != null)
        {
            if (!cursor.matches("[1-9][0-9]{0,18}")) throw ChannelRejection.badField("cursor 不合法");
            after = Long.parseLong(cursor);
        }
        int size = Math.max(1, Math.min(limit, 50));
        List<Overwrite> overwrites = ChannelGuildAccess.overwrites(mapper.selectOverwrites(channelId));
        List<Map<String, Object>> list = new ArrayList<>();
        boolean more = false;
        // 私密频道按成员实时有效权限过滤；一次最多扫 10 页，避免无界扫描。
        for (int round = 0; round < 10 && list.size() <= size; round++)
        {
            List<Map<String, Object>> page = mapper.selectGuildMembers(row.getGuildKind(), row.getGuildId(), after, 100);
            for (Map<String, Object> m : page)
            {
                long memberId = toLong(m.get("memberId"));
                after = memberId;
                GuildStanding standing = access.standing(row.getGuildKind(), row.getGuildId(), memberId);
                if (!has(access.effective(row, standing, memberId, overwrites), VIEW_CHANNEL)) continue;
                if (list.size() == size) { more = true; break; }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("member_id", String.valueOf(memberId));
                v.put("nickname", m.get("nickname") == null ? String.valueOf(memberId) : m.get("nickname"));
                if (governance != null)
                    v.put("role_ids", governance.selectMemberRoleIds(row.getGuildKind(), row.getGuildId(), memberId).stream().map(String::valueOf).toList());
                list.add(v);
            }
            if (more || page.size() < 100) break;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", more ? 1 : 0);
        data.put("next_cursor", more ? list.get(list.size() - 1).get("member_id") : null);
        return data;
    }

    public Map<String, Object> roles(long viewer, int guildKind, long guildId, boolean includeDisabled)
    {
        access.requireMember(guildKind, guildId, viewer);
        int highest = 0;
        for (Map<String, Object> r : mapper.selectMemberRoles(guildKind, guildId, viewer))
            highest = Math.max(highest, (int) toLong(r.get("position")));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : mapper.selectGuildRoles(guildKind, guildId, includeDisabled))
        {
            Map<String, Object> v = ChannelGovernanceService.roleView(r);
            v.put("is_system", toLong(r.get("isSystem")) == 1);
            v.put("is_everyone", false);
            list.add(v);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("list", list);
        data.put("has_more", false);
        data.put("my_highest_position", highest);
        return data;
    }

    /** 回执只读：本人原请求；当前已看不到频道时只说已落地、不回原结果。 */
    public Map<String, Object> receipt(long viewer, String family, String requestId)
    {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.:-]{8,64}")) throw ChannelRejection.badField("request_id 不合法");
        Map<String, Object> stored = mapper.selectReceipt(viewer, family, requestId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        if (stored == null || stored.get("response") == null)
        {
            data.put("state", "NOT_FOUND");
            data.put("outcome_known", false);
            data.put("retry_with_original_request", true);
            return data;
        }
        data.put("state", "APPLIED");
        data.put("outcome_known", true);
        long channelId = toLong(stored.get("channelId"));
        boolean visible;
        if (channelId == 0) visible = true; // 空间级治理写（角色）：本人原回执本人可读
        else try
        {
            ChannelRow row = mapper.selectChannel(channelId);
            visible = row != null && access.standing(row.getGuildKind(), row.getGuildId(), viewer) != GuildStanding.NONE;
        }
        catch (ChannelRejection gone)
        {
            visible = false;
        }
        if (visible) data.put("result", ChannelReceipts.decode(stored.get("response").toString()));
        else data.put("result_redacted", true);
        return data;
    }

    // ------------------------------------------------------------------ writes

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(long viewer, int guildKind, long guildId, Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of("name", "topic", "kind", "parent_id", "visibility", "slowmode_sec"), true);
        String name = name(c.fields.get("name"));
        String topic = topic(c.fields, true);
        int kind = intField(c.fields, "kind", 0, 2);
        long parentId = idField(c.fields.get("parent_id"), true);
        int visibility = intField(c.fields, "visibility", 1, 2);
        int slowmode = intField(c.fields, "slowmode_sec", 0, 21600);
        if (kind == 0 && parentId != 0) throw ChannelRejection.badField("分类不能再放进分类");
        String fp = ChannelReceipts.fingerprint("CREATE", guildKind + ":" + guildId, c.fields);
        requireGuildBit(viewer, guildKind, guildId, MANAGE_CHANNEL);
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.META, c.requestId, fp, "CREATE", 0L, () -> {
            requireGuildBit(viewer, guildKind, guildId, MANAGE_CHANNEL);
            if (parentId != 0)
            {
                ChannelRow parent = mapper.lockChannel(parentId);
                if (parent == null || !parent.isCategory() || parent.getStatus() != 0
                        || parent.getGuildKind() != guildKind || parent.getGuildId() != guildId)
                    throw ChannelRejection.badField("父分类不存在");
            }
            ChannelRow row = insert(viewer, guildKind, guildId, parentId, kind, name, topic, visibility, slowmode, false,
                    c.reason, c.requestId);
            return applied(c.requestId, row, 0, null);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> edit(long viewer, long channelId, Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of("name", "topic", "visibility", "slowmode_sec"), false);
        if (c.fields.isEmpty()) throw ChannelRejection.badField("没有要修改的字段");
        long expected = c.expectedVersion();
        String name = c.fields.containsKey("name") ? name(c.fields.get("name")) : null;
        boolean setTopic = c.fields.containsKey("topic");
        String topic = setTopic ? topic(c.fields, false) : null;
        Integer visibility = c.fields.containsKey("visibility") ? intField(c.fields, "visibility", 1, 2) : null;
        Integer slowmode = c.fields.containsKey("slowmode_sec") ? intField(c.fields, "slowmode_sec", 0, 21600) : null;
        String fp = ChannelReceipts.fingerprint("EDIT", channelId, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.META, c.requestId, fp, "EDIT", channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_CHANNEL);
            if (row.getStatus() != 0) throw new ChannelRejection(409, "CHANNEL_STATE_CONFLICT", "频道当前状态不可编辑");
            if (row.getMetaVersion() != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
            boolean unchanged = (name == null || name.equals(row.getName()))
                    && (!setTopic || java.util.Objects.equals(topic, row.getTopic()))
                    && (visibility == null || visibility.equals(row.getVisibility()))
                    && (slowmode == null || slowmode.equals(row.getSlowmodeSec()));
            if (!unchanged)
            {
                try
                {
                    if (mapper.updateMetadata(channelId, expected, name, topic, setTopic, visibility, slowmode) != 1)
                        throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
                }
                catch (RuntimeException dup)
                {
                    if (!ChannelRejection.isDuplicateKey(dup)) throw dup;
                    throw new ChannelRejection(409, "CHANNEL_NAME_TAKEN", "同一分类下已有同名频道");
                }
                log(channelId, "EDIT", row.getStatus(), row.getStatus(), c.reason, viewer, c.requestId);
                row = mapper.selectChannel(channelId);
            }
            Map<String, Object> result = applied(c.requestId, row, row.getStatus(), null);
            if (unchanged) result.put("unchanged", true);
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(long viewer, long channelId, Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of("archived"), false);
        int archived = intField(c.fields, "archived", 0, 1);
        long expected = c.expectedVersion();
        String op = archived == 1 ? "ARCHIVE" : "UNARCHIVE";
        String fp = ChannelReceipts.fingerprint(op, channelId, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.META, c.requestId, fp, op, channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_CHANNEL);
            if (row.getMetaVersion() != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
            int from = archived == 1 ? 0 : 1;
            if (row.getStatus() != from || (archived == 0 && !"manual".equals(row.getArchiveReason())))
                throw new ChannelRejection(409, "CHANNEL_STATE_CONFLICT", "频道当前状态不能" + (archived == 1 ? "归档" : "取消归档"));
            transition(row, expected, from, 1 - from, archived == 1 ? "manual" : null, null, null);
            log(channelId, op, from, 1 - from, c.reason, viewer, c.requestId);
            return applied(c.requestId, mapper.selectChannel(channelId), 1 - from, null);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(long viewer, long channelId, Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of(), false);
        long expected = c.expectedVersion();
        String fp = ChannelReceipts.fingerprint("DELETE", channelId, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.META, c.requestId, fp, "DELETE", channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_CHANNEL);
            if (row.getMetaVersion() != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
            if (row.getIsDefault() == 1 || row.getKind() == 2 || row.getStatus() > 1)
                throw new ChannelRejection(409, "CHANNEL_STATE_CONFLICT", "默认/公告频道不能删除");
            if (row.isCategory() && mapper.countLiveChildren(channelId) > 0)
                throw new ChannelRejection(409, "CHANNEL_STATE_CONFLICT", "分类下还有频道");
            Date purgeAt = new Date(System.currentTimeMillis() + PURGE_DAYS * 86_400_000L);
            transition(row, expected, row.getStatus(), 3, row.getArchiveReason(), purgeAt, c.reason);
            log(channelId, "DELETE", row.getStatus(), 3, c.reason, viewer, c.requestId);
            return applied(c.requestId, mapper.selectChannel(channelId), 3, purgeAt);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> restore(long viewer, long channelId, Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of(), false);
        long expected = c.expectedVersion();
        String fp = ChannelReceipts.fingerprint("RESTORE", channelId, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.META, c.requestId, fp, "RESTORE", channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_CHANNEL);
            if (row.getMetaVersion() != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
            if (row.getStatus() != 3 || row.getPurgeAt() == null || !row.getPurgeAt().after(new Date()))
                throw new ChannelRejection(409, "CHANNEL_NOT_RECOVERABLE", "频道已超过恢复期");
            // 恢复后先归档（manual），由管理者确认后再取消归档。
            transition(row, expected, 3, 1, "manual", null, null);
            log(channelId, "RESTORE", 3, 1, c.reason, viewer, c.requestId);
            return applied(c.requestId, mapper.selectChannel(channelId), 1, null);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> putOverwrite(long viewer, long channelId, int targetType, String targetId,
            Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of("allow_bits", "deny_bits", "note"), false);
        long target = target(targetType, targetId);
        Long allow = ChannelCapability.parseHex(stringField(c.fields, "allow_bits"));
        Long deny = ChannelCapability.parseHex(stringField(c.fields, "deny_bits"));
        if (allow == null || deny == null || (allow & deny) != 0) throw ChannelRejection.badField("权限位不合法");
        if ((allow & guildMask()) != 0)
            throw new ChannelRejection(400, "GUILD_PERMISSION_IN_OVERWRITE", "空间级权限不能放进频道覆盖");
        Object rawNote = c.fields.get("note");
        if (!c.fields.containsKey("note") || (rawNote != null && !(rawNote instanceof String)))
            throw ChannelRejection.badField("note 不合法");
        String note = (String) rawNote;
        if (note != null && note.length() > 120) throw ChannelRejection.badField("备注过长");
        long expected = c.expectedVersion();
        long expectedPerm = c.expectedPermVersion();
        String fp = ChannelReceipts.fingerprint("PERMISSION_PUT", channelId + ":" + targetType + ":" + target, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.GOV, c.requestId, fp, "PERMISSION_PUT", channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_ROLE);
            requireTarget(row, targetType, target);
            GuildStanding standing = access.standing(row.getGuildKind(), row.getGuildId(), viewer);
            long own = access.effective(row, standing, viewer, ChannelGuildAccess.overwrites(mapper.selectOverwrites(channelId)));
            if (standing != GuildStanding.OWNER && (allow & ~own) != 0)
                throw new ChannelRejection(403, "CHANNEL_FORBIDDEN", "不能授予自己没有的权限");
            casPermissions(row, expected, expectedPerm);
            mapper.upsertOverwrite(channelId, targetType, target, allow, deny, note, viewer);
            long auditId = log(channelId, "PERMISSION_PUT", row.getStatus(), row.getStatus(), c.reason, viewer, c.requestId);
            Map<String, Object> result = permissionApplied(c.requestId, channelId, targetType, target, expected, expectedPerm, auditId);
            result.put("allow_bits", hex(allow));
            result.put("deny_bits", hex(deny));
            result.put("note", note);
            result.put("inherited", false);
            return result;
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteOverwrite(long viewer, long channelId, int targetType, String targetId,
            Map<String, Object> body, String headerReason)
    {
        Command c = Command.of(body, headerReason, Set.of(), false);
        long target = target(targetType, targetId);
        long expected = c.expectedVersion();
        long expectedPerm = c.expectedPermVersion();
        String fp = ChannelReceipts.fingerprint("PERMISSION_DELETE", channelId + ":" + targetType + ":" + target, withVersion(c));
        return ChannelReceipts.once(mapper, viewer, ChannelReceipts.GOV, c.requestId, fp, "PERMISSION_DELETE", channelId, () -> {
            ChannelRow row = lockManaged(viewer, channelId, MANAGE_ROLE);
            casPermissions(row, expected, expectedPerm);
            if (mapper.deleteOverwrite(channelId, targetType, target) != 1)
                throw new ChannelRejection(404, "OVERWRITE_NOT_FOUND", "该覆盖不存在");
            long auditId = log(channelId, "PERMISSION_DELETE", row.getStatus(), row.getStatus(), c.reason, viewer, c.requestId);
            Map<String, Object> result = permissionApplied(c.requestId, channelId, targetType, target, expected, expectedPerm, auditId);
            result.put("deleted", true);
            result.put("inherited", true);
            return result;
        });
    }

    // ------------------------------------------------------------------ helpers

    /** 空间首个读者进入时补默认分类 + 默认文字频道；并发由 uk_channel_default 收口。 */
    void provisionDefaults(long viewer, int guildKind, long guildId)
    {
        try
        {
            ChannelRow category = insert(viewer, guildKind, guildId, 0, 0, DEFAULT_CATEGORY, null, 1, 0, false,
                    "provision", null);
            insert(viewer, guildKind, guildId, category.getId(), 1, DEFAULT_CHANNEL, null, 1, 0, true, "provision", null);
        }
        catch (RuntimeException race)
        {
            if (!(race instanceof ChannelRejection) && !ChannelRejection.isDuplicateKey(race)) throw race;
            // 另一请求已建好默认频道；本事务回滚由外层负责，这里只读回。
            throw new ChannelRejection(409, "CHANNEL_PROVISION_RACE", "频道正在初始化，请重试");
        }
    }

    private ChannelRow insert(long viewer, int guildKind, long guildId, long parentId, int kind, String name, String topic,
            int visibility, int slowmode, boolean isDefault, String reason, String requestId)
    {
        Map<String, Object> conversation = new HashMap<>();
        conversation.put("bizKey", "channel_" + UUID.randomUUID().toString().replace("-", ""));
        mapper.insertConversation(conversation);
        long id = toLong(conversation.get("id"));
        ChannelRow row = new ChannelRow();
        row.setId(id);
        row.setGuildKind(guildKind);
        row.setGuildId(guildId);
        row.setParentId(parentId);
        row.setKind(kind);
        row.setName(name);
        row.setTopic(topic);
        row.setPosition(isDefault ? 0 : Math.max(1, mapper.nextPosition(guildKind, guildId, parentId)));
        row.setIsDefault(isDefault ? 1 : 0);
        row.setVisibility(visibility);
        row.setSlowmodeSec(slowmode);
        try
        {
            mapper.insertChannel(row);
        }
        catch (RuntimeException dup)
        {
            if (isDefault || !ChannelRejection.isDuplicateKey(dup)) throw dup;
            throw new ChannelRejection(409, "CHANNEL_NAME_TAKEN", "同一分类下已有同名频道");
        }
        mapper.ensureConversationMember(id, viewer);
        log(id, "CREATE", null, 0, reason, viewer, requestId);
        return mapper.selectChannel(id);
    }

    private void transition(ChannelRow row, long expected, int from, int to, String archiveReason, Date purgeAt, String deleteReason)
    {
        if (mapper.updateLifecycle(row.getId(), expected, from, to, archiveReason, purgeAt, deleteReason) != 1)
            throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
        // 删除后会话关闭；恢复/归档保留只读历史。
        mapper.setConversationStatus(row.getId(), to == 3 ? 1 : 0);
    }

    private void casPermissions(ChannelRow row, long expected, long expectedPerm)
    {
        if (row.getMetaVersion() != expected) throw new ChannelRejection(409, "VERSION_CONFLICT", "频道已被他人修改");
        if (row.getPermVersion() != expectedPerm) throw new ChannelRejection(409, "PERM_VERSION_CONFLICT", "频道权限已被他人修改");
        if (mapper.bumpPermissionVersion(row.getId(), expected, expectedPerm) != 1)
            throw new ChannelRejection(409, "PERM_VERSION_CONFLICT", "频道权限已被他人修改");
    }

    private long log(long channelId, String action, Integer from, Integer to, String reason, long operator, String requestId)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("channelId", channelId);
        row.put("action", action);
        row.put("fromStatus", from);
        row.put("toStatus", to);
        row.put("reason", reason == null || reason.isBlank() ? action : reason);
        row.put("operatorId", operator);
        row.put("requestId", requestId);
        mapper.insertOpLog(row);
        return toLong(row.get("id"));
    }

    private ChannelRow existing(long channelId)
    {
        ChannelRow row = channelId <= 0 ? null : mapper.selectChannel(channelId);
        if (row == null || row.getStatus() == 4) throw ChannelRejection.notFound();
        ChannelGuildAccess.requireSupportedKind(row.getGuildKind());
        return row;
    }

    /** 非成员 403；成员看不到（私密/封禁/删除）一律 404，不泄露存在性。 */
    ChannelRow visibleChannel(long viewer, long channelId)
    {
        ChannelRow row = existing(channelId);
        access.requireMember(row.getGuildKind(), row.getGuildId(), viewer);
        if (row.isCategory() || row.getStatus() > 1 || !has(access.effective(row, viewer), VIEW_CHANNEL))
            throw ChannelRejection.notFound();
        return row;
    }

    private ChannelRow lockManaged(long viewer, long channelId, ChannelCapability needed)
    {
        ChannelRow row = channelId <= 0 ? null : mapper.lockChannel(channelId);
        if (row == null || row.getStatus() == 4) throw ChannelRejection.notFound();
        requireGuildBit(viewer, row.getGuildKind(), row.getGuildId(), needed);
        return row;
    }

    private void requireGuildBit(long viewer, int guildKind, long guildId, ChannelCapability needed)
    {
        long bits = access.guildBits(guildKind, guildId, viewer);
        if (bits == 0) throw new ChannelRejection(403, "GUILD_FORBIDDEN", "加入后才能查看频道");
        if (!has(bits, needed) && !has(bits, ADMINISTRATOR)) throw ChannelRejection.forbidden();
    }

    private void requireTarget(ChannelRow row, int targetType, long target)
    {
        if (targetType == 2 && mapper.countGuildRole(row.getGuildKind(), row.getGuildId(), target) == 0)
            throw new ChannelRejection(404, "ROLE_NOT_FOUND", "角色不存在");
    }

    private Map<Long, List<Overwrite>> overwritesOf(List<ChannelRow> rows)
    {
        Map<Long, List<Overwrite>> map = new HashMap<>();
        List<Long> ids = new ArrayList<>();
        for (ChannelRow r : rows) if (!r.isCategory()) ids.add(r.getId());
        if (ids.isEmpty()) return map;
        for (Map<String, Object> o : mapper.selectOverwritesOf(ids))
            map.computeIfAbsent(toLong(o.get("channelId")), k -> new ArrayList<>())
                    .addAll(ChannelGuildAccess.overwrites(List.of(o)));
        return map;
    }

    static Map<String, Object> channelView(ChannelRow row, long bits, int memberCount, boolean detail)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", String.valueOf(row.getId()));
        v.put("guild_kind", row.getGuildKind());
        v.put("guild_id", String.valueOf(row.getGuildId()));
        v.put("parent_id", String.valueOf(row.getParentId()));
        v.put("kind", row.getKind());
        v.put("name", row.getName());
        v.put("topic", row.getTopic());
        v.put("position", row.getPosition());
        v.put("is_default", row.getIsDefault());
        v.put("status", row.getStatus());
        v.put("version", String.valueOf(row.getMetaVersion()));
        if (detail) v.put("perm_version", String.valueOf(row.getPermVersion()));
        v.put("visibility", row.getVisibility());
        v.put("slowmode_sec", row.getSlowmodeSec());
        v.put("archive_reason", row.getArchiveReason());
        v.put("purge_at", format(row.getPurgeAt()));
        v.put("recoverable", false);
        v.put("my_permissions_list", names(bits));
        v.put("member_count", Math.min(memberCount, MEMBER_COUNT_CAP));
        v.put("member_count_capped", memberCount > MEMBER_COUNT_CAP ? 1 : 0);
        return v;
    }

    private static Map<String, Object> applied(String requestId, ChannelRow row, int status, Date purgeAt)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("request_id", requestId);
        r.put("state", "APPLIED");
        r.put("id", String.valueOf(row.getId()));
        r.put("guild_kind", row.getGuildKind());
        r.put("guild_id", String.valueOf(row.getGuildId()));
        r.put("version", String.valueOf(row.getMetaVersion()));
        r.put("status", status);
        r.put("name", row.getName());
        r.put("replayed", false);
        if (purgeAt != null) r.put("purge_at", format(purgeAt));
        return r;
    }

    private static Map<String, Object> permissionApplied(String requestId, long channelId, int targetType, long target,
            long expected, long expectedPerm, long auditId)
    {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("request_id", requestId);
        r.put("state", "APPLIED");
        r.put("channel_id", String.valueOf(channelId));
        r.put("target_type", targetType);
        r.put("target_id", String.valueOf(target));
        r.put("version", String.valueOf(expected + 1));
        r.put("perm_version", String.valueOf(expectedPerm + 1));
        r.put("audit_id", String.valueOf(auditId));
        r.put("replayed", false);
        return r;
    }

    private static Map<String, Object> overwriteView(Map<String, Object> o)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("target_type", (int) toLong(o.get("targetType")));
        v.put("target_id", String.valueOf(toLong(o.get("targetId"))));
        v.put("allow_bits", hex(toLong(o.get("allowBits"))));
        v.put("deny_bits", hex(toLong(o.get("denyBits"))));
        v.put("note", o.get("note"));
        return v;
    }

    private static Map<String, Object> withVersion(Command c)
    {
        Map<String, Object> m = new LinkedHashMap<>(c.fields);
        m.put("expected_version", c.rawVersion);
        m.put("expected_perm_version", c.rawPermVersion);
        return m;
    }

    private static long target(int targetType, String targetId)
    {
        if (targetType == 1 && "0".equals(targetId)) return 0L;
        if (targetType == 2 && targetId != null && targetId.matches("[1-9][0-9]{0,18}")) return Long.parseLong(targetId);
        throw ChannelRejection.badField("覆盖目标不合法");
    }

    private static String name(Object raw)
    {
        if (!(raw instanceof String text)) throw ChannelRejection.badField("名称不合法");
        if (text.chars().anyMatch(Character::isISOControl)) throw ChannelRejection.badField("名称不合法");
        try { return ChannelDefinition.normalizeNewName(text); }
        catch (IllegalArgumentException e) { throw ChannelRejection.badField("名称不合法"); }
    }

    private static String topic(Map<String, Object> fields, boolean required)
    {
        if (required && !fields.containsKey("topic")) throw ChannelRejection.badField("缺少 topic");
        Object raw = fields.get("topic");
        if (raw == null) return null;
        if (!(raw instanceof String text) || text.codePointCount(0, text.length()) > 120) throw ChannelRejection.badField("主题过长");
        return text;
    }

    private static String stringField(Map<String, Object> fields, String key)
    {
        Object raw = fields.get(key);
        if (!(raw instanceof String text)) throw ChannelRejection.badField(key + " 不合法");
        return text;
    }

    private static int intField(Map<String, Object> fields, String key, int min, int max)
    {
        Object raw = fields.get(key);
        if (!(raw instanceof Integer || raw instanceof Long)) throw ChannelRejection.badField(key + " 不合法");
        long v = ((Number) raw).longValue();
        if (v < min || v > max) throw ChannelRejection.badField(key + " 超出范围");
        return (int) v;
    }

    private static long idField(Object raw, boolean allowZero)
    {
        if (raw instanceof String text && (text.matches("[1-9][0-9]{0,18}") || (allowZero && "0".equals(text))))
            return Long.parseLong(text);
        throw ChannelRejection.badField("编号不合法");
    }

    static String format(Object date)
    {
        if (date == null) return null;
        if (date instanceof java.time.LocalDateTime t) date = java.sql.Timestamp.valueOf(t);
        if (!(date instanceof Date d)) return date.toString();
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /** 写请求公共壳：request_id/reason 必填且 reason 与 X-Audit-Log-Reason 一致；未知字段拒绝。 */
    static final class Command
    {
        final String requestId;
        final String reason;
        final Map<String, Object> fields;
        final Object rawVersion;
        final Object rawPermVersion;

        private Command(String requestId, String reason, Map<String, Object> fields, Object rawVersion, Object rawPermVersion)
        {
            this.requestId = requestId;
            this.reason = reason;
            this.fields = fields;
            this.rawVersion = rawVersion;
            this.rawPermVersion = rawPermVersion;
        }

        static Command of(Map<String, Object> body, String headerReason, Set<String> allowed, boolean create)
        {
            if (body == null) throw ChannelRejection.badField("请求体为空");
            Map<String, Object> fields = new LinkedHashMap<>(body);
            Object requestId = fields.remove("request_id");
            Object reason = fields.remove("reason");
            Object version = fields.remove("expected_version");
            Object permVersion = fields.remove("expected_perm_version");
            if (!(requestId instanceof String id) || !id.matches("[A-Za-z0-9_.:-]{8,64}"))
                throw ChannelRejection.badField("request_id 不合法");
            if (!(reason instanceof String text) || text.isBlank() || text.length() > 120
                    || text.chars().anyMatch(Character::isISOControl))
                throw ChannelRejection.badField("reason 不合法");
            if (headerReason == null || !headerReason.equals(text))
                throw ChannelRejection.badField("X-Audit-Log-Reason 与 reason 不一致");
            if (create && version != null) throw ChannelRejection.badField("创建不接受 expected_version");
            for (String key : fields.keySet())
                if (!allowed.contains(key)) throw ChannelRejection.badField("未知字段 " + key);
            if (create && !fields.keySet().equals(allowed)) throw ChannelRejection.badField("创建字段不完整");
            return new Command(id, text, fields, version, permVersion);
        }

        long expectedVersion() { return version(rawVersion, "expected_version"); }
        long expectedPermVersion() { return version(rawPermVersion, "expected_perm_version"); }

        private static long version(Object raw, String key)
        {
            if (raw instanceof String text && text.matches("0|[1-9][0-9]{0,18}")) return Long.parseLong(text);
            throw ChannelRejection.badField(key + " 必须是十进制字符串");
        }
    }
}
