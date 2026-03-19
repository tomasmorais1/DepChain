package depchain.blockchain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores deployed contract runtime bytecode for Step 3.
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
}
