package depchain.consensus;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a ConsensusNetwork and replays the first PREPARE (leader) or first vote (replica)
 * message once by re-sending it. Used to test that replicas ignore replayed messages
 * (e.g. by view/phase or deduplication).
 */
public final class ReplayConsensusNetwork implements ConsensusNetwork {
    private final ConsensusNetwork delegate;
    private final boolean replayLeaderMessage;
    private final boolean replayReplicaMessage;
    private final AtomicBoolean leaderMessageReplayed = new AtomicBoolean(false);
    private final AtomicBoolean replicaMessageReplayed = new AtomicBoolean(false);

    /**
     * @param replayLeaderMessage if true, replay the first PREPARE sent (leader replay)
     * @param replayReplicaMessage if true, replay the first vote sent (replica replay)
     */
    public ReplayConsensusNetwork(ConsensusNetwork delegate, boolean replayLeaderMessage, boolean replayReplicaMessage) {
        this.delegate = delegate;
        this.replayLeaderMessage = replayLeaderMessage;
        this.replayReplicaMessage = replayReplicaMessage;
        this.replicaMessageReplayed.set(!replayReplicaMessage);
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        delegate.sendToAll(payload);
        if (replayLeaderMessage && payload != null && payload.length > 0 && payload[0] == ConsensusMessage.TYPE_PREPARE
                && leaderMessageReplayed.compareAndSet(false, true)) {
            delegate.sendToAll(payload);
        }
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        delegate.sendTo(memberId, payload);
        if (replayReplicaMessage && payload != null && payload.length > 0 && isVoteType(payload[0])
                && replicaMessageReplayed.compareAndSet(false, true)) {
            delegate.sendTo(memberId, payload);
        }
    }

    private static boolean isVoteType(byte type) {
        return type == ConsensusMessage.TYPE_PREPARE_VOTE || type == ConsensusMessage.TYPE_PRE_COMMIT_VOTE
                || type == ConsensusMessage.TYPE_COMMIT_VOTE;
    }

    @Override
    public ReceivedMessage poll() {
        return delegate.poll();
    }
}
