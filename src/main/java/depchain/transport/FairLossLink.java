package depchain.transport;

import depchain.config.NodeAddress;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort link over UDP: one send per call.
 * Retransmissions are handled by APL above: APL retries until it receives an ACK,
 * so there are no infinite or blind retries.
 * Duplicates are passed up. APL does deduplication after authentication.
 */
public class FairLossLink implements AutoCloseable {
    private final UdpTransport transport;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FairLossLink(UdpTransport transport, int maxRetries, long retryDelayMs) {
        this.transport = transport;
        // maxRetries/retryDelayMs ignored: APL controls retries until ACK
    }

    public void send(byte[] payload, NodeAddress dest) throws IOException {
        if (closed.get()) throw new IOException("link closed");
        transport.send(payload, dest);
    }

    /** Poll for a raw message (may be duplicate). */
    public UdpTransport.RawMessage poll() {
        return transport.poll();
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            transport.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
