package depchain.blockchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import depchain.blockchain.evm.BesuEvmHelper;
import depchain.blockchain.evm.IstCoinBytecode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.Test;

/**
 * Stage 2: blocks should represent world state. We persist IST Coin storage slots (balances/allowances)
 * inside {@link LedgerBlock#getContractStorageHex()} and ensure they round-trip via JSON.
 */
class ContractStoragePersistenceTest {

    @Test
    void istAllowanceSlot_isPersistedAndCanBeRestored() throws Exception {
        String alice = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
        String bob = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

        Genesis genesis =
            new Genesis(
                "genesis-hash",
                null,
                List.of(
                    new Genesis.GenesisAccount(alice, 10_000_000L, 0),
                    new Genesis.GenesisAccount(bob, 10_000_000L, 0)
                ),
                List.of()
            );

        WorldState ws = WorldState.fromGenesis(genesis);
        TransactionExecutor exec = new TransactionExecutor();
        BlockchainLedger ledger = new BlockchainLedger(ws, genesis.getBlockHash(), exec);

        // Deploy IST Coin (known creation bytecode triggers runtime install + seeding).
        Transaction deploy =
            new Transaction(
                alice,
                null,
                0,
                0,
                1,
                5_000_000,
                IstCoinBytecode.readCreationBytecode()
            );
        // Approve bob for 100 units.
        String contract = TransactionExecutor.deriveContractAddress(alice, 0L);
        byte[] approve100 = abiApprove(bob, 100);
        Transaction approve =
            new Transaction(
                alice,
                contract,
                1,
                0,
                1,
                5_000_000,
                approve100
            );

        LedgerBlock b = ledger.appendDecidedBlock(List.of(deploy, approve));
        Map<String, Map<String, String>> storage = b.getContractStorageHex();
        assertNotNull(storage);
        assertTrue(storage.containsKey(contract));

        String allowanceSlot = BesuEvmHelper.istAllowanceSlot(alice, bob).toHexString();
        String slotKey = normalize32Hex(allowanceSlot);
        String stored = storage.get(contract).get(slotKey);
        assertNotNull(stored);
        assertEquals(UInt256.valueOf(100).toHexString().substring(2), stored);

        // Round-trip via JSON and restore into a fresh Besu world.
        BlockJsonStore store = new BlockJsonStore();
        Path dir = Files.createTempDirectory("depchain-blocks");
        Path file = store.save(dir, b);
        LedgerBlock loaded = store.load(file);

        TransactionExecutor exec2 = new TransactionExecutor();
        exec2.getContractRegistry().applyRuntimeHexSnapshot(loaded.getContractRuntimeHex());
        exec2.getEvm().applyCodesFromRegistry(exec2.getContractRegistry());
        exec2.getEvm().applyContractStorageHexSnapshot(contract, loaded.getContractStorageHex().get(contract));

        var acc = exec2.getEvm().getWorld().get(BesuEvmHelper.parseAddress(contract));
        var v = acc.getStorageValue(UInt256.fromHexString("0x" + slotKey));
        assertEquals(UInt256.valueOf(100), v);
    }

    private static byte[] abiApprove(String spenderHex, int value) {
        // approve(address,uint256) selector = 0x095ea7b3
        String selector = "095ea7b3";
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
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        if (s.length() % 2 != 0) s = "0" + s;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String normalize32Hex(String hexMaybe0x) {
        String s = hexMaybe0x == null ? "" : hexMaybe0x.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        s = s.toLowerCase();
        if (s.length() > 64) s = s.substring(s.length() - 64);
        return "0".repeat(Math.max(0, 64 - s.length())) + s;
    }
}

