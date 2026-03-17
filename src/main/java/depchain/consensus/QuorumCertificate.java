package depchain.consensus;

import threshsig.GroupKey;
import threshsig.SigShare;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Quorum Certificate using threshold signatures: certifies that a quorum of replicas
 * voted for a block in a given phase via k signature shares verifiable under the group key.
 */
public final class QuorumCertificate {
    private final long viewNumber;
    private final Phase phase;
    private final byte[] blockHash;
    private final GroupKey groupKey;
    private final SigShare[] sigShares;

    public QuorumCertificate(long viewNumber, Phase phase, byte[] blockHash,
            GroupKey groupKey, SigShare[] sigShares) {
        this.viewNumber = viewNumber;
        this.phase = Objects.requireNonNull(phase);
        this.blockHash = blockHash == null ? new byte[0] : blockHash.clone();
        this.groupKey = Objects.requireNonNull(groupKey);
        this.sigShares = sigShares == null ? new SigShare[0] : sigShares.clone();
    }

    public long getViewNumber() { return viewNumber; }
    public Phase getPhase() { return phase; }
    public byte[] getBlockHash() { return blockHash.clone(); }
    public GroupKey getGroupKey() { return groupKey; }
    public SigShare[] getSigShares() { return sigShares.clone(); }
    public int getVoteCount() { return sigShares.length; }
}
