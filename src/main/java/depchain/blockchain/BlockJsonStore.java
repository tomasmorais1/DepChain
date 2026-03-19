package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists Step 3 blocks using JSON format.
 */
public final class BlockJsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Path save(Path directory, LedgerBlock block) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(block.getHeight() + ".json");
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(block, writer);
            }
            return file;
        } catch (IOException e) {
            throw new RuntimeException("Unable to persist block JSON", e);
        }
    }

    public LedgerBlock load(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            LedgerBlock block = GSON.fromJson(reader, LedgerBlock.class);
            if (block == null) {
                throw new IllegalArgumentException("Invalid block file: " + file);
            }
            return block;
        } catch (IOException e) {
            throw new RuntimeException("Unable to read block JSON", e);
        }
    }
}
