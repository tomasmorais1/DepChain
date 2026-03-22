package depchain.blockchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores deployed contract runtime bytecode for Step 3.
 *
 * <p>Bytecode is also snapshotted into {@link LedgerBlock#getContractRuntimeHex()} for JSON
 * persistence; restore with {@link #applyRuntimeHexSnapshot(Map)} and sync the Besu world via
 * {@link depchain.blockchain.evm.BesuEvmHelper#applyCodesFromRegistry(ContractRuntimeRegistry)}.
 */
public final class ContractRuntimeRegistry {
    private final Map<String, byte[]> runtimeByAddress = new ConcurrentHashMap<>();

    public void put(String address, byte[] runtime) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("contract address cannot be null/blank");
        }
        runtimeByAddress.put(address, runtime == null ? new byte[0] : runtime.clone());
    }

    public byte[] get(String address) {
        byte[] value = runtimeByAddress.get(address);
        return value == null ? null : value.clone();
    }

    public boolean contains(String address) {
        return runtimeByAddress.containsKey(address);
    }

    /**
     * Snapshot for {@link LedgerBlock}: contract address → lowercase hex (no {@code 0x} prefix).
     */
    public Map<String, String> snapshotRuntimeHex() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : runtimeByAddress.entrySet()) {
            out.put(e.getKey(), bytesToHex(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * Restores registry from a persisted block snapshot (typically after {@link BlockJsonStore#load}).
     */
    public void applyRuntimeHexSnapshot(Map<String, String> hexByAddress) {
        if (hexByAddress == null || hexByAddress.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : hexByAddress.entrySet()) {
            put(e.getKey(), hexToBytes(e.getValue()));
        }
    }

    /** Immutable copy of address → runtime bytes (for Besu sync). */
    public Map<String, byte[]> snapshotBytes() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : runtimeByAddress.entrySet()) {
            out.put(e.getKey(), e.getValue().clone());
        }
        return Collections.unmodifiableMap(out);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.isEmpty()) {
            return new byte[0];
        }
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("odd-length hex string");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
