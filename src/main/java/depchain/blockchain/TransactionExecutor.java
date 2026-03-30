package depchain.blockchain;

import depchain.blockchain.evm.BesuEvmHelper;
import depchain.blockchain.evm.EvmCallResult;
import depchain.blockchain.evm.IstCoinBytecode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Step 3 transaction executor: native transfers, fees, and Besu EVM for contracts.
 *
 * <p>Native transfers use Ethereum intrinsic gas 21,000 (no EVM opcode execution). Contract
 * calls and deploy fee {@code gas_used} values come from Besu {@link org.hyperledger.besu.evm.fluent.EVMExecutor}
 * via {@link depchain.blockchain.evm.GasCaptureTracer}.
 */
public final class TransactionExecutor {
    /**
     * Intrinsic gas for a simple native value transfer (no contract code execution), same order of
     * magnitude as Ethereum.
     */
    public static final long NATIVE_TRANSFER_INTRINSIC_GAS = 21_000L;

    /** Early failure paths that do not enter the EVM (e.g. unknown contract). */
    private static final long INTRINSIC_FAILED_CALL_GAS = 21_000L;

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

    /**
     * Deterministic contract address derivation used by DepChain.
     * <p>This is not Ethereum CREATE; it is a project-specific stable derivation so all replicas
     * compute the same address from the same deployer + nonce.
     */
    public static String deriveContractAddress(String from, long nonce) {
        return deriveContractAddressInternal(from, nonce);
    }

    private TransactionExecutionResult executeContractDeployment(
        WorldState state,
        Transaction tx
    ) {
        WorldState.AccountState sender = state.get(tx.getFrom());
        if (sender == null) {
            return new TransactionExecutionResult(false, false, 0, 0, "sender does not exist");
        }
        if (sender.getNonce() != tx.getNonce()) {
            return new TransactionExecutionResult(false, false, 0, 0, "invalid nonce");
        }
        if (tx.getData().length == 0) {
            return new TransactionExecutionResult(false, false, 0, 0, "empty deployment bytecode");
        }
        long maxFee = mulOrMax(tx.getGasPrice(), tx.getGasLimit());
        if (sender.getBalance() < tx.getValue() + maxFee) {
            return new TransactionExecutionResult(false, false, 0, 0, "insufficient balance for fee");
        }

        long gasUsed =
            BesuEvmHelper.measureContractCreationGas(
                tx.getData(),
                tx.getFrom(),
                tx.getNonce(),
                tx.getGasLimit()
            );
        long gasUsedForFee = Math.min(gasUsed, tx.getGasLimit());
        String contractAddress = deriveContractAddress(tx.getFrom(), tx.getNonce());

        long feeCharged = computeFeeCharged(tx, gasUsedForFee);

        sender.setBalance(sender.getBalance() - feeCharged);
        sender.setNonce(sender.getNonce() + 1);
        syncAccountToEvm(state, tx.getFrom());
        byte[] runtimeBytecode;
        if (IstCoinBytecode.isKnownCreationBytecode(tx.getData())) {
            runtimeBytecode = IstCoinBytecode.readRuntimeBytecode();
            evm.setContractCode(contractAddress, runtimeBytecode);
            contractRegistry.put(contractAddress, runtimeBytecode);
            state.getOrCreate(contractAddress);
            evm.upsertAccount(contractAddress, 0, 0);
            evm.seedIstCoinBalancesAfterDeploy(tx.getFrom(), contractAddress);
        } else {
            runtimeBytecode = tx.getData();
            evm.setContractCode(contractAddress, runtimeBytecode);
            contractRegistry.put(contractAddress, runtimeBytecode);
            state.getOrCreate(contractAddress);
            evm.upsertAccount(contractAddress, 0, 0);
        }
        return new TransactionExecutionResult(
            true,
            true,
            gasUsedForFee,
            feeCharged,
            null,
            contractAddress
        );
    }

    private TransactionExecutionResult executeContractCall(WorldState state, Transaction tx) {
        WorldState.AccountState sender = state.get(tx.getFrom());
        if (sender == null) {
            return new TransactionExecutionResult(false, false, 0, 0, "sender does not exist");
        }
        if (sender.getNonce() != tx.getNonce()) {
            return new TransactionExecutionResult(false, false, 0, 0, "invalid nonce");
        }
        long maxFee = mulOrMax(tx.getGasPrice(), tx.getGasLimit());
        if (sender.getBalance() < tx.getValue() + maxFee) {
            return new TransactionExecutionResult(false, false, 0, 0, "insufficient balance for fee");
        }

        if (!contractRegistry.contains(tx.getTo())) {
            return failWithChargedGas(
                state,
                tx,
                INTRINSIC_FAILED_CALL_GAS,
                "unknown contract address"
            );
        }
        if (sender.getBalance() < tx.getValue()) {
            return new TransactionExecutionResult(false, false, 0, 0, "insufficient balance for call value");
        }

        syncAccountToEvm(state, tx.getFrom());
        syncAccountToEvm(state, tx.getTo());
        long gasUsed;
        try {
            EvmCallResult r = evm.callMetered(tx.getFrom(), tx.getTo(), tx.getData(), tx.getGasLimit());
            gasUsed = Math.min(r.gasUsed(), tx.getGasLimit());
            long feeCharged = computeFeeCharged(tx, gasUsed);
            sender.setBalance(sender.getBalance() - feeCharged);
            sender.setNonce(sender.getNonce() + 1);
            if (tx.getValue() > 0) {
                WorldState.AccountState fromAcc = state.get(tx.getFrom());
                WorldState.AccountState toAcc = state.getOrCreate(tx.getTo());
                fromAcc.setBalance(fromAcc.getBalance() - tx.getValue());
                toAcc.setBalance(toAcc.getBalance() + tx.getValue());
            }
            return new TransactionExecutionResult(true, true, gasUsed, feeCharged, null);
        } catch (RuntimeException ex) {
            gasUsed = tx.getGasLimit();
            long feeCharged = computeFeeCharged(tx, gasUsed);
            sender.setBalance(sender.getBalance() - feeCharged);
            sender.setNonce(sender.getNonce() + 1);
            return new TransactionExecutionResult(
                false,
                false,
                gasUsed,
                feeCharged,
                "evm call failed: " + ex.getMessage()
            );
        }
    }

    private TransactionExecutionResult failWithChargedGas(
        WorldState state,
        Transaction tx,
        long gasUsed,
        String error
    ) {
        WorldState.AccountState sender = state.get(tx.getFrom());
        long feeCharged = computeFeeCharged(tx, gasUsed);
        sender.setBalance(sender.getBalance() - feeCharged);
        sender.setNonce(sender.getNonce() + 1);
        return new TransactionExecutionResult(false, false, gasUsed, feeCharged, error);
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

        long gasUsed = NATIVE_TRANSFER_INTRINSIC_GAS;
        long feeCharged = computeFeeCharged(tx, gasUsed);

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

    /** fee = min(gas_price * gas_limit, gas_price * gas_used), with overflow guard. */
    private static long computeFeeCharged(Transaction tx, long gasUsed) {
        long cap = mulOrMax(tx.getGasPrice(), tx.getGasLimit());
        long atUsed = mulOrMax(tx.getGasPrice(), gasUsed);
        return Math.min(cap, atUsed);
    }

    private static long mulOrMax(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static String deriveContractAddressInternal(String from, long nonce) {
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
}
