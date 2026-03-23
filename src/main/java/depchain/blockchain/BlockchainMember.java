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
import java.nio.file.Path;
import java.util.ArrayList;
import depchain.demo.MultiProcessConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single blockchain member: runs consensus, client request listener, and blockchain service.
 * Client requests are broadcast to all members; only the leader proposes. All members store
 * (requestId -> client address). Clients sign requests; we only accept and propose verified requests.
 * Consensus payloads carry typed transaction batches ({@link TxBatchPayload}), not raw strings.
 * On DECIDE, any member can send response to client.
 */
public final class BlockchainMember implements AutoCloseable {
    private final int selfId;
    private final Membership membership;
    private final BlockchainService blockchain;
    private final HotStuffReplica replica;
    private final Map<Long, InetSocketAddress> requestIdToClient = new ConcurrentHashMap<>();
    /** requestId -> command for requests that passed signature verification. */
    private final Map<Long, String> verifiedClientRequests = new ConcurrentHashMap<>();
    /** Apply blocks in view order so indices 0,1,2,... match client order. Pending: view -> block. */
    private final ConcurrentSkipListMap<Long, Block> pendingByView = new ConcurrentSkipListMap<>();
    private final AtomicLong nextViewToApply = new AtomicLong(0);
    /** Consensus block hashes already applied locally; prevents replay re-execution. */
    private final Set<String> appliedConsensusBlocks = ConcurrentHashMap.newKeySet();
    /** Verified requests are only queued; execution happens exclusively in DECIDE path. */
    private final ConcurrentLinkedQueue<ClientProtocol.Request> pendingRequests = new ConcurrentLinkedQueue<>();
    private final Thread clientListenerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final UdpTransport consensusTransport;
    private final FairLossLink fairLoss;
    private final AuthenticatedPerfectLink apl;
    private final BlockchainLedger ledger;
    private final BlockJsonStore decidedBlockStore = new BlockJsonStore();
    private final Path decidedBlocksDir;
    private DatagramSocket clientSocket;

    /** Requires MemberConfig with threshold keyShare and groupKey (and APL privateKey). */
    public BlockchainMember(int selfId, Membership membership, int consensusPort, int clientPort,
                            MultiProcessConfig.MemberConfig config, long viewTimeoutMs) throws IOException {
        this.selfId = selfId;
        this.membership = membership;
        this.blockchain = new BlockchainService();
        this.decidedBlocksDir = Path.of("build", "consensus-blocks", "member-" + selfId);
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        this.ledger =
            new BlockchainLedger(WorldState.fromGenesis(genesis), genesis.getBlockHash());
        this.consensusTransport = new UdpTransport(consensusPort, 8192);
        this.fairLoss = new FairLossLink(consensusTransport, 5, 40);
        this.apl = new AuthenticatedPerfectLink(selfId, membership, fairLoss, config.privateKey);
        ConsensusNetwork net = new APLConsensusNetwork(apl, membership);
        HotStuffReplica.BlockValidator validator = this::isVerifiedClientBlock;
        this.replica = new HotStuffReplica(selfId, membership, net, config.keyShare, config.groupKey, this::onDecide, validator, viewTimeoutMs);
        this.clientSocket = new DatagramSocket(clientPort);
        this.clientListenerThread = new Thread(this::clientListenLoop, "client-listener-" + selfId);
        this.clientListenerThread.setDaemon(true);
        this.clientListenerThread.start();
        startLeaderProposeLoop();
    }

    private void onDecide(Block block) {
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
            TxBatchPayload batch = TxBatchPayload.fromBytes(b.getPayload());
            if (batch == null || batch.getItems().isEmpty()) {
                nextViewToApply.incrementAndGet();
                continue;
            }
            String consensusBlockId = toHex(b.getBlockHash());
            if (!appliedConsensusBlocks.add(consensusBlockId)) {
                // Replay of an already applied DECIDE: ignore to keep state/persistence idempotent.
                nextViewToApply.set(next + 1);
                continue;
            }

            // Critical Step 4 rule: execute in exactly the order decided by consensus payload.
            List<TxBatchPayload.TxItem> items = batch.getItems();
            List<Transaction> decidedTxs = new ArrayList<>(items.size());
            List<Long> decidedRequestIds = new ArrayList<>(items.size());
            for (TxBatchPayload.TxItem item : items) {
                Transaction tx = TransactionCommandCodec.decode(item.command());
                if (tx == null) {
                    nextViewToApply.set(next + 1);
                    decidedTxs.clear();
                    decidedRequestIds.clear();
                    break;
                }
                decidedTxs.add(tx);
                decidedRequestIds.add(item.requestId());
            }
            if (decidedTxs.isEmpty()) {
                continue;
            }
            LedgerBlock persisted = ledger.appendDecidedBlock(decidedTxs);
            decidedBlockStore.save(decidedBlocksDir, persisted);
            // Commit local first (persist), then reply to client with tx execution result.
            List<ResponseInfo> pendingResponses = new ArrayList<>(items.size());
            List<ExecutedTransaction> executed = persisted.getTransactions();
            int responseIndex = Math.toIntExact(persisted.getHeight());
            for (int i = 0; i < decidedRequestIds.size(); i++) {
                long requestId = decidedRequestIds.get(i);
                boolean success = executed.get(i).getResult().isSuccess();
                pendingResponses.add(new ResponseInfo(requestId, responseIndex, success));
            }
            sendDecideResponses(pendingResponses);
            nextViewToApply.set(next + 1);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void sendDecideResponses(List<ResponseInfo> pendingResponses) {
        for (ResponseInfo r : pendingResponses) {
            InetSocketAddress clientAddr = requestIdToClient.get(r.requestId());
            if (clientAddr != null) {
                try {
                    byte[] resp = ClientProtocol.encodeResponse(
                        r.requestId(),
                        r.success(),
                        r.index()
                    );
                    DatagramPacket p = new DatagramPacket(resp, resp.length, clientAddr);
                    clientSocket.send(p);
                    System.err.println(
                        "[member " +
                        selfId +
                        "] onDecide requestId=" +
                        r.requestId() +
                        " sent response index=" +
                        r.index() +
                        " to " +
                        clientAddr
                    );
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                System.err.println(
                    "[member " +
                    selfId +
                    "] onDecide requestId=" +
                    r.requestId() +
                    " index=" +
                    r.index() +
                    " no client address (not replying)"
                );
            }
        }
    }

    private record ResponseInfo(long requestId, int index, boolean success) {}

    private void startLeaderProposeLoop() {
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                if (membership.getLeaderId(replica.getView()) == selfId) {
                    ClientProtocol.Request req = pendingRequests.poll();
                    if (req != null) {
                        byte[] payload = new TxBatchPayload(
                            List.of(new TxBatchPayload.TxItem(req.getRequestId(), req.getString()))
                        ).toBytes();
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

    /** Returns true iff every tx item in payload was received as a verified signed client request. */
    private boolean isVerifiedClientBlock(Block block) {
        TxBatchPayload batch = TxBatchPayload.fromBytes(block.getPayload());
        if (batch == null || batch.getItems().isEmpty()) {
            return true; // non-client/empty block
        }
        for (TxBatchPayload.TxItem item : batch.getItems()) {
            if (
                !Objects.equals(verifiedClientRequests.get(item.requestId()), item.command()) ||
                TransactionCommandCodec.decode(item.command()) == null
            ) {
                return false;
            }
        }
        return true;
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
                if (TransactionCommandCodec.decode(req.getString()) == null) {
                    System.err.println(
                        "[member " +
                        selfId +
                        "] dropped requestId=" +
                        req.getRequestId() +
                        " (not a valid encoded Transaction)"
                    );
                    continue;
                }
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
    public BlockchainLedger getLedger() { return ledger; }

    @Override
    public void close() {
        closed.set(true);
        replica.close();
        apl.close();
        if (clientSocket != null) clientSocket.close();
        clientListenerThread.interrupt();
    }
}
