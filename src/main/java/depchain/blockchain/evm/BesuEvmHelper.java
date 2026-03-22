package depchain.blockchain.evm;

import depchain.blockchain.ContractRuntimeRegistry;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;

/**
 * Hyperledger Besu EVM wrapper for Step 3 contract deploy and call.
 * DepCoin balances are mapped 1:1 to {@link Wei} smallest units for execution.
 */
public final class BesuEvmHelper {
    private final SimpleWorld world = new SimpleWorld();

    public SimpleWorld getWorld() {
        return world;
    }

    public static Address parseAddress(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 40) {
            throw new IllegalArgumentException("Invalid address length: " + hex);
        }
        return Address.fromHexString(s);
    }

    public static Wei balanceToWei(long depCoinUnits) {
        if (depCoinUnits < 0) {
            throw new IllegalArgumentException("balance cannot be negative");
        }
        return Wei.of(depCoinUnits);
    }

    public void upsertAccount(String hex, long nonce, long balanceDepCoin) {
        Address addr = parseAddress(hex);
        Wei wei = balanceToWei(balanceDepCoin);
        if (world.get(addr) == null) {
            world.createAccount(addr, nonce, wei);
        } else {
            MutableAccount acc = (MutableAccount) world.get(addr);
            acc.setNonce(nonce);
            acc.setBalance(wei);
        }
    }

    /**
     * Sets runtime bytecode on an existing account (contract deployment result).
     */
    public void setContractCode(String contractHex, byte[] runtimeBytecode) {
        Address addr = parseAddress(contractHex);
        if (world.get(addr) == null) {
            world.createAccount(addr, 0, Wei.ZERO);
        }
        MutableAccount acc = (MutableAccount) world.get(addr);
        acc.setCode(Bytes.of(runtimeBytecode));
    }

    /**
     * Seeds {@code _balances[deployer]} for IST Coin after deploy, matching the Solidity
     * constructor minting {@link IstCoinBytecode#TOTAL_SUPPLY_UNITS} to {@code msg.sender}.
     * Storage slot: keccak256(abi.encode(deployer, uint256(0))) for mapping at slot 0.
     */
    public void seedIstCoinBalancesAfterDeploy(String deployerHex, String contractHex) {
        Address contractAddr = parseAddress(contractHex);
        var acc = world.get(contractAddr);
        if (acc == null) {
            throw new IllegalStateException("contract account missing: " + contractHex);
        }
        MutableAccount contract = (MutableAccount) acc;
        Bytes deployerPadded = padAddressTo32(deployerHex);
        Bytes preimage = Bytes.concatenate(deployerPadded, UInt256.ZERO.toBytes());
        Bytes32 slot = Hash.keccak256(preimage);
        UInt256 value = UInt256.valueOf(IstCoinBytecode.TOTAL_SUPPLY_UNITS);
        contract.setStorageValue(UInt256.fromHexString(slot.toHexString()), value);
    }

    private static Bytes padAddressTo32(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 40) {
            throw new IllegalArgumentException("expected 20-byte address hex: " + hex);
        }
        Bytes addr20 = Bytes.fromHexString(s);
        return Bytes.concatenate(Bytes.wrap(new byte[12]), addr20);
    }

    /**
     * Executes a contract call; returns the EVM output bytes (may be empty).
     */
    public Bytes call(String fromHex, String toHex, byte[] callData) {
        return callMetered(fromHex, toHex, callData, 1_000_000_000L).output();
    }

    /**
     * Contract call with explicit gas limit and metering via {@link GasCaptureTracer}.
     */
    public EvmCallResult callMetered(
        String fromHex,
        String toHex,
        byte[] callData,
        long gasLimit
    ) {
        Address from = parseAddress(fromHex);
        Address to = parseAddress(toHex);
        MutableAccount contract = (MutableAccount) world.get(to);
        if (contract == null || contract.getCode().isEmpty()) {
            throw new IllegalStateException("no contract code at " + toHex);
        }
        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.gas(gasLimit);
        executor.code(contract.getCode());
        executor.sender(from);
        executor.receiver(to);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();
        executor.callData(Bytes.of(callData == null ? new byte[0] : callData));
        GasCaptureTracer tracer = new GasCaptureTracer(gasLimit);
        executor.tracer(tracer);
        Bytes out = executor.execute();
        return new EvmCallResult(out, tracer.getGasUsed());
    }

    /**
     * Measures gas for contract creation (creation bytecode) in an isolated {@link SimpleWorld}.
     * Used for fee calculation when the DepChain deploy path does not run a full CREATE on the live world.
     */
    public static long measureContractCreationGas(
        byte[] creationBytes,
        String fromHex,
        long senderNonce,
        long gasLimit
    ) {
        if (creationBytes == null || creationBytes.length == 0) {
            return 0L;
        }
        SimpleWorld w = new SimpleWorld();
        Address from = parseAddress(fromHex);
        w.createAccount(from, senderNonce, Wei.of(1_000_000_000_000L));
        EVMExecutor exec = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        exec.gas(gasLimit);
        exec.sender(from);
        exec.messageFrameType(MessageFrame.Type.CONTRACT_CREATION);
        exec.code(Bytes.wrap(creationBytes));
        exec.worldUpdater(w.updater());
        exec.commitWorldState(true);
        GasCaptureTracer tracer = new GasCaptureTracer(gasLimit);
        exec.tracer(tracer);
        try {
            exec.execute();
        } catch (RuntimeException e) {
            return gasLimit;
        }
        return tracer.getGasUsed();
    }

    /**
     * Reinstalls runtime bytecode on the Besu {@link SimpleWorld} from a registry (e.g. after
     * {@link depchain.blockchain.BlockJsonStore#load} + {@link ContractRuntimeRegistry#applyRuntimeHexSnapshot}).
     * Contract storage slots are not persisted in Step 3 — replay txs or re-seed as needed (IST).
     */
    public void applyCodesFromRegistry(ContractRuntimeRegistry reg) {
        for (Map.Entry<String, byte[]> e : reg.snapshotBytes().entrySet()) {
            setContractCode(e.getKey(), e.getValue());
        }
    }

    public long readBalanceDepCoin(String hex) {
        Address addr = parseAddress(hex);
        var acc = world.get(addr);
        if (acc == null) {
            return 0;
        }
        return acc.getBalance().toBigInteger().longValueExact();
    }
}
