package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class TxBatchPayloadTest {

    @Test
    void round_trip_preserves_order_and_values() {
        TxBatchPayload payload = new TxBatchPayload(
            List.of(
                new TxBatchPayload.TxItem(10L, "tx-a"),
                new TxBatchPayload.TxItem(11L, "tx-b")
            )
        );
        TxBatchPayload decoded = TxBatchPayload.fromBytes(payload.toBytes());
        assertNotNull(decoded);
        assertEquals(2, decoded.getItems().size());
        assertEquals(10L, decoded.getItems().get(0).requestId());
        assertEquals("tx-a", decoded.getItems().get(0).command());
        assertEquals(11L, decoded.getItems().get(1).requestId());
        assertEquals("tx-b", decoded.getItems().get(1).command());
    }

    @Test
    void rejects_legacy_raw_payload() {
        byte[] legacy = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertNull(TxBatchPayload.fromBytes(legacy));
    }
}
