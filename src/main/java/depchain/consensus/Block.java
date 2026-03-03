package depchain.consensus;

import java.io.*;
import java.util.Arrays;
import java.util.Objects;

/**
 * A single block: payload (e.g. string to append). For Stage 1 one string per block.
 */
public final class Block implements Serializable {
    private final long viewNumber;
    private final byte[] payload;

    public Block(long viewNumber, byte[] payload) {
        this.viewNumber = viewNumber;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    public long getViewNumber() { return viewNumber; }
    public byte[] getPayload() { return payload; }

    /** Stable identity for this block (for lock/voting). */
    public byte[] getBlockHash() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(out);
            d.writeLong(viewNumber);
            d.writeInt(payload.length);
            d.write(payload);
            d.flush();
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Block block = (Block) o;
        return viewNumber == block.viewNumber && Arrays.equals(payload, block.payload);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(viewNumber);
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }
}
