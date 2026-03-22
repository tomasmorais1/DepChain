package depchain.blockchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 3 block format for local execution/persistence.
 *
 * <p>Account balances/nonces live in {@link #getState()}. Deployed contract <strong>runtime</strong>
 * bytecode is duplicated in {@link #getContractRuntimeHex()} so JSON round-trips restore the
 * {@link ContractRuntimeRegistry}; Besu {@link depchain.blockchain.evm.BesuEvmHelper} storage must
 * still be rebuilt (replay or contract-specific hooks — see README).
 */
public final class LedgerBlock {
    private final String blockHash;
    private final String previousBlockHash;
    private final long height;
    private final long timestamp;
    private final List<ExecutedTransaction> transactions;
    private final Map<String, WorldState.AccountSnapshot> state;
    /** Contract address → runtime bytecode as lowercase hex (no 0x). */
    private final Map<String, String> contractRuntimeHex;

    public LedgerBlock(
        String blockHash,
        String previousBlockHash,
        long height,
        long timestamp,
        List<ExecutedTransaction> transactions,
        Map<String, WorldState.AccountSnapshot> state,
        Map<String, String> contractRuntimeHex
    ) {
        this.blockHash = blockHash;
        this.previousBlockHash = previousBlockHash;
        this.height = height;
        this.timestamp = timestamp;
        this.transactions = transactions == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(transactions);
        this.state = state == null ? Collections.emptyMap() : state;
        this.contractRuntimeHex =
            contractRuntimeHex == null || contractRuntimeHex.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(contractRuntimeHex));
    }

    public String getBlockHash() {
        return blockHash;
    }

    public String getPreviousBlockHash() {
        return previousBlockHash;
    }

    public long getHeight() {
        return height;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public List<ExecutedTransaction> getTransactions() {
        return transactions;
    }

    public Map<String, WorldState.AccountSnapshot> getState() {
        return state;
    }

    /**
     * Deployed contracts known at this block: address → runtime bytecode (hex, no {@code 0x}).
     */
    public Map<String, String> getContractRuntimeHex() {
        return contractRuntimeHex;
    }
}
