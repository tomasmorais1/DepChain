package depchain.blockchain;

import depchain.blockchain.evm.BesuEvmHelper;
import depchain.blockchain.evm.IstCoinBytecode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence sanity test: after persisting a decided block, a fresh process
 * should be able to reconstruct the committed account snapshots, deployed
 * runtime bytecode, and contract storage from JSON.
 */
class LedgerRestartConsistencyIntegrationTest {

    @Test
    void persistedBlock_canReconstructCommittedStateAfterRestart() throws Exception {
        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";
        String aliceKey = WorldState.normalizeAddr(alice);
        String bobKey = WorldState.normalizeAddr(bob);

        Genesis genesis = new Genesis(
            "genesis-hash",
            null,
            List.of(
                new Genesis.GenesisAccount(alice, 10_000_000L, 0),
                new Genesis.GenesisAccount(bob, 10_000_000L, 0)
            ),
            List.of()
        );

        WorldState world = WorldState.fromGenesis(genesis);
        TransactionExecutor executor = new TransactionExecutor();
        BlockchainLedger ledger = new BlockchainLedger(world, genesis.getBlockHash(), executor);

        Transaction deploy = new Transaction(
            alice,
            null,
            0,
            0,
            1,
            5_000_000,
            IstCoinBytecode.readCreationBytecode()
        );
        String contract = TransactionExecutor.deriveContractAddress(alice, 0L);

        Transaction approve = new Transaction(
            alice,
            contract,
            1,
            0,
            1,
            5_000_000,
            abiApprove(bob, 100)
        );

        LedgerBlock persisted = ledger.appendDecidedBlock(List.of(deploy, approve));

        BlockJsonStore store = new BlockJsonStore();
        Path dir = Files.createTempDirectory("depchain-ledger-restart");
        Path file = store.save(dir, persisted);
        LedgerBlock loaded = store.load(file);

        assertEquals(persisted.getHeight(), loaded.getHeight());
        assertEquals(persisted.getPreviousBlockHash(), loaded.getPreviousBlockHash());
        assertEquals(persisted.getState().keySet(), loaded.getState().keySet());
        assertEquals(persisted.getContractRuntimeHex(), loaded.getContractRuntimeHex());
        assertEquals(persisted.getContractStorageHex(), loaded.getContractStorageHex());

        WorldState restoredWorld = worldStateFromSnapshot(loaded.getState());
        TransactionExecutor restoredExecutor = new TransactionExecutor();

        restoredExecutor.getContractRegistry().applyRuntimeHexSnapshot(loaded.getContractRuntimeHex());
        restoredExecutor.getEvm().applyCodesFromRegistry(restoredExecutor.getContractRegistry());
        for (Map.Entry<String, Map<String, String>> e : loaded.getContractStorageHex().entrySet()) {
            restoredExecutor.getEvm().applyContractStorageHexSnapshot(e.getKey(), e.getValue());
        }

        assertEquals(
            loaded.getState().get(aliceKey).getBalance(),
            restoredWorld.get(aliceKey).getBalance()
        );
        assertEquals(
            loaded.getState().get(aliceKey).getNonce(),
            restoredWorld.get(aliceKey).getNonce()
        );
        assertEquals(
            loaded.getState().get(bobKey).getBalance(),
            restoredWorld.get(bobKey).getBalance()
        );
        assertEquals(
            loaded.getState().get(bobKey).getNonce(),
            restoredWorld.get(bobKey).getNonce()
        );

        assertTrue(restoredExecutor.getContractRegistry().contains(contract));
        assertNotNull(restoredExecutor.getContractRegistry().get(contract));

        String allowanceSlot = normalize32Hex(BesuEvmHelper.istAllowanceSlot(alice, bob).toHexString());
        String persistedAllowance = loaded.getContractStorageHex().get(contract).get(allowanceSlot);
        assertNotNull(persistedAllowance);
        assertEquals(UInt256.valueOf(100).toHexString().substring(2), persistedAllowance);

        var restoredContract = restoredExecutor.getEvm().getWorld().get(BesuEvmHelper.parseAddress(contract));
        var restoredAllowance = restoredContract.getStorageValue(UInt256.fromHexString("0x" + allowanceSlot));
        assertEquals(UInt256.valueOf(100), restoredAllowance);
    }

    private static WorldState worldStateFromSnapshot(Map<String, WorldState.AccountSnapshot> snapshot) {
        WorldState restored = new WorldState();
        for (Map.Entry<String, WorldState.AccountSnapshot> entry : snapshot.entrySet()) {
            WorldState.AccountState state = restored.getOrCreate(entry.getKey());
            state.setBalance(entry.getValue().getBalance());
            state.setNonce(entry.getValue().getNonce());
        }
        return restored;
    }

    private static byte[] abiApprove(String spenderHex, int value) {
        String selector = "095ea7b3"; // approve(address,uint256)
        String spender = padAddress(spenderHex);
        String val = String.format("%064x", java.math.BigInteger.valueOf(value));
        return hexToBytes(selector + spender + val);
    }

    private static String padAddress(String hex) {
        String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        return "000000000000000000000000" + s.toLowerCase();
    }

    private static byte[] hexToBytes(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() % 2 != 0) {
            s = "0" + s;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String normalize32Hex(String hexMaybe0x) {
        String s = hexMaybe0x == null ? "" : hexMaybe0x.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        s = s.toLowerCase();
        if (s.length() > 64) {
            s = s.substring(s.length() - 64);
        }
        return "0".repeat(Math.max(0, 64 - s.length())) + s;
    }
}
