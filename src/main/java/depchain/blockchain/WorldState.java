package depchain.blockchain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal account state for Step 3 execution.
 */
public final class WorldState {
    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();

    public static WorldState fromGenesis(Genesis genesis) {
        WorldState state = new WorldState();
        for (Genesis.GenesisAccount account : genesis.getAccounts()) {
            state.accounts.put(
                account.getAddress(),
                new AccountState(account.getBalance(), account.getNonce())
            );
        }
        return state;
    }

    public AccountState getOrCreate(String address) {
        return accounts.computeIfAbsent(address, k -> new AccountState(0, 0));
    }

    public AccountState get(String address) {
        return accounts.get(address);
    }

    public Map<String, AccountState> asMapView() {
        return Collections.unmodifiableMap(accounts);
    }

    public Map<String, AccountSnapshot> snapshot() {
        Map<String, AccountSnapshot> out = new LinkedHashMap<>();
        for (Map.Entry<String, AccountState> entry : accounts.entrySet()) {
            AccountState account = entry.getValue();
            out.put(
                entry.getKey(),
                new AccountSnapshot(account.getBalance(), account.getNonce())
            );
        }
        return Collections.unmodifiableMap(out);
    }

    public static final class AccountState {
        private long balance;
        private long nonce;

        public AccountState(long balance, long nonce) {
            if (balance < 0 || nonce < 0) {
                throw new IllegalArgumentException("balance/nonce cannot be negative");
            }
            this.balance = balance;
            this.nonce = nonce;
        }

        public long getBalance() {
            return balance;
        }

        public long getNonce() {
            return nonce;
        }

        public void setBalance(long balance) {
            if (balance < 0) throw new IllegalArgumentException("balance cannot be negative");
            this.balance = balance;
        }

        public void setNonce(long nonce) {
            if (nonce < 0) throw new IllegalArgumentException("nonce cannot be negative");
            this.nonce = nonce;
        }
    }

    public static final class AccountSnapshot {
        private final long balance;
        private final long nonce;

        public AccountSnapshot(long balance, long nonce) {
            this.balance = balance;
            this.nonce = nonce;
        }

        public long getBalance() {
            return balance;
        }

        public long getNonce() {
            return nonce;
        }
    }
}
