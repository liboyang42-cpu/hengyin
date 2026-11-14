package com.chengyinhub.business.service.channel.foundation;

import com.chengyinhub.business.mapper.channel.foundation.ChannelLifecycleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Actual archive persistence, deliberately not auto-registered or exposed as HTTP.
 * A production caller must supply a current server-authoritative management adapter;
 * this class neither resolves permissions nor accepts a caller-provided permission bit.
 * Permission-cache invalidation and public endpoints remain separate integration work.
 */
public final class ChannelLifecyclePersistence {
    @FunctionalInterface
    public interface ManagementAuthority {
        /** Must verify current actor, guild lifecycle/membership, and MANAGE_CHANNEL in this transaction. */
        void requireCurrentManagement(long actor, int guildKind, long guildId, long channelId);
    }

    public record ArchiveCommand(long actor, int guildKind, long guildId, long channelId,
            int expectedPermissionVersion, boolean archived, String requestId, String auditReason) {
        public ArchiveCommand {
            if (actor<=0 || guildKind<1 || guildKind>7 || guildId<=0 || channelId<=0 || expectedPermissionVersion<0)
                throw new IllegalArgumentException("Invalid command scope");
            if (requestId==null || !requestId.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Original request identity required");
            if (auditReason==null || auditReason.isBlank() || auditReason.codePointCount(0,auditReason.length())>120)
                throw new IllegalArgumentException("Audit reason required");
        }
    }

    private final ChannelLifecycleMapper mapper;
    private final TransactionTemplate transactions;
    private final ManagementAuthority authority;
    private final Clock clock;
    private final ObjectMapper json;

    public ChannelLifecyclePersistence(ChannelLifecycleMapper mapper, TransactionTemplate transactions,
            ManagementAuthority authority, Clock clock, ObjectMapper json) {
        this.mapper=Objects.requireNonNull(mapper); this.transactions=Objects.requireNonNull(transactions);
        this.authority=Objects.requireNonNull(authority); this.clock=Objects.requireNonNull(clock); this.json=Objects.requireNonNull(json);
    }

    public Map<String,Object> archive(ArchiveCommand command) {
        Objects.requireNonNull(command);
        return transactions.execute(status -> {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                    || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                throw new IllegalStateException("Writable transaction required");
            // Always recheck current authority, including before replaying historical responses.
            authority.requireCurrentManagement(command.actor(),command.guildKind(),command.guildId(),command.channelId());
            String fingerprint=fingerprint(command);
            mapper.reserveReceipt(command.actor(),command.requestId(),fingerprint);
            Map<String,Object> receipt=mapper.lockReceipt(command.actor(),command.requestId());
            if (receipt==null || !fingerprint.equals(receipt.get("fingerprint")))
                throw new IllegalArgumentException("IDEMPOTENCY_KEY_REUSED");
            if (receipt.get("response")!=null) {
                Map<String,Object> replay=decode(receipt.get("response").toString()); replay.put("replayed",true); return replay;
            }
            ChannelLifecycleSnapshot row=mapper.lockChannel(command.channelId(),command.guildKind(),command.guildId());
            if (row==null || row.deleted() || row.status()<0 || row.status()>1)
                throw new IllegalStateException("CHANNEL_UNAVAILABLE");
            if (!command.archived() && row.status()==1 && !"manual".equals(row.archiveReason()))
                throw new IllegalStateException("CHANNEL_ARCHIVE_IMMUTABLE");
            int wanted=command.archived()?1:0;
            boolean changed=false;
            LocalDateTime now=LocalDateTime.now(clock);
            if (row.status()!=wanted && row.permissionVersion()==command.expectedPermissionVersion()) {
                if (row.permissionVersion()==Integer.MAX_VALUE) throw new IllegalStateException("CHANNEL_VERSION_EXHAUSTED");
                int updated=command.archived()?mapper.archiveCas(row,"manual",now):mapper.unarchiveCas(row,now);
                if (updated<0 || updated>1) throw new IllegalStateException("CHANNEL_STATE_CONFLICT");
                if (updated==1 && mapper.appendAudit(row,wanted,command.archived()?"ARCHIVE":"UNARCHIVE",command.auditReason(),
                        command.actor(),command.requestId(),now)!=1) throw new IllegalStateException("CHANNEL_AUDIT_FAILED");
                changed=updated==1;
                if (!changed) {
                    row=mapper.lockChannel(command.channelId(),command.guildKind(),command.guildId());
                    if (row==null || row.deleted() || row.status()<0 || row.status()>1)
                        throw new IllegalStateException("CHANNEL_UNAVAILABLE");
                }
            }
            Map<String,Object> response=new LinkedHashMap<>();
            response.put("id",row.id()); response.put("status",changed?wanted:row.status());
            response.put("perm_version",row.permissionVersion()+(changed?1:0));
            response.put("unchanged",!changed); response.put("replayed",false);
            boolean resultArchived=(changed?wanted:row.status())==1;
            response.put("archive_reason",resultArchived?(changed?"manual":row.archiveReason()):null);
            LocalDateTime archivedAt=resultArchived?(changed?now:row.archivedAt()):null;
            response.put("archived_at",archivedAt==null?null:archivedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
            String encoded=encode(response);
            if (mapper.finishReceipt(command.actor(),command.requestId(),encoded)!=1)
                throw new IllegalStateException("CHANNEL_RECEIPT_FAILED");
            // First response and replay use the same persisted representation, including number types.
            return decode(encoded);
        });
    }

    private String fingerprint(ArchiveCommand c) {
        try {
            String body=json.writeValueAsString(java.util.List.of("channel-archive:v1",c.actor(),c.guildKind(),c.guildId(),
                    c.channelId(),c.expectedPermissionVersion(),c.archived(),c.auditReason()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot bind command",e); }
    }
    private String encode(Map<String,Object> response) {
        try { return json.writeValueAsString(response); }
        catch (Exception e) { throw new IllegalStateException("Cannot store receipt",e); }
    }
    private Map<String,Object> decode(String response) {
        try { return json.readValue(response,new TypeReference<LinkedHashMap<String,Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Cannot read receipt",e); }
    }
}
