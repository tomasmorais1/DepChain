package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransactionExecutorTest {

    @Test
    void executes_native_transfer_and_charges_fee() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction tx = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            100,
            2,
            50_000,
            null
        );

        TransactionExecutionResult result = executor.execute(state, tx);

        assertTrue(result.isSuccess());
        assertTrue(result.isStateApplied());
        assertEquals(42_000, result.getFeeCharged());
        assertEquals(
            10_000_000 - 42_000 - 100,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getBalance()
        );
        assertEquals(
            10_000_000 + 100,
            state.get("0x70997970C51812dc3A010C7d01b50e0d17dc79C8").getBalance()
        );
        assertEquals(
            1,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getNonce()
        );
    }

    @Test
    void out_of_gas_charges_fee_but_does_not_transfer_value() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction tx = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            100,
            3,
            10_000,
            null
        );

        TransactionExecutionResult result = executor.execute(state, tx);

        assertFalse(result.isSuccess());
        assertFalse(result.isStateApplied());
        assertEquals("out of gas", result.getError());
        assertEquals(30_000, result.getFeeCharged());
        assertEquals(
            10_000_000 - 30_000,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getBalance()
        );
        assertEquals(
            10_000_000,
            state.get("0x70997970C51812dc3A010C7d01b50e0d17dc79C8").getBalance()
        );
        assertEquals(
            1,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getNonce()
        );
    }

    @Test
    void rejects_transaction_with_wrong_nonce_without_state_changes() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction tx = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            7,
            100,
            1,
            21_000,
            null
        );

        TransactionExecutionResult result = executor.execute(state, tx);

        assertFalse(result.isSuccess());
        assertEquals("invalid nonce", result.getError());
        assertEquals(
            10_000_000,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getBalance()
        );
        assertEquals(
            0,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getNonce()
        );
    }
}
