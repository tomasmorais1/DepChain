package depchain.client;

import depchain.blockchain.Transaction;
import depchain.blockchain.TransactionCommandCodec;
import depchain.client.eth.TransactionEthHasher;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/**
 * Wire format for client append requests and responses.
 * <p>Type 0 (RSA): legacy append-string / Demo; signs {@code requestId || utf8}.
 * <p>Type 2 (ETH): Stage-2 transactions; signs Keccak-256 {@link TransactionEthHasher#hashForSigning(Transaction)}
 * with secp256k1; replicas recover address and require {@code tx.from} to match (non-repudiation).
 */
public final class ClientProtocol {
    public static final byte TYPE_REQUEST = 0;
    public static final byte TYPE_ETH_REQUEST = 2;
    public static final byte TYPE_RESPONSE = 1;
    public static final int MAX_STRING_LENGTH = 64 * 1024;
    /** Max size for request wire (string + pubKey + sig). */
    public static final int MAX_REQUEST_WIRE = MAX_STRING_LENGTH + 2048;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** Build the payload that is signed (same layout as block payload: requestId + string). */
    public static byte[] signedContent(long requestId, String string) {
        byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + utf8.length);
        b.putLong(requestId);
        b.put(utf8);
        return b.array();
    }

    /** Encode RSA request (type 0). */
    public static byte[] encodeRequest(long requestId, String string, byte[] signature, byte[] publicKeyEncoded) {
        try {
            byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_STRING_LENGTH) throw new IllegalArgumentException("string too long");
            if (signature == null || publicKeyEncoded == null) throw new IllegalArgumentException("signature and publicKey required");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeByte(TYPE_REQUEST);
            d.writeLong(requestId);
            d.writeInt(utf8.length);
            d.write(utf8);
            d.writeInt(publicKeyEncoded.length);
            d.write(publicKeyEncoded);
            d.writeInt(signature.length);
            d.write(signature);
            d.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Encode ETH-signed transaction command (type 2). */
    public static byte[] encodeEthRequest(long requestId, String string, Sign.SignatureData sig) {
        try {
            byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_STRING_LENGTH) throw new IllegalArgumentException("string too long");
            if (sig == null) throw new IllegalArgumentException("signature required");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeByte(TYPE_ETH_REQUEST);
            d.writeLong(requestId);
            d.writeInt(utf8.length);
            d.write(utf8);
            byte v = sig.getV()[0];
            d.writeByte(v);
            byte[] r = sig.getR();
            byte[] s = sig.getS();
            d.writeInt(r.length);
            d.write(r);
            d.writeInt(s.length);
            d.write(s);
            d.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Request parseRequest(byte[] wire) {
        if (wire == null || wire.length < 2) return null;
        ByteBuffer b = ByteBuffer.wrap(wire);
        byte kind = b.get();
        if (kind == TYPE_REQUEST) {
            return parseRsaTail(b);
        }
        if (kind == TYPE_ETH_REQUEST) {
            return parseEthTail(b);
        }
        return null;
    }

    private static Request parseRsaTail(ByteBuffer b) {
        if (b.remaining() < 8 + 4) return null;
        long requestId = b.getLong();
        int len = b.getInt();
        if (len < 0 || len > MAX_STRING_LENGTH || b.remaining() < len) return null;
        byte[] utf8 = new byte[len];
        b.get(utf8);
        String string = new String(utf8, StandardCharsets.UTF_8);
        if (b.remaining() < 4) return new Request(requestId, string, null, null, null, TYPE_REQUEST);
        int pubKeyLen = b.getInt();
        if (pubKeyLen < 0 || b.remaining() < pubKeyLen) return new Request(requestId, string, null, null, null, TYPE_REQUEST);
        byte[] pubKeyBytes = new byte[pubKeyLen];
        b.get(pubKeyBytes);
        if (b.remaining() < 4) return new Request(requestId, string, null, pubKeyBytes, null, TYPE_REQUEST);
        int sigLen = b.getInt();
        if (sigLen < 0 || b.remaining() < sigLen) return new Request(requestId, string, null, pubKeyBytes, null, TYPE_REQUEST);
        byte[] sig = new byte[sigLen];
        b.get(sig);
        return new Request(requestId, string, sig, pubKeyBytes, null, TYPE_REQUEST);
    }

    private static Request parseEthTail(ByteBuffer b) {
        if (b.remaining() < 8 + 4) return null;
        long requestId = b.getLong();
        int len = b.getInt();
        if (len < 0 || len > MAX_STRING_LENGTH || b.remaining() < len) return null;
        byte[] utf8 = new byte[len];
        b.get(utf8);
        String string = new String(utf8, StandardCharsets.UTF_8);
        if (b.remaining() < 1 + 4) return null;
        byte v = b.get();
        int rLen = b.getInt();
        if (rLen < 0 || rLen > 128 || b.remaining() < rLen + 4) return null;
        byte[] r = new byte[rLen];
        b.get(r);
        int sLen = b.getInt();
        if (sLen < 0 || sLen > 128 || b.remaining() < sLen) return null;
        byte[] s = new byte[sLen];
        b.get(s);
        Sign.SignatureData sig = new Sign.SignatureData(v, r, s);
        return new Request(requestId, string, null, null, sig, TYPE_ETH_REQUEST);
    }

    /** Verify request: RSA (type 0) or ETH transaction signature (type 2). */
    public static boolean verifyRequest(Request req) {
        if (req == null) return false;
        if (req.getProtocolKind() == TYPE_ETH_REQUEST) {
            return verifyEthSignedTransaction(req);
        }
        return verifyRsaRequest(req);
    }

    private static boolean verifyRsaRequest(Request req) {
        if (req.getSignature() == null || req.getPublicKeyEncoded() == null) return false;
        try {
            PublicKey pubKey = java.security.KeyFactory.getInstance("RSA")
                    .generatePublic(new java.security.spec.X509EncodedKeySpec(req.getPublicKeyEncoded()));
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initVerify(pubKey);
            sig.update(signedContent(req.getRequestId(), req.getString()));
            return sig.verify(req.getSignature());
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** Keccak hash + ECDSA recover; {@code tx.from} must match signer (Ethereum-style). */
    public static boolean verifyEthSignedTransaction(Request req) {
        if (req == null || req.getEthSignature() == null) return false;
        Transaction tx = TransactionCommandCodec.decode(req.getString());
        if (tx == null) return false;
        byte[] hash = TransactionEthHasher.hashForSigning(tx);
        try {
            java.math.BigInteger pubKeyRecovered = Sign.signedMessageHashToKey(hash, req.getEthSignature());
            String recoveredAddr = Keys.getAddress(pubKeyRecovered);
            return addrEqual(tx.getFrom(), recoveredAddr);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean addrEqual(String fromField, String recoveredWeb3j) {
        String a = Numeric.cleanHexPrefix(TransactionEthHasher.normalizeAddr(fromField));
        String b = Numeric.cleanHexPrefix(recoveredWeb3j);
        return a.equalsIgnoreCase(b);
    }

    public static byte[] encodeResponse(long requestId, boolean success, int index) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeByte(TYPE_RESPONSE);
            d.writeLong(requestId);
            d.writeBoolean(success);
            d.writeInt(index);
            d.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Response parseResponse(byte[] wire) {
        if (wire == null || wire.length < 1 + 8 + 1 + 4) return null;
        ByteBuffer b = ByteBuffer.wrap(wire);
        if (b.get() != TYPE_RESPONSE) return null;
        long requestId = b.getLong();
        boolean success = b.get() != 0;
        int index = b.getInt();
        return new Response(requestId, success, index);
    }

    public static final class Request {
        private final long requestId;
        private final String string;
        private final byte[] signature;
        private final byte[] publicKeyEncoded;
        private final Sign.SignatureData ethSignature;
        private final byte protocolKind;

        public Request(
                long requestId,
                String string,
                byte[] signature,
                byte[] publicKeyEncoded,
                Sign.SignatureData ethSignature,
                byte protocolKind
        ) {
            this.requestId = requestId;
            this.string = string;
            this.signature = signature;
            this.publicKeyEncoded = publicKeyEncoded;
            this.ethSignature = ethSignature;
            this.protocolKind = protocolKind;
        }

        public long getRequestId() { return requestId; }
        public String getString() { return string; }
        public byte[] getSignature() { return signature; }
        public byte[] getPublicKeyEncoded() { return publicKeyEncoded; }
        public Sign.SignatureData getEthSignature() { return ethSignature; }
        public byte getProtocolKind() { return protocolKind; }
    }

    public static final class Response {
        private final long requestId;
        private final boolean success;
        private final int index;

        public Response(long requestId, boolean success, int index) {
            this.requestId = requestId;
            this.success = success;
            this.index = index;
        }
        public long getRequestId() { return requestId; }
        public boolean isSuccess() { return success; }
        public int getIndex() { return index; }
    }
}
