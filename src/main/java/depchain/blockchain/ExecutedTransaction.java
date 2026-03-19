package depchain.blockchain;

/**
 * Transaction plus execution result for persisted blocks.
 */
public final class ExecutedTransaction {
    private final Transaction transaction;
    private final TransactionExecutionResult result;

    public ExecutedTransaction(
        Transaction transaction,
        TransactionExecutionResult result
    ) {
        this.transaction = transaction;
        this.result = result;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public TransactionExecutionResult getResult() {
        return result;
    }
}
