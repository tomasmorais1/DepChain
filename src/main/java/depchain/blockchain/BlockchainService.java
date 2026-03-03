package depchain.blockchain;

import depchain.consensus.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simple in-memory blockchain: append-only array of strings.
 * Updated only via upcall from consensus when DECIDE is received.
 */
public final class BlockchainService {
    private final List<String> log = new CopyOnWriteArrayList<>();

    /** Called by consensus layer when a block is decided (payload is plain string). */
    public void onDecide(Block block) {
        if (block == null || block.getPayload() == null) return;
        log.add(new String(block.getPayload(), java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Append string directly (used by BlockchainMember when block payload is requestId+string). */
    public void appendString(String s) {
        if (s != null) log.add(s);
    }

    public List<String> getLog() {
        return Collections.unmodifiableList(new ArrayList<>(log));
    }

    public int size() {
        return log.size();
    }
}
