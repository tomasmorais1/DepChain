package depchain.blockchain;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * Stage 2 / Step 3 transaction model.
 * Supports native transfers and contract calls (when {@code data} is non-empty).
 */
public final class Transaction implements Serializable {
    private final String from;
    private final String to;
    private final long nonce;
    private final long value;
    private final long gasPrice;
    private final long gasLimit;
    private final byte[] data;

    /**
     * Block ordering before execution: higher {@linkplain #maxFeeOffer() max fee offer} first.
     * <p>
     * The enunciado charges {@code min(gas_price * gas_limit, gas_price * gas_used)}; before
     * execution {@code gas_used} is unknown, so we use {@code gas_price * gas_limit} as the
     * user-declared upper bound on native DepCoin fee — a standard proxy for “willingness to pay”.
     * Tie-break: {@link #getNonce()} (applied in {@code BlockchainLedger.appendBlock}).
     */
    public static final Comparator<Transaction> FEE_PRIORITY =
        Comparator.comparingLong(Transaction::maxFeeOffer).reversed();

    /**
     * Upper bound on fee in DepCoin smallest units: {@code gasPrice * gasLimit}.
     * Matches the maximum of {@code gas_price * gas_limit} in the fee formula before {@code gas_used} is known.
     * On overflow, returns {@link Long#MAX_VALUE} so the transaction sorts first (extreme bids).
     */
    public long maxFeeOffer() {
        try {
            return Math.multiplyExact(gasPrice, gasLimit);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public Transaction(
        String from,
        String to,
        long nonce,
        long value,
        long gasPrice,
        long gasLimit,
        byte[] data
    ) {
        if (from == null || from.isBlank()) {
            throw new IllegalArgumentException("from cannot be null/blank");
        }
        if (nonce < 0 || value < 0) {
            throw new IllegalArgumentException("nonce/value cannot be negative");
        }
        if (gasPrice <= 0 || gasLimit <= 0) {
            throw new IllegalArgumentException("gasPrice and gasLimit must be > 0");
        }
        this.from = from;
        this.to = to;
        this.nonce = nonce;
        this.value = value;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.data = data == null ? new byte[0] : data.clone();
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public long getNonce() {
        return nonce;
    }

    public long getValue() {
        return value;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public byte[] getData() {
        return data.clone();
    }

    public boolean isContractDeployment() {
        return to == null || to.isBlank();
    }

    public boolean isContractCall() {
        return !isContractDeployment() && data.length > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction)) return false;
        Transaction that = (Transaction) o;
        return nonce == that.nonce
            && value == that.value
            && gasPrice == that.gasPrice
            && gasLimit == that.gasLimit
            && Objects.equals(from, that.from)
            && Objects.equals(to, that.to)
            && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(from, to, nonce, value, gasPrice, gasLimit);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
