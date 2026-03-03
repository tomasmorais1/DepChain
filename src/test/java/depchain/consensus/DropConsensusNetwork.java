package depchain.consensus;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps a ConsensusNetwork and drops a fraction of messages (for intrusive tests).
 */
public final class DropConsensusNetwork implements ConsensusNetwork {
    private final ConsensusNetwork delegate;
    private final double dropProbability;

    public DropConsensusNetwork(ConsensusNetwork delegate, double dropProbability) {
        this.delegate = delegate;
        this.dropProbability = Math.max(0, Math.min(1, dropProbability));
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        if (ThreadLocalRandom.current().nextDouble() < dropProbability) return;
        delegate.sendToAll(payload);
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        if (ThreadLocalRandom.current().nextDouble() < dropProbability) return;
        delegate.sendTo(memberId, payload);
    }

    @Override
    public ReceivedMessage poll() {
        return delegate.poll();
    }
}
