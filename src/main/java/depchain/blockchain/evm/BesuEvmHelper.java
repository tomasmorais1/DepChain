package depchain.blockchain.evm;

import depchain.blockchain.ContractRuntimeRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.jcajce.provider.digest.Keccak;
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
        Bytes32 slot = keccak256(preimage);
        UInt256 value = UInt256.valueOf(IstCoinBytecode.TOTAL_SUPPLY_UNITS);
        contract.setStorageValue(UInt256.fromHexString(slot.toHexString()), value);
    }

    /**
     * Writes one EVM storage word on a contract account (e.g. genesis {@code contracts[].storage}).
     * Slot and value are interpreted as unsigned 256-bit words: hex strings may use an {@code 0x} prefix
     * and are left-padded with zeros to 32 bytes.
     */
    public void putStorageHex(String contractHex, String slotHex, String valueHex) {
        Address contractAddr = parseAddress(contractHex);
        if (world.get(contractAddr) == null) {
            world.createAccount(contractAddr, 0, Wei.ZERO);
        }
        MutableAccount contract = (MutableAccount) world.get(contractAddr);
        UInt256 slot = uint256FromFlexibleHex(slotHex);
        UInt256 value = uint256FromFlexibleHex(valueHex);
        contract.setStorageValue(slot, value);
    }

    private static UInt256 uint256FromFlexibleHex(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.isEmpty()) {
            return UInt256.ZERO;
        }
        if (s.length() > 64) {
            throw new IllegalArgumentException("hex value longer than 32 bytes: " + hex);
        }
        if ((s.length() & 1) != 0) {
            s = "0" + s;
        }
        String padded = "0".repeat(64 - s.length()) + s;
        return UInt256.fromHexString("0x" + padded);
    }

    /**
     * Snapshots contract storage for supported contracts (currently: IST Coin) into a JSON-friendly
     * format. This is a pragmatic approach to satisfy the Stage 2 requirement that blocks represent
     * the world state after executing transactions.
     *
     * <p>For IST Coin, we persist:
     * <ul>
     *   <li>{@code _balances[addr]} for a bounded set of known addresses</li>
     *   <li>{@code _allowances[owner][spender]} for the same bounded set</li>
     * </ul>
     *
     * <p>Keys and values are 32-byte hex strings (64 lowercase hex chars, no {@code 0x}).
     */
    public Map<String, Map<String, String>> snapshotSupportedContractStorage(
        ContractRuntimeRegistry reg,
        Set<String> knownAddresses
    ) {
        if (reg == null) return Map.of();
        Map<String, byte[]> runtimes = reg.snapshotBytes();
        if (runtimes.isEmpty()) return Map.of();

        Set<String> addrs = boundKnownAddresses(knownAddresses, 32);
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        byte[] istRuntime = IstCoinBytecode.readRuntimeBytecode();
        for (Map.Entry<String, byte[]> e : runtimes.entrySet()) {
            String contractHex = e.getKey();
            byte[] runtime = e.getValue();
            if (runtime == null) continue;
            if (!java.util.Arrays.equals(runtime, istRuntime)) {
                continue; // only IST supported for storage snapshotting
            }
            Map<String, String> slots = snapshotIstStorageSlots(contractHex, addrs);
            if (!slots.isEmpty()) {
                out.put(contractHex, Map.copyOf(slots));
            }
        }
        return Map.copyOf(out);
    }

    private static Set<String> boundKnownAddresses(Set<String> known, int max) {
        if (known == null || known.isEmpty() || max <= 0) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String a : known) {
            if (a == null) continue;
            String s = a.trim();
            if (!s.startsWith("0x") && !s.startsWith("0X")) continue;
            if (s.length() != 42) continue;
            out.add(s);
            if (out.size() >= max) break;
        }
        return Set.copyOf(out);
    }

    private Map<String, String> snapshotIstStorageSlots(String contractHex, Set<String> addresses) {
        Address contractAddr = parseAddress(contractHex);
        var acc = world.get(contractAddr);
        if (acc == null) return Map.of();

        Map<String, String> out = new LinkedHashMap<>();
        // balances: mapping(address => uint256) at slot 0
        for (String a : addresses) {
            Bytes32 slot = istBalanceSlot(a);
            UInt256 v = acc.getStorageValue(UInt256.fromHexString(slot.toHexString()));
            if (!v.isZero()) {
                out.put(normalize32Hex(slot.toHexString()), normalize32Hex(v.toHexString()));
            }
        }
        // allowances: mapping(address => mapping(address => uint256)) at slot 1
        if (!addresses.isEmpty()) {
            List<String> list = List.copyOf(addresses);
            for (String owner : list) {
                for (String spender : list) {
                    Bytes32 slot = istAllowanceSlot(owner, spender);
                    UInt256 v = acc.getStorageValue(UInt256.fromHexString(slot.toHexString()));
                    if (!v.isZero()) {
                        out.put(normalize32Hex(slot.toHexString()), normalize32Hex(v.toHexString()));
                    }
                }
            }
        }
        return out;
    }

    /** Applies a persisted storage snapshot back into the Besu world. */
    public void applyContractStorageHexSnapshot(
        String contractHex,
        Map<String, String> slotToValueHex
    ) {
        if (contractHex == null || contractHex.isBlank()) return;
        if (slotToValueHex == null || slotToValueHex.isEmpty()) return;
        Address addr = parseAddress(contractHex);
        if (world.get(addr) == null) {
            world.createAccount(addr, 0, Wei.ZERO);
        }
        MutableAccount acc = (MutableAccount) world.get(addr);
        for (Map.Entry<String, String> e : slotToValueHex.entrySet()) {
            String k = normalize32Hex(e.getKey());
            String v = normalize32Hex(e.getValue());
            acc.setStorageValue(UInt256.fromHexString("0x" + k), UInt256.fromHexString("0x" + v));
        }
    }

    /** Storage slot for IST {@code _balances[account]} mapping at slot 0. */
    public static Bytes32 istBalanceSlot(String accountHex) {
        Bytes key = padAddressTo32(accountHex);
        Bytes preimage = Bytes.concatenate(key, UInt256.ZERO.toBytes());
        return keccak256(preimage);
    }

    /** Storage slot for IST {@code _allowances[owner][spender]} nested mapping at slot 1. */
    public static Bytes32 istAllowanceSlot(String ownerHex, String spenderHex) {
        Bytes owner = padAddressTo32(ownerHex);
        Bytes outerPre = Bytes.concatenate(owner, UInt256.ONE.toBytes());
        Bytes32 outer = keccak256(outerPre);
        Bytes spender = padAddressTo32(spenderHex);
        Bytes finalPre = Bytes.concatenate(spender, outer);
        return keccak256(finalPre);
    }

    private static String normalize32Hex(String hexMaybe0x) {
        String s = hexMaybe0x == null ? "" : hexMaybe0x.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        s = s.toLowerCase();
        if (s.length() > 64) {
            s = s.substring(s.length() - 64);
        }
        return "0".repeat(Math.max(0, 64 - s.length())) + s;
    }

    /**
     * Reads IST Coin {@code _balances[account]} directly from storage, matching
     * {@code balanceOf(address)} return semantics (uint256).
     * <p>Layout: mapping at slot 0 → keccak256(pad(address) || uint256(0)).
     */
    public Bytes readIstBalanceOfReturnData(String contractHex, String accountHex) {
        Address contractAddr = parseAddress(contractHex);
        var acc = world.get(contractAddr);
        if (acc == null) {
            return Bytes.EMPTY;
        }
        Bytes key = padAddressTo32(accountHex);
        Bytes preimage = Bytes.concatenate(key, UInt256.ZERO.toBytes());
        Bytes32 slot = keccak256(preimage);
        UInt256 value =
            acc.getStorageValue(UInt256.fromHexString(slot.toHexString()));
        return value.toBytes();
    }

    private static Bytes32 keccak256(Bytes input) {
        byte[] in = input == null ? new byte[0] : input.toArrayUnsafe();
        byte[] out = new Keccak.Digest256().digest(in);
        return Bytes32.wrap(out);
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
