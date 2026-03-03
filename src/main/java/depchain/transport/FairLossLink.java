package depchain.transport;

import depchain.config.NodeAddress;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Best-effort link over UDP: retries send a limited number of times to approximate
 * "fair loss" (eventually message gets through). Does NOT deduplicate on receive;
 * duplicates are passed up. APL will do deduplication after authentication.
 */
public class FairLossLink implements AutoCloseable {
    private final UdpTransport transport;
    private final int maxRetries;
    private final long retryDelayMs;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public FairLossLink(UdpTransport transport, int maxRetries, long retryDelayMs) {
        this.transport = transport;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fairloss-retry");
            t.setDaemon(true);
            return t;
        });
    }

    public void send(byte[] payload, NodeAddress dest) throws IOException {
        if (closed.get()) throw new IOException("link closed");
        transport.send(payload, dest);
        for (int i = 1; i < maxRetries; i++) {
            final int attempt = i;
            scheduler.schedule(() -> {
                if (closed.get()) return;
                try {
                    transport.send(payload, dest);
                } catch (IOException ignored) {
                    // socket closed or network error; ignore after close
                }
            }, attempt * retryDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Poll for a raw message (may be duplicate). */
    public UdpTransport.RawMessage poll() {
        return transport.poll();
    }

    @Override
    public void close() {
        closed.set(true);
        scheduler.shutdown();
        try {
            transport.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
