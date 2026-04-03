package depchain.transport;

import depchain.config.NodeAddress;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** UDP send per call; APL above handles retries and post-auth dedup. */
public class FairLossLink implements AutoCloseable {
    private final UdpTransport transport;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FairLossLink(UdpTransport transport, int maxRetries, long retryDelayMs) {
        this.transport = transport;
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
