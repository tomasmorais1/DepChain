package depchain.client.eth;

import depchain.blockchain.Transaction;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.web3j.crypto.Hash;

/**
 * Keccak-256 hash over a canonical encoding of {@link Transaction} for ECDSA signing (Ethereum-style).
 * Domain separation prefix avoids cross-protocol replay.
 */
public final class TransactionEthHasher {
    private static final byte[] DOMAIN = "DepChainTxV1".getBytes(StandardCharsets.UTF_8);

    private TransactionEthHasher() {}

    /** 32-byte Keccak-256 digest; sign this with secp256k1 (same family as Ethereum). */
    public static byte[] hashForSigning(Transaction tx) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(raw);
            String from = normalizeAddr(tx.getFrom());
            String to = tx.getTo() == null || tx.getTo().isBlank() ? "" : normalizeAddr(tx.getTo());
            d.writeUTF(from);
            d.writeUTF(to);
            d.writeLong(tx.getNonce());
            d.writeLong(tx.getValue());
            d.writeLong(tx.getGasPrice());
            d.writeLong(tx.getGasLimit());
            byte[] data = tx.getData();
            d.writeInt(data.length);
            d.write(data);
            d.flush();
            byte[] body = raw.toByteArray();
            byte[] prefixed = new byte[DOMAIN.length + body.length];
            System.arraycopy(DOMAIN, 0, prefixed, 0, DOMAIN.length);
            System.arraycopy(body, 0, prefixed, DOMAIN.length, body.length);
            return Hash.sha3(prefixed);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String normalizeAddr(String a) {
        if (a == null) return "";
        String s = a.trim().toLowerCase();
        return s.startsWith("0x") ? s : "0x" + s;
    }
}
