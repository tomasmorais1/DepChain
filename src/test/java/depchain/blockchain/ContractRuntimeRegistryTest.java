package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractRuntimeRegistryTest {

    @Test
    void snapshot_hex_round_trips_through_apply_snapshot() {
        ContractRuntimeRegistry reg = new ContractRuntimeRegistry();
        byte[] code = new byte[] { 0x60, 0x00, (byte) 0xff };
        reg.put("0xcccccccccccccccccccccccccccccccccccccccc", code);

        Map<String, String> hex = reg.snapshotRuntimeHex();
        assertEquals("6000ff", hex.get("0xcccccccccccccccccccccccccccccccccccccccc"));

        ContractRuntimeRegistry restored = new ContractRuntimeRegistry();
        restored.applyRuntimeHexSnapshot(hex);
        assertArrayEquals(code, restored.get("0xcccccccccccccccccccccccccccccccccccccccc"));
    }

    @Test
    void empty_snapshot_is_unmodifiable_empty() {
        ContractRuntimeRegistry reg = new ContractRuntimeRegistry();
        Map<String, String> hex = reg.snapshotRuntimeHex();
        assertTrue(hex.isEmpty());
    }
}
