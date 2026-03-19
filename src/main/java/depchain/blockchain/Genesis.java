package depchain.blockchain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory representation of genesis.json for Step 3.
 */
public final class Genesis {
    private final String blockHash;
    private final String previousBlockHash;
    private final List<GenesisAccount> accounts;
    private final List<Transaction> transactions;

    public Genesis(
        String blockHash,
        String previousBlockHash,
        List<GenesisAccount> accounts,
        List<Transaction> transactions
    ) {
        this.blockHash = blockHash;
        this.previousBlockHash = previousBlockHash;
        this.accounts = accounts == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(accounts));
        this.transactions = transactions == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(transactions));
    }

    public String getBlockHash() {
        return blockHash;
    }

    public String getPreviousBlockHash() {
        return previousBlockHash;
    }

    public List<GenesisAccount> getAccounts() {
        return accounts;
    }

    public List<Transaction> getTransactions() {
        return transactions;
    }

    public static final class GenesisAccount {
        private final String address;
        private final long balance;
        private final long nonce;

        public GenesisAccount(String address, long balance, long nonce) {
            if (address == null || address.isBlank()) {
                throw new IllegalArgumentException("address cannot be null/blank");
            }
            if (balance < 0 || nonce < 0) {
                throw new IllegalArgumentException("balance/nonce cannot be negative");
            }
            this.address = address;
            this.balance = balance;
            this.nonce = nonce;
        }

        public String getAddress() {
            return address;
        }

        public long getBalance() {
            return balance;
        }

        public long getNonce() {
            return nonce;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GenesisAccount)) return false;
            GenesisAccount that = (GenesisAccount) o;
            return balance == that.balance
                && nonce == that.nonce
                && Objects.equals(address, that.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, balance, nonce);
        }
    }
}
