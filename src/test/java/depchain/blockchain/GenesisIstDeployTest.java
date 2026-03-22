package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.blockchain.evm.IstCoinBytecode;
import org.junit.jupiter.api.Test;

/**
 * Genesis tx deploys IST Coin: creation data matches resources; execution installs runtime + supply.
 */
class GenesisIstDeployTest {

    @Test
    void genesis_deploy_transaction_installs_ist_runtime() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        Transaction deployTx = genesis.getTransactions().get(0);
        assertTrue(IstCoinBytecode.isKnownCreationBytecode(deployTx.getData()));

        WorldState state = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();

        TransactionExecutionResult result = executor.execute(state, deployTx);
        assertTrue(result.isSuccess(), result.getError());
        assertNotNull(result.getCreatedContractAddress());

        String contract = result.getCreatedContractAddress();
        assertTrue(executor.getContractRegistry().contains(contract));
        byte[] registered = executor.getContractRegistry().get(contract);
        assertEquals(IstCoinBytecode.readRuntimeBytecode().length, registered.length);
    }
}
