package depchain.blockchain;

import depchain.client.ClientProtocol;
import depchain.config.Membership;
import depchain.consensus.*;
import depchain.links.AuthenticatedPerfectLink;
import depchain.transport.FairLossLink;
import depchain.transport.UdpTransport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single blockchain member: runs consensus, client request listener, and blockchain service.
 * Client requests are broadcast to all members; only the leader proposes. All members store
 * (requestId -> client address). Clients sign requests; we only accept and propose verified requests,
 * so a byzantine leader cannot get replicas to agree on a forged command.
 * On DECIDE, any member can send response to client.
 */
public final class BlockchainMember implements AutoCloseable {
    private final int selfId;
    private final Membership membership;
    private final BlockchainService blockchain;
    private final HotStuffReplica replica;
    private final int clientPort;
    private final Map<Long, InetSocketAddress> requestIdToClient = new ConcurrentHashMap<>();
    /** requestId -> string for requests that passed signature verification (reject forged proposals). */
    private final Map<Long, String> verifiedClientRequests = new ConcurrentHashMap<>();
    /** Apply blocks in view order so indices 0,1,2,... match client order. Pending: view -> block. */
    private final ConcurrentSkipListMap<Long, Block> pendingByView = new ConcurrentSkipListMap<>();
    private final AtomicLong nextViewToApply = new AtomicLong(0);
    /** requestId -> index; avoids duplicate append if same block decided in multiple views. */
    private final Map<Long, Integer> requestIdToIndex = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ClientProtocol.Request> pendingRequests = new ConcurrentLinkedQueue<>();
    private final Thread clientListenerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final UdpTransport consensusTransport;
    private final FairLossLink fairLoss;
    private final AuthenticatedPerfectLink apl;
    private DatagramSocket clientSocket;

    public BlockchainMember(int selfId, Membership membership, int consensusPort, int clientPort,
                            PrivateKey privateKey, long viewTimeoutMs) throws IOException {
        this.selfId = selfId;
        this.membership = membership;
        this.clientPort = clientPort;
        this.blockchain = new BlockchainService();
        this.consensusTransport = new UdpTransport(consensusPort, 8192);
        this.fairLoss = new FairLossLink(consensusTransport, 5, 40);
        this.apl = new AuthenticatedPerfectLink(selfId, membership, fairLoss, privateKey);
        ConsensusNetwork net = new APLConsensusNetwork(apl, membership);
        HotStuffReplica.BlockValidator validator = this::isVerifiedClientBlock;
        this.replica = new HotStuffReplica(selfId, membership, net, privateKey, this::onDecide, validator, viewTimeoutMs);
        this.clientSocket = new DatagramSocket(clientPort);
        this.clientListenerThread = new Thread(this::clientListenLoop, "client-listener-" + selfId);
        this.clientListenerThread.setDaemon(true);
        this.clientListenerThread.start();
        startLeaderProposeLoop();
    }

    private void onDecide(Block block) {
        byte[] payload = block.getPayload();
        if (payload == null || payload.length < 8) {
            blockchain.onDecide(block);
            return;
        }
        long view = block.getViewNumber();
        pendingByView.put(view, block);
        drainPendingDecides();
    }

    /** Apply pending blocks in view order so indices 0,1,2,... are stable and match commit order. */
    private void drainPendingDecides() {
        while (true) {
            long next = nextViewToApply.get();
            Block b = pendingByView.remove(next);
            if (b == null) {
                if (pendingByView.isEmpty()) break;
                nextViewToApply.set(pendingByView.firstKey());
                continue;
            }
            byte[] payload = b.getPayload();
            if (payload == null || payload.length < 8) {
                nextViewToApply.incrementAndGet();
                continue;
            }
            ByteBuffer buf = ByteBuffer.wrap(payload);
            long requestId = buf.getLong();
            String string = new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
            int index = requestIdToIndex.computeIfAbsent(requestId, k -> {
                blockchain.appendString(string);
                return blockchain.size() - 1;
            });
            nextViewToApply.set(next + 1);

            InetSocketAddress clientAddr = requestIdToClient.get(requestId);
            if (clientAddr != null) {
                try {
                    byte[] resp = ClientProtocol.encodeResponse(requestId, true, index);
                    DatagramPacket p = new DatagramPacket(resp, resp.length, clientAddr);
                    clientSocket.send(p);
                    System.err.println("[member " + selfId + "] onDecide requestId=" + requestId + " sent response index=" + index + " to " + clientAddr);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                System.err.println("[member " + selfId + "] onDecide requestId=" + requestId + " index=" + index + " no client address (not replying)");
            }
        }
    }

    private void startLeaderProposeLoop() {
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                if (membership.getLeaderId(replica.getView()) == selfId) {
                    ClientProtocol.Request req = pendingRequests.poll();
                    if (req != null) {
                        byte[] payload = ByteBuffer.allocate(8 + req.getString().getBytes(StandardCharsets.UTF_8).length)
                            .putLong(req.getRequestId())
                            .put(req.getString().getBytes(StandardCharsets.UTF_8))
                            .array();
                        replica.propose(new Block(replica.getView(), payload));
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "leader-propose-" + selfId);
        t.setDaemon(true);
        t.start();
    }

    /** Return true iff the block payload is a (requestId, string) that we received as a verified signed client request. */
    private boolean isVerifiedClientBlock(Block block) {
        byte[] payload = block.getPayload();
        if (payload == null || payload.length < 8) return true; // non-client block (e.g. empty)
        ByteBuffer b = ByteBuffer.wrap(payload);
        long requestId = b.getLong();
        String string = new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
        return Objects.equals(verifiedClientRequests.get(requestId), string);
    }

    private void clientListenLoop() {
        byte[] buf = new byte[ClientProtocol.MAX_REQUEST_WIRE];
        while (!closed.get() && clientSocket != null && !clientSocket.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                clientSocket.receive(p);
                byte[] copy = new byte[p.getLength()];
                System.arraycopy(p.getData(), p.getOffset(), copy, 0, p.getLength());
                ClientProtocol.Request req = ClientProtocol.parseRequest(copy);
                if (req == null) continue;
                if (!ClientProtocol.verifyRequest(req)) {
                    System.err.println("[member " + selfId + "] dropped client request requestId=" + req.getRequestId() + " (invalid or missing signature)");
                    continue;
                }
                InetSocketAddress clientAddr = new InetSocketAddress(p.getAddress(), p.getPort());
                System.err.println("[member " + selfId + "] received client request requestId=" + req.getRequestId() + " from " + clientAddr);
                requestIdToClient.put(req.getRequestId(), clientAddr);
                verifiedClientRequests.put(req.getRequestId(), req.getString());
                pendingRequests.offer(req);
            } catch (IOException e) {
                if (!closed.get()) e.printStackTrace();
            }
        }
    }

    public BlockchainService getBlockchain() { return blockchain; }
    public HotStuffReplica getReplica() { return replica; }

    @Override
    public void close() {
        closed.set(true);
        replica.close();
        apl.close();
        if (clientSocket != null) clientSocket.close();
        clientListenerThread.interrupt();
    }
}
