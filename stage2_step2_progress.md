# DepChain Stage 2 - Step 2 Progress

## Status

Step 2 completed.

## Scope (Step 2 only)

Only Step 2 was addressed:

- Implement a frontrunning-resistant ERC-20 smart contract in Solidity.
- Keep the work ready to be tested with the Besu EVM workflow.

No Step 3 work was done.

## Implemented

Created:

- `src/main/resources/contracts/ISTCoin.sol`
- `src/main/resources/contracts/ISTCoin.runtime.hex`
- `src/test/java/depchain/evm/ISTCoinBesuExecutionTest.java`

Token properties:

- Name: `IST Coin`
- Symbol: `IST`
- Decimals: `2`
- Total supply: `100_000_000 * 10^2`
- Constructor mints full supply to deployer (`msg.sender`)

## Frontrunning mitigation

The contract uses a guarded `approve` method:

- Allowed transitions:
  - `0 -> N`
  - `N -> 0`
- Forbidden transition:
  - `N -> M` where both values are non-zero

This prevents the classic ERC-20 approval frontrunning pattern.

Helper methods were also included:

- `increaseAllowance(spender, addedValue)`
- `decreaseAllowance(spender, subtractedValue)`

## Suggested validation sequence (Step 2)

Implemented with Besu EVM execution in `ISTCoinBesuExecutionTest`:

1. `approve(spender, 100)` succeeds
2. `approve(spender, 50)` without reset reverts
3. `approve(spender, 0)` succeeds
4. `approve(spender, 50)` after reset succeeds

Validation detail:

- The test executes EVM calls and validates allowance evolution through direct storage slot inspection of `_allowances[owner][spender]`.
- Execution setup follows the Lab2 pattern by loading the runtime bytecode directly in the EVM executor.

## Local environment note (JDK 21)

The Besu artifacts used by the Step 2 EVM test require Java 21.

Current local version observed in terminal:

- `java -version` -> `17.0.4.1`

### Switch to JDK 21 (macOS)

If JDK 21 is already installed:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

If JDK 21 is not installed, install first (example with Homebrew):

```bash
brew install openjdk@21
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Run Step 2 test with Java 21:

```bash
mvn -Pstep2-evm -Dtest=depchain.evm.ISTCoinBesuExecutionTest test
```

Latest validation result:

- `ISTCoinBesuExecutionTest` passed locally with Java 21.
