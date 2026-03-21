package depchain.evm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.crypto.Hash;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.junit.jupiter.api.Test;

class ISTCoinBesuExecutionTest {

    private static final String APPROVE_SELECTOR = "095ea7b3";

    @Test
    void approve_requires_reset_to_zero_before_new_non_zero() {
        Address owner = Address.fromHexString(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        Address spender = Address.fromHexString(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );
        Address contract = Address.fromHexString(
            "1234567891234567891234567891234567891234"
        );

        SimpleWorld world = new SimpleWorld();
        world.createAccount(owner, 0, Wei.fromEth(100));
        world.createAccount(contract, 0, Wei.fromEth(0));

        MutableAccount contractAccount = (MutableAccount) world.get(contract);
        Bytes runtimeCode = Bytes.fromHexString(readRuntimeBytecode());
        contractAccount.setCode(runtimeCode);

        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.code(runtimeCode);
        executor.sender(owner);
        executor.receiver(contract);
        executor.worldUpdater(world.updater());
        executor.commitWorldState();

        String approve100 = APPROVE_SELECTOR + encodeAddress(spender) + encodeUint(100);
        String approve50 = APPROVE_SELECTOR + encodeAddress(spender) + encodeUint(50);
        String approve0 = APPROVE_SELECTOR + encodeAddress(spender) + encodeUint(0);
        executeCall(executor, approve100);
        assertEquals(100, readAllowanceFromStorage(world, contract, owner, spender));

        executeCall(executor, approve50);
        assertEquals(100, readAllowanceFromStorage(world, contract, owner, spender));

        executeCall(executor, approve0);
        assertEquals(0, readAllowanceFromStorage(world, contract, owner, spender));

        executeCall(executor, approve50);
        assertEquals(50, readAllowanceFromStorage(world, contract, owner, spender));
    }

    private static String encodeAddress(Address address) {
        String hex = address.toHexString().replace("0x", "");
        return "000000000000000000000000" + hex;
    }

    private static String encodeUint(int value) {
        return String.format("%064x", BigInteger.valueOf(value));
    }

    private static void executeCall(EVMExecutor executor, String callDataHex) {
        executor.callData(Bytes.fromHexString(callDataHex));
        executor.execute();
    }

    private static int readAllowanceFromStorage(
        SimpleWorld world,
        Address contract,
        Address owner,
        Address spender
    ) {
        Bytes32 outer = Hash.keccak256(
            Bytes.fromHexString(encodeAddress(owner) + encodeUint(1))
        );
        Bytes32 finalSlot = Hash.keccak256(
            Bytes.concatenate(Bytes.fromHexString(encodeAddress(spender)), outer)
        );
        BigInteger value = world
            .get(contract)
            .getStorageValue(UInt256.fromHexString(finalSlot.toHexString()))
            .toBigInteger();
        return value.intValueExact();
    }

    private static String readRuntimeBytecode() {
        try (InputStream input =
            ISTCoinBesuExecutionTest.class.getResourceAsStream(
                "/contracts/ISTCoin.runtime.hex"
            )) {
            if (input == null) {
                throw new IllegalStateException("Missing ISTCoin runtime bytecode");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new RuntimeException("Unable to read runtime bytecode", e);
        }
    }
}
