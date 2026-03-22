package depchain.blockchain.evm;

import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.tracing.OperationTracer;

/**
 * Tracks gas consumed on the root {@link MessageFrame} using Besu's post-op remaining gas.
 * {@code gasUsed ≈ gasLimit - remainingGas} after execution (refunds already reflected in remaining).
 */
public final class GasCaptureTracer implements OperationTracer {

    private final long gasLimit;
    private long lastRootRemainingGas = -1;

    public GasCaptureTracer(long gasLimit) {
        if (gasLimit <= 0) {
            throw new IllegalArgumentException("gasLimit must be > 0");
        }
        this.gasLimit = gasLimit;
    }

    @Override
    public void tracePostExecution(
        MessageFrame frame,
        Operation.OperationResult operationResult
    ) {
        if (frame.getDepth() == 0) {
            lastRootRemainingGas = frame.getRemainingGas();
        }
    }

    /**
     * Gas units consumed by the execution (capped by {@code gasLimit}).
     */
    public long getGasUsed() {
        if (lastRootRemainingGas < 0) {
            return gasLimit;
        }
        long used = gasLimit - lastRootRemainingGas;
        if (used < 0) {
            return 0;
        }
        return Math.min(used, gasLimit);
    }
}
