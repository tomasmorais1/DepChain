package depchain.consensus;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Quorum Certificate: certifies that 2f+1 replicas voted for a block in a given phase.
 * For Step 3-4: map of replicaId -> signature. For Step 5 can use threshold sig or keep map.
 */
public final class QuorumCertificate {
    private final long viewNumber;
    private final Phase phase;
    private final byte[] blockHash;
    private final Map<Integer, byte[]> signatures; // replicaId -> signature

    public QuorumCertificate(long viewNumber, Phase phase, byte[] blockHash, Map<Integer, byte[]> signatures) {
        this.viewNumber = viewNumber;
        this.phase = Objects.requireNonNull(phase);
        this.blockHash = blockHash == null ? new byte[0] : blockHash.clone();
        this.signatures = signatures == null ? Map.of() : Map.copyOf(signatures);
    }

    public long getViewNumber() { return viewNumber; }
    public Phase getPhase() { return phase; }
    public byte[] getBlockHash() { return blockHash.clone(); }
    public Map<Integer, byte[]> getSignatures() { return Collections.unmodifiableMap(signatures); }
    public int getVoteCount() { return signatures.size(); }
}
