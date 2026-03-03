package depchain.transport;

import depchain.config.NodeAddress;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw UDP send/receive. Messages may be lost, duplicated, delayed, or corrupted.
 */
public class UdpTransport implements AutoCloseable {
    private final DatagramSocket socket;
    private final int maxPacketSize;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final BlockingQueue<RawMessage> receiveQueue;
    private final Thread receiverThread;

    public UdpTransport(int bindPort, int maxPacketSize) throws SocketException {
        this.socket = new DatagramSocket(bindPort);
        this.maxPacketSize = maxPacketSize;
        this.receiveQueue = new ArrayBlockingQueue<>(1024);
        this.receiverThread = new Thread(this::receiveLoop, "udp-receiver");
        this.receiverThread.setDaemon(true);
        this.receiverThread.start();
    }

    public void send(byte[] payload, NodeAddress dest) throws IOException {
        if (payload.length > maxPacketSize)
            throw new IllegalArgumentException("payload too large: " + payload.length);
        DatagramPacket p = new DatagramPacket(payload, payload.length, dest.toInetSocketAddress());
        socket.send(p);
    }

    /** Non-blocking: returns null if no message available. */
    public RawMessage poll() {
        return receiveQueue.poll();
    }

    /** Blocking receive. */
    public RawMessage receive() throws InterruptedException {
        return receiveQueue.take();
    }

    private void receiveLoop() {
        byte[] buf = new byte[maxPacketSize];
        while (!closed.get()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                NodeAddress from = new NodeAddress(p.getAddress().getHostAddress(), p.getPort());
                receiveQueue.offer(new RawMessage(from, copy));
            } catch (IOException e) {
                if (!closed.get()) e.printStackTrace();
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        socket.close();
        receiverThread.interrupt();
    }

    public static final class RawMessage {
        private final NodeAddress from;
        private final byte[] payload;

        public RawMessage(NodeAddress from, byte[] payload) {
            this.from = from;
            this.payload = payload;
        }

        public NodeAddress getFrom() { return from; }
        public byte[] getPayload() { return payload; }
    }
}
