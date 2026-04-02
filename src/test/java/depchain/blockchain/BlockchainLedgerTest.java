package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.blockchain.evm.BesuEvmHelper;
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
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            0,
            10,
            1,
            50_000,
            null
        );
        Transaction highFee = new Transaction(
            "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
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
        // Same gasLimit: order follows gasPrice; generally order follows maxFeeOffer = gasPrice * gasLimit
        assertEquals(9 * 50_000L, highFee.maxFeeOffer());
        assertEquals(1 * 50_000L, lowFee.maxFeeOffer());
        assertEquals(
            9 * 50_000L,
            block.getTransactions().get(0).getTransaction().maxFeeOffer()
        );
        assertEquals(
            1 * 50_000L,
            block.getTransactions().get(1).getTransaction().maxFeeOffer()
        );

        Path tempDir = Files.createTempDirectory("depchain-step3-blocks-");
        BlockJsonStore store = new BlockJsonStore();
        Path file = store.save(tempDir, block);
        LedgerBlock loaded = store.load(file);

        assertEquals(block.getBlockHash(), loaded.getBlockHash());
        assertEquals(block.getPreviousBlockHash(), loaded.getPreviousBlockHash());
        assertEquals(block.getHeight(), loaded.getHeight());
        assertEquals(block.getTransactions().size(), loaded.getTransactions().size());
        String acct0 = WorldState.normalizeAddr("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266");
        assertEquals(
            block.getState().get(acct0).getBalance(),
            loaded.getState().get(acct0).getBalance()
        );
        assertTrue(block.getContractRuntimeHex().isEmpty());
        assertTrue(loaded.getContractRuntimeHex().isEmpty());
    }

    @Test
    void ledger_block_persists_contract_runtime_hex_round_trip() throws Exception {
        Genesis genesis = GenesisLoader.loadFromResource("/blockchain/genesis.json");
        WorldState state = WorldState.fromGenesis(genesis);
        ContractRuntimeRegistry reg = new ContractRuntimeRegistry();
        BesuEvmHelper evm = new BesuEvmHelper();
        TransactionExecutor exec = new TransactionExecutor(reg, evm);
        BlockchainLedger ledger = new BlockchainLedger(state, genesis.getBlockHash(), exec);

        Transaction deploy = new Transaction(
            "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
            null,
            0,
            0,
            1,
            200_000,
            new byte[] { 0x00 }
        );
        LedgerBlock block = ledger.appendBlock(List.of(deploy));
        assertEquals(1, block.getContractRuntimeHex().size());

        String contractAddress =
            block.getTransactions().get(0).getResult().getCreatedContractAddress();
        assertNotNull(contractAddress);

        Path tempDir = Files.createTempDirectory("depchain-contract-persist-");
        BlockJsonStore store = new BlockJsonStore();
        Path file = store.save(tempDir, block);
        LedgerBlock loaded = store.load(file);

        assertEquals(block.getContractRuntimeHex(), loaded.getContractRuntimeHex());

        ContractRuntimeRegistry restored = new ContractRuntimeRegistry();
        restored.applyRuntimeHexSnapshot(loaded.getContractRuntimeHex());
        assertTrue(restored.contains(contractAddress));
        assertArrayEquals(reg.get(contractAddress), restored.get(contractAddress));
    }
}
