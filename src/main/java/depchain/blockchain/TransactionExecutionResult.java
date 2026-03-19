package depchain.blockchain;

/**
 * Execution output for Step 3 transaction processing.
 */
public final class TransactionExecutionResult {
    private final boolean success;
    private final boolean stateApplied;
    private final long gasUsed;
    private final long feeCharged;
    private final String error;
    private final String createdContractAddress;

    public TransactionExecutionResult(
        boolean success,
        boolean stateApplied,
        long gasUsed,
        long feeCharged,
        String error,
        String createdContractAddress
    ) {
        this.success = success;
        this.stateApplied = stateApplied;
        this.gasUsed = gasUsed;
        this.feeCharged = feeCharged;
        this.error = error;
        this.createdContractAddress = createdContractAddress;
    }

    public TransactionExecutionResult(
        boolean success,
        boolean stateApplied,
        long gasUsed,
        long feeCharged,
        String error
    ) {
        this(success, stateApplied, gasUsed, feeCharged, error, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isStateApplied() {
        return stateApplied;
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public long getFeeCharged() {
        return feeCharged;
    }

    public String getError() {
        return error;
    }

    public String getCreatedContractAddress() {
        return createdContractAddress;
    }
}
