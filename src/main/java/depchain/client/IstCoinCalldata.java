package depchain.client;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

/**
 * ABI-encoded calldata for IST Coin (ERC-20–compatible selectors) without pulling in web3j codegen.
 */
public final class IstCoinCalldata {

    private IstCoinCalldata() {}

    public static byte[] transfer(String toAddress, BigInteger amountSmallestUnits) {
        return buildTwoArgCall("transfer(address,uint256)", toAddress, amountSmallestUnits);
    }

    public static byte[] approve(String spenderAddress, BigInteger amountSmallestUnits) {
        return buildTwoArgCall("approve(address,uint256)", spenderAddress, amountSmallestUnits);
    }

    public static byte[] transferFrom(String ownerAddress, String recipientAddress, BigInteger amountSmallestUnits) {
        byte[] sel = firstFourBytesKeccak("transferFrom(address,address,uint256)");
        byte[] out = new byte[4 + 32 + 32 + 32];
        System.arraycopy(sel, 0, out, 0, 4);
        System.arraycopy(padAddress(toWord(ownerAddress)), 0, out, 4, 32);
        System.arraycopy(padAddress(toWord(recipientAddress)), 0, out, 36, 32);
        System.arraycopy(uint256Word(amountSmallestUnits), 0, out, 68, 32);
        return out;
    }

    private static byte[] buildTwoArgCall(String signature, String addr, BigInteger amount) {
        byte[] sel = firstFourBytesKeccak(signature);
        byte[] out = new byte[4 + 32 + 32];
        System.arraycopy(sel, 0, out, 0, 4);
        System.arraycopy(padAddress(toWord(addr)), 0, out, 4, 32);
        System.arraycopy(uint256Word(amount), 0, out, 36, 32);
        return out;
    }

    private static byte[] firstFourBytesKeccak(String signature) {
        byte[] hash = Hash.sha3(signature.getBytes(StandardCharsets.UTF_8));
        byte[] sel = new byte[4];
        System.arraycopy(hash, 0, sel, 0, 4);
        return sel;
    }

    private static byte[] toWord(String hexAddr) {
        String a = Numeric.cleanHexPrefix(hexAddr == null ? "" : hexAddr.trim());
        if (a.length() != 40) {
            throw new IllegalArgumentException("expected 20-byte hex address: " + hexAddr);
        }
        return Numeric.hexStringToByteArray(a);
    }

    private static byte[] padAddress(byte[] addr20) {
        byte[] word = new byte[32];
        System.arraycopy(addr20, 0, word, 12, 20);
        return word;
    }

    private static byte[] uint256Word(BigInteger v) {
        if (v == null || v.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative");
        }
        byte[] src = v.toByteArray();
        if (src.length > 32) {
            throw new IllegalArgumentException("amount does not fit uint256");
        }
        byte[] out = new byte[32];
        System.arraycopy(src, 0, out, 32 - src.length, src.length);
        return out;
    }
}
