package depchain.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class IstCoinCalldataTest {

    @Test
    void transfer_has_standard_selector_and_padded_args() {
        byte[] d =
                IstCoinCalldata.transfer(
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        BigInteger.valueOf(1250));
        assertEquals(4 + 32 + 32, d.length);
        assertArrayEquals(new byte[] {(byte) 0xa9, 0x05, (byte) 0x9c, (byte) 0xbb}, java.util.Arrays.copyOfRange(d, 0, 4));
    }

    @Test
    void approve_selector_matches_erc20() {
        byte[] d =
                IstCoinCalldata.approve(
                        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", BigInteger.ONE);
        assertArrayEquals(new byte[] {0x09, 0x5e, (byte) 0xa7, (byte) 0xb3}, java.util.Arrays.copyOfRange(d, 0, 4));
    }

    @Test
    void transferFrom_selector_matches_erc20() {
        byte[] d =
                IstCoinCalldata.transferFrom(
                        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                        BigInteger.TEN);
        assertArrayEquals(new byte[] {0x23, (byte) 0xb8, 0x72, (byte) 0xdd}, java.util.Arrays.copyOfRange(d, 0, 4));
    }
}
