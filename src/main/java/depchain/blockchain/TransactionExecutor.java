package depchain.blockchain;

import depchain.blockchain.evm.BesuEvmHelper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.tuweni.bytes.Bytes;

/**
 * Step 3 transaction executor: native transfers, fees, and Besu EVM for contracts.
 */
public final class TransactionExecutor {
    /** Simplified fixed gas for native transfer. */
    public static final long NATIVE_TRANSFER_GAS_USED = 21_000L;
    public static final long CONTRACT_DEPLOY_GAS_USED = 120_000L;
    public static final long CONTRACT_CALL_GAS_USED = 45_000L;

    private final ContractRuntimeRegistry contractRegistry;
    private final BesuEvmHelper evm;

    public TransactionExecutor() {
        this(new ContractRuntimeRegistry(), new BesuEvmHelper());
    }

    public TransactionExecutor(ContractRuntimeRegistry contractRegistry) {
        this(contractRegistry, new BesuEvmHelper());
    }

    public TransactionExecutor(ContractRuntimeRegistry contractRegistry, BesuEvmHelper evm) {
        this.contractRegistry = contractRegistry;
        this.evm = evm;
    }

    public ContractRuntimeRegistry getContractRegistry() {
        return contractRegistry;
    }

    public BesuEvmHelper getEvm() {
        return evm;
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
        syncAccountToEvm(state, tx.getFrom());
        evm.setContractCode(contractAddress, tx.getData());
        contractRegistry.put(contractAddress, tx.getData());
        state.getOrCreate(contractAddress);
        evm.upsertAccount(contractAddress, 0, 0);
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
        syncAccountToEvm(state, tx.getFrom());
        syncAccountToEvm(state, tx.getTo());
        try {
            evm.call(tx.getFrom(), tx.getTo(), tx.getData());
            // Ledger DepCoin balances are authoritative; sync EVM wei for CALL but apply
            // native value transfer on WorldState (Besu SimpleWorld balance != our ledger).
            if (tx.getValue() > 0) {
                WorldState.AccountState fromAcc = state.get(tx.getFrom());
                WorldState.AccountState toAcc = state.getOrCreate(tx.getTo());
                fromAcc.setBalance(fromAcc.getBalance() - tx.getValue());
                toAcc.setBalance(toAcc.getBalance() + tx.getValue());
            }
            return new TransactionExecutionResult(
                true,
                true,
                CONTRACT_CALL_GAS_USED,
                pre.feeCharged,
                null
            );
        } catch (RuntimeException ex) {
            return new TransactionExecutionResult(
                false,
                false,
                CONTRACT_CALL_GAS_USED,
                pre.feeCharged,
                "evm call failed: " + ex.getMessage()
            );
        }
    }

    private void syncAccountToEvm(WorldState state, String address) {
        WorldState.AccountState acc = state.get(address);
        if (acc == null) {
            return;
        }
        evm.upsertAccount(address, acc.getNonce(), acc.getBalance());
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
