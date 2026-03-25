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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 2 Step 2 + Step 5: ERC-20 approval frontrunning mitigation — changing a non-zero allowance
 * to another non-zero value requires an explicit reset to zero first (see {@code approve} in
 * {@code ISTCoin.sol}).
 */
@DisplayName("ISTCoin EVM — approval frontrunning resistance (Step 5)")
class ISTCoinBesuExecutionTest {

    private static final String APPROVE_SELECTOR = "095ea7b3";

    @Test
    @DisplayName("approve(N) then approve(M) with M≠0 reverts until allowance reset to 0")
    void approve_requires_reset_to_zero_before_new_non_zero() {
        Address owner = Address.fromHexString(
            "f39fd6e51aad88f6f4ce6ab8827279cfffb92266"
        );
        Address spender = Address.fromHexString(
            "70997970c51812dc3a010c7d01b50e0d17dc79c8"
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
