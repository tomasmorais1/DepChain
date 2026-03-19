package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GenesisLoaderTest {

    @Test
    void loads_genesis_from_resource() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");

        assertEquals("genesis-step3", genesis.getBlockHash());
        assertNull(genesis.getPreviousBlockHash());
        assertEquals(2, genesis.getAccounts().size());
        assertEquals(1, genesis.getTransactions().size());

        Genesis.GenesisAccount first = genesis.getAccounts().get(0);
        assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", first.getAddress());
        assertEquals(1000000, first.getBalance());
        assertEquals(0, first.getNonce());

        Transaction deployTx = genesis.getTransactions().get(0);
        assertEquals("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", deployTx.getFrom());
        assertEquals(0, deployTx.getNonce());
        assertEquals(0, deployTx.getValue());
        assertEquals(1, deployTx.getGasPrice());
        assertEquals(5000000, deployTx.getGasLimit());
        assertArrayEquals(new byte[] { 0x60, 0x00, 0x60, 0x00 }, deployTx.getData());
    }
}
