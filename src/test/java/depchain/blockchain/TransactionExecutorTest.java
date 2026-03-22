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
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getBalance()
        );
        assertEquals(
            10_000_000 + 100,
            state.get("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").getBalance()
        );
        assertEquals(
            1,
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getNonce()
        );
    }

    @Test
    void out_of_gas_charges_fee_but_does_not_transfer_value() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction tx = new Transaction(
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getBalance()
        );
        assertEquals(
            10_000_000,
            state.get("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").getBalance()
        );
        assertEquals(
            1,
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getNonce()
        );
    }

    @Test
    void rejects_transaction_with_wrong_nonce_without_state_changes() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction tx = new Transaction(
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
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
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getBalance()
        );
        assertEquals(
            0,
            state.get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getNonce()
        );
    }
}
