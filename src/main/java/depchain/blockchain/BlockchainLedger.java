package depchain.blockchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Local Step 3 chain execution pipeline (without consensus integration).
 */
public final class BlockchainLedger {
    private final List<LedgerBlock> chain = new ArrayList<>();
    private final WorldState worldState;
    private final TransactionExecutor executor;
    private String lastBlockHash;
    private long nextHeight;

    public BlockchainLedger(WorldState worldState, String genesisBlockHash) {
        this(worldState, genesisBlockHash, new TransactionExecutor());
    }

    public BlockchainLedger(
        WorldState worldState,
        String genesisBlockHash,
        TransactionExecutor executor
    ) {
        this.worldState = worldState;
        this.lastBlockHash = genesisBlockHash;
        this.executor = executor;
        this.nextHeight = 1L;
    }

    /**
     * Orders txs by {@link Transaction#maxFeeOffer()} descending, then {@link Transaction#getNonce()}
     * ascending (deterministic tie-break).
     */
    public LedgerBlock appendBlock(List<Transaction> pendingTransactions) {
        List<Transaction> ordered = new ArrayList<>(pendingTransactions);
        ordered.sort(Transaction.FEE_PRIORITY.thenComparingLong(Transaction::getNonce));
        return appendTransactionsInOrder(ordered);
    }

    /**
     * Appends transactions in the exact order already decided by consensus.
     */
    public LedgerBlock appendDecidedBlock(List<Transaction> decidedOrder) {
        return appendTransactionsInOrder(new ArrayList<>(decidedOrder));
    }

    private LedgerBlock appendTransactionsInOrder(List<Transaction> ordered) {
        List<ExecutedTransaction> executed = new ArrayList<>();
        for (Transaction tx : ordered) {
            TransactionExecutionResult result = executor.execute(worldState, tx);
            executed.add(new ExecutedTransaction(tx, result));
        }

        long timestamp = System.currentTimeMillis();
        String blockHash = computeBlockHash(lastBlockHash, nextHeight, timestamp, executed);
        // Persist contract storage for the genesis-deployed IST Coin (and any other known contracts we support).
        var contractStorageHex =
            executor
                .getEvm()
                .snapshotSupportedContractStorage(
                    executor.getContractRegistry(),
                    worldState.asMapView().keySet()
                );
        LedgerBlock block = new LedgerBlock(
            blockHash,
            lastBlockHash,
            nextHeight,
            timestamp,
            executed,
            worldState.snapshot(),
            executor.getContractRegistry().snapshotRuntimeHex(),
            contractStorageHex
        );
        chain.add(block);
        lastBlockHash = blockHash;
        nextHeight++;
        return block;
    }

    public List<LedgerBlock> getBlocks() {
        return List.copyOf(chain);
    }

    public WorldState getWorldState() {
        return worldState;
    }

    public TransactionExecutor getExecutor() {
        return executor;
    }

    private static String computeBlockHash(
        String previousBlockHash,
        long height,
        long timestamp,
        List<ExecutedTransaction> txs
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(previousBlockHash.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(height).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
            for (ExecutedTransaction etx : txs) {
                Transaction tx = etx.getTransaction();
                digest.update(tx.getFrom().getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(tx.getTo()).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(tx.getNonce()).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(tx.getValue()).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(tx.getGasPrice()).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(tx.getGasLimit()).getBytes(StandardCharsets.UTF_8));
                digest.update(tx.getData());
                digest.update(
                    Boolean
                        .toString(etx.getResult().isSuccess())
                        .getBytes(StandardCharsets.UTF_8)
                );
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }
}
