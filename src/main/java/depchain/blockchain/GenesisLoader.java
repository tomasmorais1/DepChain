package depchain.blockchain;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads Step 3 genesis configuration from JSON resources.
 */
public final class GenesisLoader {
    private static final Gson GSON = new Gson();

    private GenesisLoader() {}

    public static Genesis loadFromResource(String resourcePath) {
        try (InputStream input = GenesisLoader.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalArgumentException(
                    "Genesis resource not found: " + resourcePath
                );
            }
            return parse(input);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read genesis resource", e);
        }
    }

    static Genesis parse(InputStream input) {
        try (InputStreamReader reader = new InputStreamReader(
            input,
            StandardCharsets.UTF_8
        )) {
            GenesisJson json = GSON.fromJson(reader, GenesisJson.class);
            if (json == null) {
                throw new IllegalArgumentException("Genesis JSON is empty");
            }
            List<Genesis.GenesisAccount> accounts = new ArrayList<>();
            if (json.accounts != null) {
                for (GenesisAccountJson account : json.accounts) {
                    accounts.add(
                        new Genesis.GenesisAccount(
                            account.address,
                            account.balance,
                            account.nonce
                        )
                    );
                }
            }

            List<Transaction> transactions = new ArrayList<>();
            if (json.transactions != null) {
                for (TransactionJson tx : json.transactions) {
                    transactions.add(
                        new Transaction(
                            tx.from,
                            tx.to,
                            tx.nonce,
                            tx.value,
                            tx.gasPrice,
                            tx.gasLimit,
                            decodeData(tx.data)
                        )
                    );
                }
            }

            List<Genesis.GenesisContract> contracts = new ArrayList<>();
            if (json.contracts != null) {
                for (GenesisContractJson cj : json.contracts) {
                    Map<String, String> slots = new LinkedHashMap<>();
                    if (cj.storage != null) {
                        for (Map.Entry<String, String> e : cj.storage.entrySet()) {
                            if (e.getKey() != null && e.getValue() != null) {
                                slots.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    contracts.add(new Genesis.GenesisContract(cj.address, cj.runtimeHex, slots));
                }
            }

            return new Genesis(
                json.blockHash,
                json.previousBlockHash,
                accounts,
                transactions,
                contracts
            );
        } catch (IOException e) {
            throw new RuntimeException("Unable to parse genesis JSON", e);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Invalid genesis JSON format", e);
        }
    }

    private static byte[] decodeData(String data) {
        if (data == null || data.isBlank()) {
            return new byte[0];
        }
        if (data.startsWith("0x") || data.startsWith("0X")) {
            String hex = data.substring(2);
            if (hex.length() % 2 != 0) {
                hex = "0" + hex;
            }
            byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length(); i += 2) {
                out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
            }
            return out;
        }
        return Base64.getDecoder().decode(data);
    }

    private static final class GenesisJson {
        String blockHash;
        String previousBlockHash;
        List<GenesisAccountJson> accounts;
        List<TransactionJson> transactions;
        List<GenesisContractJson> contracts;
    }

    private static final class GenesisContractJson {
        String address;
        String runtimeHex;
        Map<String, String> storage;
    }

    private static final class GenesisAccountJson {
        String address;
        long balance;
        long nonce;
    }

    private static final class TransactionJson {
        String from;
        String to;
        long nonce;
        long value;
        long gasPrice;
        long gasLimit;
        String data;
    }
}
