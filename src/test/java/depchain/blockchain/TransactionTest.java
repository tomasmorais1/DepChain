package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionTest {

    @Test
    void rejects_zero_or_negative_gas_fields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new Transaction("0xabc", "0xdef", 0, 1, 0, 21000, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Transaction("0xabc", "0xdef", 0, 1, 1, 0, null)
        );
    }

    @Test
    void identifies_deploy_and_call_transactions() {
        Transaction deploy = new Transaction(
            "0xabc",
            null,
            0,
            0,
            1,
            500000,
            new byte[] { 0x01 }
        );
        Transaction call = new Transaction(
            "0xabc",
            "0xdef",
            1,
            0,
            1,
            50000,
            new byte[] { 0x12, 0x34 }
        );
        Transaction transfer = new Transaction(
            "0xabc",
            "0xdef",
            2,
            10,
            1,
            21000,
            null
        );

        assertTrue(deploy.isContractDeployment());
        assertFalse(deploy.isContractCall());
        assertFalse(call.isContractDeployment());
        assertTrue(call.isContractCall());
        assertFalse(transfer.isContractDeployment());
        assertFalse(transfer.isContractCall());
    }

    @Test
    void orders_by_max_fee_offer_gas_price_times_gas_limit_descending() {
        Transaction low = new Transaction("0x1", "0x2", 0, 1, 1, 21000, null);
        Transaction high = new Transaction("0x3", "0x4", 0, 1, 10, 21000, null);
        Transaction mid = new Transaction("0x5", "0x6", 0, 1, 5, 21000, null);

        List<Transaction> txs = new ArrayList<>(List.of(low, high, mid));
        txs.sort(Transaction.FEE_PRIORITY);

        assertEquals(10 * 21000L, txs.get(0).maxFeeOffer());
        assertEquals(5 * 21000L, txs.get(1).maxFeeOffer());
        assertEquals(1 * 21000L, txs.get(2).maxFeeOffer());
    }

    @Test
    void higher_gas_price_does_not_always_win_product_decides() {
        // B has higher gasPrice but A has higher gasPrice * gasLimit
        Transaction a = new Transaction("0x1", "0x2", 0, 1, 2, 100_000, null);
        Transaction b = new Transaction("0x3", "0x4", 0, 1, 10, 10_000, null);

        List<Transaction> txs = new ArrayList<>(List.of(b, a));
        txs.sort(Transaction.FEE_PRIORITY);

        assertEquals(200_000L, a.maxFeeOffer());
        assertEquals(100_000L, b.maxFeeOffer());
        assertEquals(a, txs.get(0));
        assertEquals(b, txs.get(1));
    }
}
