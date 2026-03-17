package depchain.consensus;

import java.io.IOException;

/**
 * Wraps a ConsensusNetwork and drops all outgoing vote messages (PREPARE_VOTE, PRE_COMMIT_VOTE, COMMIT_VOTE)
 * from this replica. NEW_VIEW and other messages go through. Simulates a replica that fails to vote
 * but still participates in new-view (so leader can get n-f NEW_VIEW).
 */
public final class VoteDroppingConsensusNetwork implements ConsensusNetwork {
    private final ConsensusNetwork delegate;

    public VoteDroppingConsensusNetwork(ConsensusNetwork delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        if (isVote(payload)) return;
        delegate.sendToAll(payload);
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        if (isVote(payload)) return;
        delegate.sendTo(memberId, payload);
    }

    private static boolean isVote(byte[] payload) {
        if (payload == null || payload.length < 1) return false;
        byte t = payload[0];
        return t == ConsensusMessage.TYPE_PREPARE_VOTE || t == ConsensusMessage.TYPE_PRE_COMMIT_VOTE
                || t == ConsensusMessage.TYPE_COMMIT_VOTE;
    }

    @Override
    public ReceivedMessage poll() {
        return delegate.poll();
    }
}
