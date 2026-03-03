package depchain.consensus;

import java.io.IOException;

/**
 * Abstraction for sending/receiving consensus messages. Allows injection of test doubles
 * for intrusive tests (drop, delay, Byzantine).
 */
public interface ConsensusNetwork {

    /** Send payload to all blockchain members (including self if in membership). */
    void sendToAll(byte[] payload) throws IOException;

    /** Send payload to a specific member. */
    void sendTo(int memberId, byte[] payload) throws IOException;

    /** Poll for next delivered message; returns null if none. */
    ReceivedMessage poll();

    interface ReceivedMessage {
        int getSenderId();
        byte[] getPayload();
    }
}
