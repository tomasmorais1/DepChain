package depchain.blockchain;

import java.util.List;

/**
 * Ordered candidate batch to be proposed through HotStuff.
 */
public final class BlockCandidate {

    private final List<PendingTransaction> transactions;
    private final long totalGasLimit;

    public BlockCandidate(List<PendingTransaction> transactions, long totalGasLimit) {
        this.transactions = transactions == null ? List.of() : List.copyOf(transactions);
        this.totalGasLimit = totalGasLimit;
    }

    public List<PendingTransaction> getTransactions() {
        return transactions;
    }

    public long getTotalGasLimit() {
        return totalGasLimit;
    }

    public boolean isEmpty() {
        return transactions.isEmpty();
    }

    @Override
    public String toString() {
        return "BlockCandidate{" +
            "transactions=" + transactions.size() +
            ", totalGasLimit=" + totalGasLimit +
            '}';
    }
}
