package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.blockchain.evm.IstCoinBytecode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenesisLoaderTest {

    @Test
    void loads_genesis_from_resource() {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");

        assertEquals("genesis-step3", genesis.getBlockHash());
        assertNull(genesis.getPreviousBlockHash());
        assertEquals(2, genesis.getAccounts().size());
        assertEquals(1, genesis.getTransactions().size());
        assertTrue(genesis.getContracts().isEmpty());

        Genesis.GenesisAccount first = genesis.getAccounts().get(0);
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", first.getAddress());
        assertEquals(10_000_000, first.getBalance());
        assertEquals(0, first.getNonce());

        Transaction deployTx = genesis.getTransactions().get(0);
        assertEquals("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", deployTx.getFrom());
        assertEquals(0, deployTx.getNonce());
        assertEquals(0, deployTx.getValue());
        assertEquals(1, deployTx.getGasPrice());
        assertEquals(5000000, deployTx.getGasLimit());
        assertArrayEquals(IstCoinBytecode.readCreationBytecode(), deployTx.getData());
        assertTrue(deployTx.getData().length > 1000, "IST creation bytecode should be large");
    }

    @Test
    void loads_optional_contracts_bootstrap() {
        String json =
                "{"
                    + "\"blockHash\":\"g\","
                    + "\"previousBlockHash\":null,"
                    + "\"accounts\":[],"
                    + "\"transactions\":[],"
                    + "\"contracts\":[{"
                    + "\"address\":\"0x00000000000000000000000000000000000000aa\","
                    + "\"runtimeHex\":\"0x00aa\","
                    + "\"storage\":{\"0x01\":\"0x02\"}"
                    + "}]"
                    + "}";
        Genesis g = GenesisLoader.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, g.getContracts().size());
        Genesis.GenesisContract c = g.getContracts().get(0);
        assertEquals("0x00000000000000000000000000000000000000aa", c.getAddress());
        assertEquals("0x00aa", c.getRuntimeHex());
        assertEquals(Map.of("0x01", "0x02"), c.getStorage());
    }
}
