package depchain.blockchain.evm;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;

/**
 * Hyperledger Besu EVM wrapper for Step 3 contract deploy and call.
 * DepCoin balances are mapped 1:1 to {@link Wei} smallest units for execution.
 */
public final class BesuEvmHelper {
    private final SimpleWorld world = new SimpleWorld();

    public SimpleWorld getWorld() {
        return world;
    }

    public static Address parseAddress(String hex) {
        String s = hex == null ? "" : hex.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 40) {
            throw new IllegalArgumentException("Invalid address length: " + hex);
        }
        return Address.fromHexString(s);
    }

    public static Wei balanceToWei(long depCoinUnits) {
        if (depCoinUnits < 0) {
            throw new IllegalArgumentException("balance cannot be negative");
        }
        return Wei.of(depCoinUnits);
    }

    public void upsertAccount(String hex, long nonce, long balanceDepCoin) {
        Address addr = parseAddress(hex);
        Wei wei = balanceToWei(balanceDepCoin);
        if (world.get(addr) == null) {
            world.createAccount(addr, nonce, wei);
        } else {
            MutableAccount acc = (MutableAccount) world.get(addr);
            acc.setNonce(nonce);
            acc.setBalance(wei);
        }
    }

    /**
     * Sets runtime bytecode on an existing account (contract deployment result).
     */
    public void setContractCode(String contractHex, byte[] runtimeBytecode) {
        Address addr = parseAddress(contractHex);
        if (world.get(addr) == null) {
            world.createAccount(addr, 0, Wei.ZERO);
        }
        MutableAccount acc = (MutableAccount) world.get(addr);
        acc.setCode(Bytes.of(runtimeBytecode));
    }

    /**
     * Executes a contract call; returns the EVM output bytes (may be empty).
     */
    public Bytes call(String fromHex, String toHex, byte[] callData) {
        Address from = parseAddress(fromHex);
        Address to = parseAddress(toHex);
        MutableAccount contract = (MutableAccount) world.get(to);
        if (contract == null || contract.getCode().isEmpty()) {
            throw new IllegalStateException("no contract code at " + toHex);
        }
        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.code(contract.getCode());
        executor.sender(from);
        executor.receiver(to);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();
        executor.callData(Bytes.of(callData == null ? new byte[0] : callData));
        return executor.execute();
    }

    public long readBalanceDepCoin(String hex) {
        Address addr = parseAddress(hex);
        var acc = world.get(addr);
        if (acc == null) {
            return 0;
        }
        return acc.getBalance().toBigInteger().longValueExact();
    }
}
