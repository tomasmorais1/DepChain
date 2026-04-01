package depchain.blockchain;

/**
 * Configuration for building the next candidate block.
 */
public final class BlockBuilderConfig {

    private final int maxTransactionsPerBlock;
    private final long maxBlockGas;

    public BlockBuilderConfig(int maxTransactionsPerBlock, long maxBlockGas) {
        if (maxTransactionsPerBlock <= 0) {
            throw new IllegalArgumentException("maxTransactionsPerBlock must be > 0");
        }
        this.maxTransactionsPerBlock = maxTransactionsPerBlock;
        this.maxBlockGas = maxBlockGas;
    }

    public static BlockBuilderConfig byTxCountOnly(int maxTransactionsPerBlock) {
        return new BlockBuilderConfig(maxTransactionsPerBlock, 0L);
    }

    public int getMaxTransactionsPerBlock() {
        return maxTransactionsPerBlock;
    }

    public long getMaxBlockGas() {
        return maxBlockGas;
    }

    public boolean hasGasLimit() {
        return maxBlockGas > 0;
    }
}
