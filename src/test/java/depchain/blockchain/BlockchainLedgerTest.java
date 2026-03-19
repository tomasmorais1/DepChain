package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockchainLedgerTest {

    @Test
    void appends_block_ordered_by_fee_and_persists_json() throws Exception {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        BlockchainLedger ledger = new BlockchainLedger(state, genesis.getBlockHash());

        Transaction lowFee = new Transaction(
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            0,
            10,
            1,
            50_000,
            null
        );
        Transaction highFee = new Transaction(
            "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            0,
            10,
            9,
            50_000,
            null
        );

        LedgerBlock block = ledger.appendBlock(List.of(lowFee, highFee));

        assertEquals(1L, block.getHeight());
        assertEquals("genesis-step3", block.getPreviousBlockHash());
        assertNotNull(block.getBlockHash());
        assertFalse(block.getBlockHash().isBlank());
        assertNotEquals(block.getPreviousBlockHash(), block.getBlockHash());
        assertEquals(2, block.getTransactions().size());
        assertEquals(
            9,
            block.getTransactions().get(0).getTransaction().getGasPrice()
        );
        assertEquals(
            1,
            block.getTransactions().get(1).getTransaction().getGasPrice()
        );

        Path tempDir = Files.createTempDirectory("depchain-step3-blocks-");
        BlockJsonStore store = new BlockJsonStore();
        Path file = store.save(tempDir, block);
        LedgerBlock loaded = store.load(file);

        assertEquals(block.getBlockHash(), loaded.getBlockHash());
        assertEquals(block.getPreviousBlockHash(), loaded.getPreviousBlockHash());
        assertEquals(block.getHeight(), loaded.getHeight());
        assertEquals(block.getTransactions().size(), loaded.getTransactions().size());
        assertEquals(
            block.getState().get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getBalance(),
            loaded.getState().get("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").getBalance()
        );
    }
}
