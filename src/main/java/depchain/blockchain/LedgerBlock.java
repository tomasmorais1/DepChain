package depchain.blockchain;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Step 3 block format for local execution/persistence.
 */
public final class LedgerBlock {
    private final String blockHash;
    private final String previousBlockHash;
    private final long height;
    private final long timestamp;
    private final List<ExecutedTransaction> transactions;
    private final Map<String, WorldState.AccountSnapshot> state;

    public LedgerBlock(
        String blockHash,
        String previousBlockHash,
        long height,
        long timestamp,
        List<ExecutedTransaction> transactions,
        Map<String, WorldState.AccountSnapshot> state
    ) {
        this.blockHash = blockHash;
        this.previousBlockHash = previousBlockHash;
        this.height = height;
        this.timestamp = timestamp;
        this.transactions = transactions == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(transactions);
        this.state = state == null ? Collections.emptyMap() : state;
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
}
