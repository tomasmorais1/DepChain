package depchain.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;

/**
 * Wire format for client append requests and responses.
 * Request: type=0 (1 byte), requestId (8 bytes), stringLength (4), stringUtf8, pubKeyLength (4), pubKeyBytes, sigLength (4), sigBytes.
 * Client signs (requestId || stringUtf8) with private key; replicas verify to reject commands forged by a byzantine leader.
 * Response: type=1 (1 byte), requestId (8 bytes), success (1 byte), index (4 bytes).
 */
public final class ClientProtocol {
    public static final byte TYPE_REQUEST = 0;
    public static final byte TYPE_RESPONSE = 1;
    public static final int MAX_STRING_LENGTH = 64 * 1024;
    /** Max size for request wire (string + pubKey + sig). */
    public static final int MAX_REQUEST_WIRE = MAX_STRING_LENGTH + 1024;
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** Build the payload that is signed (same layout as block payload: requestId + string). */
    public static byte[] signedContent(long requestId, String string) {
        byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + utf8.length);
        b.putLong(requestId);
        b.put(utf8);
        return b.array();
    }

    /** Encode request with signature and public key so replicas can verify it was sent by the client. */
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

    public static Request parseRequest(byte[] wire) {
        if (wire == null || wire.length < 1 + 8 + 4) return null;
        ByteBuffer b = ByteBuffer.wrap(wire);
        if (b.get() != TYPE_REQUEST) return null;
        long requestId = b.getLong();
        int len = b.getInt();
        if (len < 0 || len > MAX_STRING_LENGTH || b.remaining() < len) return null;
        byte[] utf8 = new byte[len];
        b.get(utf8);
        String string = new String(utf8, StandardCharsets.UTF_8);
        if (b.remaining() < 4) return new Request(requestId, string, null, null);
        int pubKeyLen = b.getInt();
        if (pubKeyLen < 0 || b.remaining() < pubKeyLen) return new Request(requestId, string, null, null);
        byte[] pubKeyBytes = new byte[pubKeyLen];
        b.get(pubKeyBytes);
        if (b.remaining() < 4) return new Request(requestId, string, null, pubKeyBytes);
        int sigLen = b.getInt();
        if (sigLen < 0 || b.remaining() < sigLen) return new Request(requestId, string, null, pubKeyBytes);
        byte[] sig = new byte[sigLen];
        b.get(sig);
        return new Request(requestId, string, sig, pubKeyBytes);
    }

    /** Verify that the request was signed by the client (reject forged commands by byzantine leader). */
    public static boolean verifyRequest(Request req) {
        if (req == null || req.getSignature() == null || req.getPublicKeyEncoded() == null) return false;
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

        public Request(long requestId, String string, byte[] signature, byte[] publicKeyEncoded) {
            this.requestId = requestId;
            this.string = string;
            this.signature = signature;
            this.publicKeyEncoded = publicKeyEncoded;
        }
        public long getRequestId() { return requestId; }
        public String getString() { return string; }
        public byte[] getSignature() { return signature; }
        public byte[] getPublicKeyEncoded() { return publicKeyEncoded; }
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
