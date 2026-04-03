package depchain.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Wire encoding for a proposed block: ordered list of (requestId, command) items. */
public final class TxBatchPayload {
    private static final int MAGIC = 0x44545842; // "DTXB"
    private static final short VERSION = 1;

    private final List<TxItem> items;

    public TxBatchPayload(List<TxItem> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    public List<TxItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeInt(MAGIC);
            d.writeShort(VERSION);
            d.writeInt(items.size());
            for (TxItem item : items) {
                byte[] cmd = item.command().getBytes(StandardCharsets.UTF_8);
                d.writeLong(item.requestId());
                d.writeInt(cmd.length);
                d.write(cmd);
            }
            d.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to encode TxBatchPayload", e);
        }
    }

    public static TxBatchPayload fromBytes(byte[] payload) {
        if (payload == null || payload.length < 10) {
            return null;
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(payload);
            int magic = b.getInt();
            short version = b.getShort();
            if (magic != MAGIC || version != VERSION) {
                return null;
            }
            int count = b.getInt();
            if (count < 0) {
                return null;
            }
            List<TxItem> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                if (b.remaining() < 12) {
                    return null;
                }
                long requestId = b.getLong();
                int len = b.getInt();
                if (len < 0 || b.remaining() < len) {
                    return null;
                }
                byte[] cmd = new byte[len];
                b.get(cmd);
                out.add(new TxItem(requestId, new String(cmd, StandardCharsets.UTF_8)));
            }
            if (b.hasRemaining()) {
                return null;
            }
            return new TxBatchPayload(out);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public record TxItem(long requestId, String command) {
        public TxItem {
            if (command == null) {
                throw new IllegalArgumentException("command cannot be null");
            }
        }
    }
}
