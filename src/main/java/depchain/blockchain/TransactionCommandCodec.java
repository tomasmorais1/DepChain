package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.Base64;

/** Encodes/decodes {@link Transaction} to a stable string command for client protocol. */
public final class TransactionCommandCodec {
    private static final Gson GSON = new Gson();

    private TransactionCommandCodec() {}

    public static String encode(Transaction tx) {
        WireTx w = new WireTx();
        w.from = tx.getFrom();
        w.to = tx.getTo();
        w.nonce = tx.getNonce();
        w.value = tx.getValue();
        w.gasPrice = tx.getGasPrice();
        w.gasLimit = tx.getGasLimit();
        w.dataBase64 = Base64.getEncoder().encodeToString(tx.getData());
        return GSON.toJson(w);
    }

    public static Transaction decode(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        try {
            WireTx w = GSON.fromJson(command, WireTx.class);
            if (w == null || w.from == null) {
                return null;
            }
            byte[] data = w.dataBase64 == null
                ? new byte[0]
                : Base64.getDecoder().decode(w.dataBase64);
            return new Transaction(
                w.from,
                w.to,
                w.nonce,
                w.value,
                w.gasPrice,
                w.gasLimit,
                data
            );
        } catch (IllegalArgumentException | JsonSyntaxException e) {
            return null;
        }
    }

    private static final class WireTx {
        String from;
        String to;
        long nonce;
        long value;
        long gasPrice;
        long gasLimit;
        String dataBase64;
    }
}
