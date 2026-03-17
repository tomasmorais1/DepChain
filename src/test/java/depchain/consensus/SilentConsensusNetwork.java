package depchain.consensus;

import java.io.IOException;

/**
 * Wraps a ConsensusNetwork and drops all outgoing messages (simulates a crashed replica).
 */
public final class SilentConsensusNetwork implements ConsensusNetwork {
    private final ConsensusNetwork delegate;

    public SilentConsensusNetwork(ConsensusNetwork delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        // drop
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        // drop
    }

    @Override
    public ReceivedMessage poll() {
        return delegate.poll();
    }
}
