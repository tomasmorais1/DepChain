package depchain.client;

import depchain.config.NodeAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format for client append requests and responses.
 * Request: type=0 (1 byte), requestId (8 bytes), stringLength (4), stringUtf8.
 * Response: type=1 (1 byte), requestId (8 bytes), success (1 byte), index (4 bytes).
 */
public final class ClientProtocol {
    public static final byte TYPE_REQUEST = 0;
    public static final byte TYPE_RESPONSE = 1;
    public static final int MAX_STRING_LENGTH = 64 * 1024;

    public static byte[] encodeRequest(long requestId, String string) {
        try {
            byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
            if (utf8.length > MAX_STRING_LENGTH) throw new IllegalArgumentException("string too long");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeByte(TYPE_REQUEST);
            d.writeLong(requestId);
            d.writeInt(utf8.length);
            d.write(utf8);
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
        return new Request(requestId, new String(utf8, StandardCharsets.UTF_8));
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

        public Request(long requestId, String string) {
            this.requestId = requestId;
            this.string = string;
        }
        public long getRequestId() { return requestId; }
        public String getString() { return string; }
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
