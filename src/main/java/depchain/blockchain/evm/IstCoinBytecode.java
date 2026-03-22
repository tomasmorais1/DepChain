package depchain.blockchain.evm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Loads compiled IST Coin bytecode from resources (same Solidity as {@code ISTCoin.sol}).
 * Used to deploy the real contract in genesis: creation tx data must match
 * {@code ISTCoin.creation.hex}; runtime is taken from {@code ISTCoin.runtime.hex}.
 */
public final class IstCoinBytecode {

    /** Matches {@code totalSupply} in ISTCoin.sol: 100_000_000 * 10^decimals (2). */
    public static final long TOTAL_SUPPLY_UNITS = 100_000_000L * 100L;

    private static final String CREATION_RESOURCE = "/contracts/ISTCoin.creation.hex";
    private static final String RUNTIME_RESOURCE = "/contracts/ISTCoin.runtime.hex";

    private IstCoinBytecode() {}

    public static byte[] readCreationBytecode() {
        return readHexResource(CREATION_RESOURCE);
    }

    public static byte[] readRuntimeBytecode() {
        return readHexResource(RUNTIME_RESOURCE);
    }

    /**
     * True if {@code data} is exactly the known IST creation bytecode (same compiler output
     * as committed in resources).
     */
    public static boolean isKnownCreationBytecode(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        byte[] expected = readCreationBytecode();
        return Arrays.equals(data, expected);
    }

    private static byte[] readHexResource(String path) {
        try (InputStream in = IstCoinBytecode.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource: " + path);
            }
            String hex = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (hex.startsWith("0x") || hex.startsWith("0X")) {
                hex = hex.substring(2);
            }
            if (hex.length() % 2 != 0) {
                hex = "0" + hex;
            }
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length(); i += 2) {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read " + path, e);
        }
    }

    /** Hex string for embedding in genesis JSON (with 0x prefix). */
    public static String creationHexWith0x() {
        byte[] b = readCreationBytecode();
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
