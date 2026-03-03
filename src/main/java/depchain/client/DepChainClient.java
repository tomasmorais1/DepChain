package depchain.client;

import depchain.config.NodeAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client library: append(string) broadcasts request to all members, waits for confirmation.
 */
public final class DepChainClient implements AutoCloseable {
    private final List<NodeAddress> memberAddresses;
    private final int clientPort;
    private final long timeoutMs;
    private final int maxRetries;
    private final AtomicLong requestIdGen = new AtomicLong(0);
    private volatile DatagramSocket socket;
    private final Map<Long, CompletableFuture<ClientProtocol.Response>> pending = new ConcurrentHashMap<>();
    private final Thread receiverThread;
    private volatile boolean closed;

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort) {
        this(memberAddresses, clientPort, 5000L, 3);
    }

    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, long timeoutMs, int maxRetries) {
        this(memberAddresses, clientPort, null, timeoutMs, maxRetries);
    }

    /**
     * Create client; if bindAddress is non-null, bind the receive socket to that address (e.g. 127.0.0.1 for multi-JVM so responses reach us).
     */
    public DepChainClient(List<NodeAddress> memberAddresses, int clientPort, InetSocketAddress bindAddress, long timeoutMs, int maxRetries) {
        this.memberAddresses = List.copyOf(memberAddresses);
        this.clientPort = clientPort;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        try {
            this.socket = bindAddress != null
                    ? new DatagramSocket(bindAddress)
                    : new DatagramSocket(clientPort);
        } catch (Exception e) {
            throw new RuntimeException("bind client port " + clientPort, e);
        }
        this.receiverThread = new Thread(this::receiveLoop, "client-receiver");
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    /**
     * Append string to the blockchain. Blocks until a member confirms or timeout.
     * @return the index at which the string was appended, or -1 on failure
     */
    public int append(String string) throws InterruptedException {
        if (string == null) return -1;
        long requestId = requestIdGen.incrementAndGet();
        byte[] payload = ClientProtocol.encodeRequest(requestId, string);
        CompletableFuture<ClientProtocol.Response> future = new CompletableFuture<>();
        pending.put(requestId, future);
        try {
            for (int r = 0; r < maxRetries; r++) {
                try {
                    System.err.println("[client] send requestId=" + requestId + " to " + memberAddresses);
                    for (NodeAddress addr : memberAddresses) {
                        DatagramPacket p = new DatagramPacket(payload, payload.length, addr.toInetSocketAddress());
                        socket.send(p);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    ClientProtocol.Response resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                    System.err.println("[client] received response requestId=" + requestId + " index=" + resp.getIndex() + " success=" + resp.isSuccess());
                    return resp.isSuccess() ? resp.getIndex() : -1;
                } catch (TimeoutException e) {
                    System.err.println("[client] timeout requestId=" + requestId + " retry " + (r + 1) + "/" + maxRetries);
                    continue;
                } catch (ExecutionException e) {
                    return -1;
                }
            }
            System.err.println("[client] all retries failed for requestId=" + requestId);
            return -1;
        } finally {
            pending.remove(requestId);
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (!closed && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                ClientProtocol.Response resp = ClientProtocol.parseResponse(copy);
                if (resp != null) {
                    System.err.println("[client] recv response requestId=" + resp.getRequestId() + " from " + p.getSocketAddress());
                    CompletableFuture<ClientProtocol.Response> f = pending.remove(resp.getRequestId());
                    if (f != null) f.complete(resp);
                }
            } catch (IOException e) {
                if (!closed) e.printStackTrace();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        if (socket != null) socket.close();
        receiverThread.interrupt();
    }
}
