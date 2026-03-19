package depchain.blockchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Step 3 transaction executor (first slice):
 * native transfers + gas charging semantics.
 */
public final class TransactionExecutor {
    /** Simplified fixed gas for native transfer in this first Step 3 slice. */
    public static final long NATIVE_TRANSFER_GAS_USED = 21_000L;
    public static final long CONTRACT_DEPLOY_GAS_USED = 120_000L;
    public static final long CONTRACT_CALL_GAS_USED = 45_000L;

    private final ContractRuntimeRegistry contractRegistry;

    public TransactionExecutor() {
        this(new ContractRuntimeRegistry());
    }

    public TransactionExecutor(ContractRuntimeRegistry contractRegistry) {
        this.contractRegistry = contractRegistry;
    }

    public ContractRuntimeRegistry getContractRegistry() {
        return contractRegistry;
    }

    public TransactionExecutionResult execute(WorldState state, Transaction tx) {
        if (tx.isContractDeployment()) {
            return executeContractDeployment(state, tx);
        }
        if (tx.isContractCall()) {
            return executeContractCall(state, tx);
        }
        return executeNativeTransfer(state, tx);
    }

    private TransactionExecutionResult executeContractDeployment(
        WorldState state,
        Transaction tx
    ) {
        PreChargeResult pre = preCharge(state, tx, CONTRACT_DEPLOY_GAS_USED);
        if (!pre.proceed) {
            return pre.result;
        }
        if (tx.getData().length == 0) {
            return new TransactionExecutionResult(
                false,
                false,
                CONTRACT_DEPLOY_GAS_USED,
                pre.feeCharged,
                "empty deployment bytecode"
            );
        }
        String contractAddress = deriveContractAddress(tx.getFrom(), tx.getNonce());
        contractRegistry.put(contractAddress, tx.getData());
        state.getOrCreate(contractAddress);
        return new TransactionExecutionResult(
            true,
            true,
            CONTRACT_DEPLOY_GAS_USED,
            pre.feeCharged,
            null,
            contractAddress
        );
    }

    private TransactionExecutionResult executeContractCall(WorldState state, Transaction tx) {
        PreChargeResult pre = preCharge(state, tx, CONTRACT_CALL_GAS_USED);
        if (!pre.proceed) {
            return pre.result;
        }
        if (!contractRegistry.contains(tx.getTo())) {
            return new TransactionExecutionResult(
                false,
                false,
                CONTRACT_CALL_GAS_USED,
                pre.feeCharged,
                "unknown contract address"
            );
        }
        WorldState.AccountState sender = state.get(tx.getFrom());
        if (sender.getBalance() < tx.getValue()) {
            return new TransactionExecutionResult(
                false,
                false,
                CONTRACT_CALL_GAS_USED,
                pre.feeCharged,
                "insufficient balance for call value"
            );
        }
        WorldState.AccountState contract = state.getOrCreate(tx.getTo());
        sender.setBalance(sender.getBalance() - tx.getValue());
        contract.setBalance(contract.getBalance() + tx.getValue());
        return new TransactionExecutionResult(
            true,
            true,
            CONTRACT_CALL_GAS_USED,
            pre.feeCharged,
            null
        );
    }

    private TransactionExecutionResult executeNativeTransfer(
        WorldState state,
        Transaction tx
    ) {
        WorldState.AccountState sender = state.get(tx.getFrom());
        if (sender == null) {
            return new TransactionExecutionResult(
                false,
                false,
                0,
                0,
                "sender does not exist"
            );
        }
        if (sender.getNonce() != tx.getNonce()) {
            return new TransactionExecutionResult(
                false,
                false,
                0,
                0,
                "invalid nonce"
            );
        }

        long gasUsed = NATIVE_TRANSFER_GAS_USED;
        long feeByLimit = tx.getGasPrice() * tx.getGasLimit();
        long feeByUsed = tx.getGasPrice() * gasUsed;
        long feeCharged = Math.min(feeByLimit, feeByUsed);

        if (sender.getBalance() < feeCharged) {
            return new TransactionExecutionResult(
                false,
                false,
                gasUsed,
                0,
                "insufficient balance for fee"
            );
        }

        sender.setBalance(sender.getBalance() - feeCharged);
        sender.setNonce(sender.getNonce() + 1);

        if (gasUsed > tx.getGasLimit()) {
            return new TransactionExecutionResult(
                false,
                false,
                gasUsed,
                feeCharged,
                "out of gas"
            );
        }

        if (sender.getBalance() < tx.getValue()) {
            return new TransactionExecutionResult(
                false,
                false,
                gasUsed,
                feeCharged,
                "insufficient balance for value transfer"
            );
        }

        WorldState.AccountState recipient = state.getOrCreate(tx.getTo());
        sender.setBalance(sender.getBalance() - tx.getValue());
        recipient.setBalance(recipient.getBalance() + tx.getValue());

        return new TransactionExecutionResult(true, true, gasUsed, feeCharged, null);
    }

    private PreChargeResult preCharge(WorldState state, Transaction tx, long gasUsed) {
        WorldState.AccountState sender = state.get(tx.getFrom());
        if (sender == null) {
            return PreChargeResult.stop(
                new TransactionExecutionResult(
                    false,
                    false,
                    0,
                    0,
                    "sender does not exist"
                ),
                0
            );
        }
        if (sender.getNonce() != tx.getNonce()) {
            return PreChargeResult.stop(
                new TransactionExecutionResult(
                    false,
                    false,
                    0,
                    0,
                    "invalid nonce"
                ),
                0
            );
        }

        long feeByLimit = tx.getGasPrice() * tx.getGasLimit();
        long feeByUsed = tx.getGasPrice() * gasUsed;
        long feeCharged = Math.min(feeByLimit, feeByUsed);

        if (sender.getBalance() < feeCharged) {
            return PreChargeResult.stop(
                new TransactionExecutionResult(
                    false,
                    false,
                    gasUsed,
                    0,
                    "insufficient balance for fee"
                ),
                0
            );
        }

        sender.setBalance(sender.getBalance() - feeCharged);
        sender.setNonce(sender.getNonce() + 1);

        if (gasUsed > tx.getGasLimit()) {
            return PreChargeResult.stop(
                new TransactionExecutionResult(
                    false,
                    false,
                    gasUsed,
                    feeCharged,
                    "out of gas"
                ),
                feeCharged
            );
        }
        return PreChargeResult.continueExecution(feeCharged);
    }

    private static String deriveContractAddress(String from, long nonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(from.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(nonce).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder("0x");
            for (int i = hash.length - 20; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    private static final class PreChargeResult {
        private final boolean proceed;
        private final long feeCharged;
        private final TransactionExecutionResult result;

        private PreChargeResult(
            boolean proceed,
            long feeCharged,
            TransactionExecutionResult result
        ) {
            this.proceed = proceed;
            this.feeCharged = feeCharged;
            this.result = result;
        }

        private static PreChargeResult continueExecution(long feeCharged) {
            return new PreChargeResult(true, feeCharged, null);
        }

        private static PreChargeResult stop(
            TransactionExecutionResult result,
            long feeCharged
        ) {
            return new PreChargeResult(false, feeCharged, result);
        }
    }
}
