package depchain.blockchain;

import java.util.Arrays;
import java.util.Objects;

/**
 * Verified transaction waiting in the mempool.
 */
public final class PendingTransaction {

    private final long requestId;
    private final String clientId;
    private final Transaction transaction;
    private final byte[] canonicalBytes;
    private final long arrivalSequence;
    private final long feePriority;

    public PendingTransaction(
        long requestId,
        String clientId,
        Transaction transaction,
        byte[] canonicalBytes,
        long arrivalSequence
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction cannot be null");
        }
        this.requestId = requestId;
        this.clientId = clientId;
        this.transaction = transaction;
        this.canonicalBytes = canonicalBytes == null ? new byte[0] : canonicalBytes.clone();
        this.arrivalSequence = arrivalSequence;
        this.feePriority = transaction.maxFeeOffer();
    }

    public long getRequestId() {
        return requestId;
    }

    public String getClientId() {
        return clientId;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public byte[] getCanonicalBytes() {
        return canonicalBytes.clone();
    }

    public long getArrivalSequence() {
        return arrivalSequence;
    }

    public long getFeePriority() {
        return feePriority;
    }

    public String getSenderAddress() {
        return transaction.getFrom();
    }

    public long getNonce() {
        return transaction.getNonce();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PendingTransaction that)) {
            return false;
        }
        return requestId == that.requestId
            && arrivalSequence == that.arrivalSequence
            && Objects.equals(clientId, that.clientId)
            && Objects.equals(transaction, that.transaction)
            && Arrays.equals(canonicalBytes, that.canonicalBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(requestId, clientId, transaction, arrivalSequence);
        result = 31 * result + Arrays.hashCode(canonicalBytes);
        return result;
    }

    @Override
    public String toString() {
        return "PendingTransaction{" +
            "requestId=" + requestId +
            ", clientId='" + clientId + '\'' +
            ", from='" + transaction.getFrom() + '\'' +
            ", nonce=" + transaction.getNonce() +
            ", feePriority=" + feePriority +
            '}';
    }
}
