package depchain.blockchain;

/**
 * Provides the next valid nonce for an account according to the currently committed state.
 */
public interface NonceProvider {

    /**
     * Returns the next valid nonce for the given account according to the committed state.
     *
     * If the account does not exist yet, this should normally return 0.
     */
    long getNextExpectedNonce(String accountAddress);
}
