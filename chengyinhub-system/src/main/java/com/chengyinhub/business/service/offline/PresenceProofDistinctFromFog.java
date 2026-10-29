package com.chengyinhub.business.service.offline;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * C05 独占：迷雾只是探索记忆，不是到场证明。不改 RoamTileMemoryPager。禁止伪造 GPS。
 */
public final class PresenceProofDistinctFromFog {

    private static final Pattern TILE = Pattern.compile("^[0-9a-zA-Z:_\\-]{1,64}$");

    public enum Failure {
        FOG_IS_NOT_PRESENCE, PROOF_REQUIRED, FAKE_GPS_REJECTED, PRESENCE_CAPABILITY_DISABLED
    }

    private PresenceProofDistinctFromFog() {
    }

    /** Existing roam presence capability stays off in this exclusive adapter. */
    public static boolean roamPresenceCapabilityEnabled() {
        return false;
    }

    public static boolean isExplorationMemory(String tile) {
        return tile != null && TILE.matcher(tile).matches();
    }

    public static void certifyPresence(FogExplorationMemory memory, SideQuestAcceptHold.Proof proof) {
        Objects.requireNonNull(memory);
        if (roamPresenceCapabilityEnabled()) {
            throw new PresenceProofException(Failure.PRESENCE_CAPABILITY_DISABLED);
        }
        if (proof == null) {
            throw new PresenceProofException(Failure.FOG_IS_NOT_PRESENCE);
        }
        if (proof.kind == SideQuestAcceptHold.ProofKind.GPS && !proof.attested) {
            throw new PresenceProofException(Failure.FAKE_GPS_REJECTED);
        }
    }

    public static void certifyPresenceFromFogTiles(Set<String> tiles) {
        Objects.requireNonNull(tiles);
        throw new PresenceProofException(Failure.FOG_IS_NOT_PRESENCE);
    }

    public static void rejectSimulatedGps() {
        throw new PresenceProofException(Failure.FAKE_GPS_REJECTED);
    }

    public static void proofRequiredWithoutFog() {
        throw new PresenceProofException(Failure.PROOF_REQUIRED);
    }

    public static final class FogExplorationMemory {
        public final Set<String> tiles;

        public FogExplorationMemory(Set<String> tiles) {
            if (tiles == null || tiles.size() > 2000) {
                throw new PresenceProofException(Failure.FOG_IS_NOT_PRESENCE);
            }
            Set<String> copy = new LinkedHashSet<String>();
            for (String tile : tiles) {
                if (!isExplorationMemory(tile)) {
                    throw new PresenceProofException(Failure.FOG_IS_NOT_PRESENCE);
                }
                copy.add(tile);
            }
            this.tiles = Collections.unmodifiableSet(copy);
        }
    }

    public static final class PresenceProofException extends IllegalStateException {
        public final Failure code;

        PresenceProofException(Failure code) {
            super(code.name());
            this.code = code;
        }
    }
}
