package depchain.consensus;

import java.io.IOException;
import java.util.Arrays;

/**
 * Wraps a ConsensusNetwork and corrupts outgoing vote messages (signature bytes)
 * so that the vote fails verification. Used to simulate Byzantine behavior.
 */
public final class CorruptingConsensusNetwork implements ConsensusNetwork {
    private final ConsensusNetwork delegate;

    public CorruptingConsensusNetwork(ConsensusNetwork delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendToAll(byte[] payload) throws IOException {
        delegate.sendToAll(maybeCorrupt(payload));
    }

    @Override
    public void sendTo(int memberId, byte[] payload) throws IOException {
        delegate.sendTo(memberId, maybeCorrupt(payload));
    }

    @Override
    public ReceivedMessage poll() {
        return delegate.poll();
    }

    private byte[] maybeCorrupt(byte[] payload) {
        if (payload == null || payload.length < 1) return payload;
        byte type = payload[0];
        if (type == ConsensusMessage.TYPE_PREPARE_VOTE || type == ConsensusMessage.TYPE_PRE_COMMIT_VOTE || type == ConsensusMessage.TYPE_COMMIT_VOTE) {
            byte[] copy = payload.clone();
            if (copy.length > 20) {
                for (int i = copy.length - 10; i < copy.length && i >= 0; i++) copy[i] ^= 0xFF;
            }
            return copy;
        }
        return payload;
    }
}
