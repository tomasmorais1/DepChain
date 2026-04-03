package depchain.blockchain;

import depchain.client.ClientProtocol;
import depchain.config.Membership;
import depchain.consensus.APLConsensusNetwork;
import depchain.consensus.Block;
import depchain.consensus.ConsensusNetwork;
import depchain.consensus.HotStuffReplica;
import depchain.demo.MultiProcessConfig;
import depchain.links.AuthenticatedPerfectLink;
import depchain.transport.FairLossLink;
import depchain.transport.UdpTransport;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.tuweni.bytes.Bytes;

/** One replica: HotStuff consensus, UDP client listener, mempool, ledger execution and persistence. */
public final class BlockchainMember implements AutoCloseable {
    private static final int MAX_TXS_PER_BLOCK = 64;
    private static final long MAX_GAS_PER_BLOCK = 5_000_000L;

    private final int selfId;
    private final Membership membership;
    private final BlockchainService blockchain;
    private final HotStuffReplica replica;

    private final Map<Long, InetSocketAddress> requestIdToClient = new ConcurrentHashMap<>();
    /** requestId -> canonical command string for requests that passed signature verification. */
    private final Map<Long, String> verifiedClientRequests = new ConcurrentHashMap<>();
    /** requestId -> pending verified tx currently known locally. */
    private final Map<Long, PendingTransaction> pendingByRequestId = new ConcurrentHashMap<>();

    /** Apply blocks in view order so indices 0,1,2,... match commit order. */
    private final ConcurrentSkipListMap<Long, Block> pendingByView = new ConcurrentSkipListMap<>();
    private final AtomicLong nextViewToApply = new AtomicLong(0);

    /** Consensus block hashes already applied locally; prevents replay re-execution. */
    private final Set<String> appliedConsensusBlocks = ConcurrentHashMap.newKeySet();

    private final Thread clientListenerThread;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastProposedView = new AtomicLong(Long.MIN_VALUE);

    private final UdpTransport consensusTransport;
    private final FairLossLink fairLoss;
    private final AuthenticatedPerfectLink apl;
    private final BlockchainLedger ledger;
    private final BlockJsonStore decidedBlockStore = new BlockJsonStore();
    private final Path decidedBlocksDir;
    private final Mempool mempool;
    private final BlockBuilder blockBuilder;

    private DatagramSocket clientSocket;

    /** Requires MemberConfig with threshold keyShare and groupKey (and APL privateKey). */
    public BlockchainMember(
        int selfId,
        Membership membership,
        int consensusPort,
        int clientPort,
        MultiProcessConfig.MemberConfig config,
        long viewTimeoutMs
    ) throws IOException {
        this.selfId = selfId;
        this.membership = membership;
        this.blockchain = new BlockchainService();
        this.decidedBlocksDir = Path.of("build", "consensus-blocks", "member-" + selfId);

        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        this.ledger = new BlockchainLedger(WorldState.fromGenesis(genesis), genesis.getBlockHash());
        applyGenesisContracts(genesis);
        bootstrapIstIfPresent(genesis);

        this.mempool = new Mempool();
        this.blockBuilder = new BlockBuilder(new BlockBuilderConfig(MAX_TXS_PER_BLOCK, MAX_GAS_PER_BLOCK));

        this.consensusTransport = new UdpTransport(consensusPort, 8192);
        this.fairLoss = new FairLossLink(consensusTransport, 5, 40);
        this.apl = new AuthenticatedPerfectLink(
            selfId,
            membership,
            fairLoss,
            config.privateKey,
            config.linkMac
        );
        ConsensusNetwork net = new APLConsensusNetwork(selfId, apl, membership);
        HotStuffReplica.BlockValidator validator = this::isVerifiedClientBlock;
        this.replica = new HotStuffReplica(
            selfId,
            membership,
            net,
            config.keyShare,
            config.groupKey,
            this::onDecide,
            validator,
            viewTimeoutMs
        );

        this.clientSocket = new DatagramSocket(clientPort);
        this.clientListenerThread = new Thread(this::clientListenLoop, "client-listener-" + selfId);
        this.clientListenerThread.setDaemon(true);
        this.clientListenerThread.start();

        startLeaderProposeLoop();
    }

    /** Installs bytecode and optional storage for any genesis.contracts entries. */
    private void applyGenesisContracts(Genesis genesis) {
        if (genesis == null || genesis.getContracts() == null || genesis.getContracts().isEmpty()) {
            return;
        }
        TransactionExecutor exec = ledger.getExecutor();
        if (exec == null) {
            return;
        }
        for (Genesis.GenesisContract c : genesis.getContracts()) {
            byte[] runtime = hexStringToBytes(c.getRuntimeHex());
            String addr = c.getAddress();
            exec.getContractRegistry().put(addr, runtime);
            exec.getEvm().setContractCode(addr, runtime);
            ledger.getWorldState().getOrCreate(addr);
            exec.getEvm().upsertAccount(addr, 0, 0);
            for (Map.Entry<String, String> slot : c.getStorage().entrySet()) {
                exec.getEvm().putStorageHex(addr, slot.getKey(), slot.getValue());
            }
        }
    }

    /**
     * Bootstraps IST Coin contract state directly from genesis.json, without going through consensus.
     * Required so read-queries (e.g. balanceOf) can be answered immediately.
     */
    private void bootstrapIstIfPresent(Genesis genesis) {
        if (genesis == null || genesis.getTransactions() == null || genesis.getTransactions().isEmpty()) {
            return;
        }
        Transaction deploy = null;
        for (Transaction t : genesis.getTransactions()) {
            if (t != null && t.isContractDeployment()) {
                deploy = t;
                break;
            }
        }
        if (deploy == null) {
            return;
        }
        TransactionExecutor exec = ledger.getExecutor();
        if (exec == null) {
            return;
        }
        if (!depchain.blockchain.evm.IstCoinBytecode.isKnownCreationBytecode(deploy.getData())) {
            return;
        }

        String contractAddress = TransactionExecutor.deriveContractAddress(deploy.getFrom(), deploy.getNonce());
        if (exec.getContractRegistry().contains(contractAddress)) {
            return;
        }
        byte[] runtime = depchain.blockchain.evm.IstCoinBytecode.readRuntimeBytecode();
        exec.getContractRegistry().put(contractAddress, runtime);
        exec.getEvm().setContractCode(contractAddress, runtime);
        ledger.getWorldState().getOrCreate(contractAddress);
        exec.getEvm().upsertAccount(contractAddress, 0, 0);
        exec.getEvm().seedIstCoinBalancesAfterDeploy(deploy.getFrom(), contractAddress);
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
                if (pendingByView.isEmpty()) {
                    break;
                }
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
                nextViewToApply.set(next + 1);
                continue;
            }

            List<TxBatchPayload.TxItem> items = batch.getItems();
            List<Transaction> decidedTxs = new ArrayList<>(items.size());
            List<Long> decidedRequestIds = new ArrayList<>(items.size());
            List<PendingTransaction> committedPending = new ArrayList<>(items.size());

            boolean decodeFailed = false;
            for (TxBatchPayload.TxItem item : items) {
                Transaction tx = TransactionCommandCodec.decode(item.command());
                if (tx == null) {
                    decodeFailed = true;
                    break;
                }
                decidedTxs.add(tx);
                decidedRequestIds.add(item.requestId());

                PendingTransaction pending = pendingByRequestId.get(item.requestId());
                if (pending != null) {
                    committedPending.add(pending);
                }
            }

            if (decodeFailed || decidedTxs.isEmpty()) {
                nextViewToApply.set(next + 1);
                continue;
            }

            LedgerBlock persisted = ledger.appendDecidedBlock(decidedTxs);
            decidedBlockStore.save(decidedBlocksDir, persisted);

            mempool.removeCommitted(committedPending);
            for (Long requestId : decidedRequestIds) {
                pendingByRequestId.remove(requestId);
            }

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

    private void sendDecideResponses(List<ResponseInfo> pendingResponses) {
        for (ResponseInfo r : pendingResponses) {
            InetSocketAddress clientAddr = requestIdToClient.get(r.requestId());
            if (clientAddr != null) {
                try {
                    byte[] resp = ClientProtocol.encodeResponse(r.requestId(), r.success(), r.index());
                    DatagramPacket p = new DatagramPacket(resp, resp.length, clientAddr);
                    clientSocket.send(p);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void startLeaderProposeLoop() {
        Thread t = new Thread(() -> {
            while (!closed.get()) {
                try {
                    long currentView = replica.getView();
                    if (membership.getLeaderId(currentView) == selfId) {
                        maybeProposeForView(currentView);
                    }
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "leader-propose-" + selfId);
        t.setDaemon(true);
        t.start();
    }

    private void maybeProposeForView(long currentView) {
        if (!mempool.hasPendingTransactions()) {
            return;
        }
        if (lastProposedView.get() == currentView) {
            return;
        }

        NonceProvider nonceProvider = accountAddress -> {
            WorldState.AccountState acc = ledger.getWorldState().get(accountAddress);
            return acc == null ? 0L : acc.getNonce();
        };

        BlockCandidate candidate = blockBuilder.buildNextBlock(mempool, nonceProvider);
        if (candidate.isEmpty()) {
            return;
        }

        List<TxBatchPayload.TxItem> items = new ArrayList<>(candidate.getTransactions().size());
        for (PendingTransaction pending : candidate.getTransactions()) {
            String command = verifiedClientRequests.get(pending.getRequestId());
            if (command == null) {
                return;
            }
            items.add(new TxBatchPayload.TxItem(pending.getRequestId(), command));
        }

        lastProposedView.set(currentView);
        byte[] payload = new TxBatchPayload(items).toBytes();
        replica.propose(new Block(currentView, payload));
    }

    /**
     * Returns true iff every tx item in payload:
     * - corresponds to a locally verified signed client request
     * - has not been tampered with by the leader
     * - contains no duplicate request ids
     * - preserves per-account nonce order within the batch
     */
    private boolean isVerifiedClientBlock(Block block) {
        TxBatchPayload batch = TxBatchPayload.fromBytes(block.getPayload());
        if (batch == null || batch.getItems().isEmpty()) {
            return true;
        }

        Set<Long> seenRequestIds = new HashSet<>();
        Map<String, Long> expectedNonceByAccount = new ConcurrentHashMap<>();

        for (TxBatchPayload.TxItem item : batch.getItems()) {
            if (!seenRequestIds.add(item.requestId())) {
                return false;
            }

            String verifiedCommand = verifiedClientRequests.get(item.requestId());
            if (!Objects.equals(verifiedCommand, item.command())) {
                return false;
            }

            Transaction tx = TransactionCommandCodec.decode(item.command());
            if (tx == null) {
                return false;
            }

            String sender = tx.getFrom();
            long expectedNonce = expectedNonceByAccount.computeIfAbsent(sender, key -> {
                WorldState.AccountState acc = ledger.getWorldState().get(key);
                return acc == null ? 0L : acc.getNonce();
            });

            if (tx.getNonce() != expectedNonce) {
                return false;
            }
            expectedNonceByAccount.put(sender, expectedNonce + 1);
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
                if (req == null) {
                    continue;
                }

                InetSocketAddress clientAddr = new InetSocketAddress(p.getAddress(), p.getPort());
                if (req.getProtocolKind() == ClientProtocol.TYPE_QUERY) {
                    handleQuery(req, clientAddr);
                    continue;
                }

                if (!ClientProtocol.verifyRequest(req)) {
                    continue;
                }

                Transaction tx = TransactionCommandCodec.decode(req.getString());
                if (tx == null) {
                    continue;
                }

                PendingTransaction pending = mempool.addVerifiedTransaction(
                    req.getRequestId(),
                    "client",
                    tx,
                    req.getString().getBytes(StandardCharsets.UTF_8)
                );
                if (pending == null) {
                    continue;
                }

                requestIdToClient.put(req.getRequestId(), clientAddr);
                verifiedClientRequests.put(req.getRequestId(), req.getString());
                pendingByRequestId.put(req.getRequestId(), pending);
            } catch (IOException e) {
                if (!closed.get()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleQuery(ClientProtocol.Request req, InetSocketAddress clientAddr) {
        byte queryKind = req.getQueryKind();
        long requestId = req.getRequestId();
        byte[] returnData = new byte[0];
        boolean success = true;

        try {
            String payload = req.getString() == null ? "" : req.getString().trim();
            if (queryKind == ClientProtocol.QUERY_DEP_BALANCE) {
                String address = payload;
                WorldState.AccountState acc = ledger.getWorldState().get(address);
                long bal = acc == null ? 0L : acc.getBalance();
                returnData = java.nio.ByteBuffer.allocate(8).putLong(bal).array();
            } else if (queryKind == ClientProtocol.QUERY_IST_BALANCE_OF) {
                String[] parts = payload.split("\\|", 2);
                if (parts.length != 2) {
                    throw new IllegalArgumentException("IST query payload must be <contract>|<address>");
                }
                String contract = parts[0].trim();
                String who = parts[1].trim();
                Bytes out = ledger.getExecutor().getEvm().readIstBalanceOfReturnData(contract, who);
                returnData = out == null ? new byte[0] : out.toArrayUnsafe();
            } else {
                success = false;
            }
        } catch (Exception e) {
            success = false;
        }

        LedgerBlock head = null;
        List<LedgerBlock> blocks = ledger.getBlocks();
        if (!blocks.isEmpty()) {
            head = blocks.get(blocks.size() - 1);
        }
        int headHeight = head == null ? 0 : Math.toIntExact(head.getHeight());
        String headHash = head == null ? "" : head.getBlockHash();

        try {
            byte[] resp = ClientProtocol.encodeQueryResponse(requestId, success, headHeight, headHash, returnData);
            DatagramPacket pkt = new DatagramPacket(resp, resp.length, clientAddr);
            clientSocket.send(pkt);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public BlockchainService getBlockchain() {
        return blockchain;
    }

    public HotStuffReplica getReplica() {
        return replica;
    }

    public BlockchainLedger getLedger() {
        return ledger;
    }

    @Override
    public void close() {
        closed.set(true);
        replica.close();
        apl.close();
        if (clientSocket != null) {
            clientSocket.close();
        }
        clientListenerThread.interrupt();
    }

    private record ResponseInfo(long requestId, int index, boolean success) {}

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexStringToBytes(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if ((s.length() & 1) != 0) {
            s = "0" + s;
        }
        if (s.isEmpty()) {
            return new byte[0];
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
