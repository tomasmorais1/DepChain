package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContractExecutionTest {

    @Test
    void deploy_creates_contract_address_and_registers_runtime() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction deploy = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            null,
            0,
            0,
            2,
            200_000,
            new byte[] { 0x00 }
        );

        TransactionExecutionResult result = executor.execute(state, deploy);

        assertTrue(result.isSuccess());
        assertTrue(result.isStateApplied());
        assertNotNull(result.getCreatedContractAddress());
        assertTrue(executor.getContractRegistry().contains(result.getCreatedContractAddress()));
        assertEquals(
            1,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getNonce()
        );
    }

    @Test
    void call_to_deployed_contract_transfers_value_after_fee() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction deploy = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            null,
            0,
            0,
            1,
            200_000,
            new byte[] { 0x00 }
        );
        TransactionExecutionResult deployResult = executor.execute(state, deploy);
        String contractAddress = deployResult.getCreatedContractAddress();

        Transaction call = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            contractAddress,
            1,
            50,
            1,
            100_000,
            new byte[] { 0x12, 0x34 }
        );
        TransactionExecutionResult callResult = executor.execute(state, call);

        assertTrue(callResult.isSuccess());
        assertEquals(
            50,
            state.get(contractAddress).getBalance()
        );
    }

    @Test
    void call_unknown_contract_fails_after_fee_charge() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        Transaction call = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0xcccccccccccccccccccccccccccccccccccccccc",
            0,
            10,
            1,
            80_000,
            new byte[] { 0x55 }
        );
        TransactionExecutionResult result = executor.execute(state, call);

        assertFalse(result.isSuccess());
        assertEquals("unknown contract address", result.getError());
        assertEquals(
            1,
            state.get("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266").getNonce()
        );
    }
}
