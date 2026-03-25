# Stage 2 — Step 5: Byzantine robustness & approval frontrunning (tests)

This document records the **Step 5** test coverage for SEC-2526 Stage 2: behaviour under **Byzantine** participants (replicas and clients) and **ERC-20 approval frontrunning** mitigation (IST Coin).

---

## 1. What was already in the project (keep as Step 5 evidence)

### Consensus / replicas

- **`ByzantineTest`** + **`CorruptingConsensusNetwork`** — one replica sends **invalid/corrupt** threshold votes; honest replicas still gather **2f+1** valid shares and **decide**.
- **`ReplicaFailingTest`** + **`VoteDroppingConsensusNetwork`** / **`SilentConsensusNetwork`** — one replica **drops all votes** (others still decide) vs **two silent** replicas (**no quorum**, no decision).
- **`IntrusiveNetworkTest`** — **`DropConsensusNetwork`** with **0% drop** (deterministic harness). The old random-drop test was removed as inherently flaky without consensus-layer retries.

### Contract / approval frontrunning

- **`ISTCoinBesuExecutionTest`** + **`ISTCoin.sol`** — guarded `approve`: a non-zero allowance must be **reset to zero** before setting another non-zero value (mitigates the classic approval frontrunning pattern).

### Execution layer

- **`TransactionExecutorTest`** — e.g. **wrong nonce** rejected without corrupting state.

### End-to-end client

- **`ClientIntegrationTest`** — honest **`appendTransaction`** through replicas → ledger grows.

---

## 2. Alterations added for Step 5 (new / tightened)

### New test classes

| Class | What it demonstrates |
|--------|----------------------|
| **`ClientProtocolByzantineTest`** | **ETH wire:** tampered transaction body with the **original** signature → verification **fails**; signature from the **wrong key** or **`from` ≠ signer** → **fails**; well-formed signed tx → **passes**. |
| **`VerifiedBatchLeaderInjectionTest`** | **Anti–Byzantine leader injection:** same logical rule as `BlockchainMember#isVerifiedClientBlock` — a block whose batch references a `requestId`/command pair that was **not** stored as verified, or **forges** a different command for a known `requestId`, is **rejected** (unit test, no UDP). |
| **`ByzantineClientIntegrationTest`** | **UDP to all replicas:** broadcast a **malicious** ETH request (tampered body, signature for another body) → **no** new ledger block; plus a **honest** `appendTransaction` path still **commits**. |

### Existing test touched for clarity in reports

- **`ISTCoinBesuExecutionTest`** — class-level **`@DisplayName`** and javadoc tying the suite to **Step 5** / approval frontrunning; main test method **`@DisplayName`** for the reset-to-zero rule.

### `pom.xml` (from JDK troubleshooting)

- Comment that **Besu 25.x** dependencies are **Java 21 bytecode** and **`JAVA_HOME` must be JDK 21+** for `mvn` compile/test.

---

## 3. Comparison notes (colleague project / PDF / report)

- The **Stage 2 v2 PDF** was not available in-repo path during implementation; align final checklist with the **official PDF** before submission.
- **Colleague’s `Depchain` (last year)** used similar ideas (Byzantine client tests, consensus tests) but often **mock-heavy** or **non-cryptographic**; this project’s Step 5 tests emphasise **real signature verification**, **real replicas**, and **ISTCoin** behaviour.

---

## 4. Optional extras (only if the enunciado asks for them)

- **Replay** of the same client `requestId` / duplicate enqueue behaviour.
- **Multi-transaction** ledger scenarios involving IST (e.g. several calls in one decided batch) — heavier and slower.

---

## 4.1 Multi-tx blocks and fee order (PDF alignment)

Described in **`STAGE2-STEP4-IMPLEMENTATION-PLAN.md`** (section **3.1**): leader batching, `TransactionBatchOrder`, and `TransactionBatchOrderTest`. Step 5 tests that build on verified batches remain listed above.

---

## 5. How to run the Step 5–focused suite

Requires **JDK 21** (`java -version` and `mvn -v` should show 21).

```bash
mvn test -Dtest=ClientProtocolByzantineTest,VerifiedBatchLeaderInjectionTest,ByzantineClientIntegrationTest,ByzantineTest,ReplicaFailingTest,ISTCoinBesuExecutionTest
```

To include the general client integration test:

```bash
mvn test -Dtest=ClientProtocolByzantineTest,VerifiedBatchLeaderInjectionTest,ByzantineClientIntegrationTest,ByzantineTest,ReplicaFailingTest,ISTCoinBesuExecutionTest,ClientIntegrationTest
```

Full suite:

```bash
mvn clean test
```

---

## 6. Summary

Step 5 is covered by **replica Byzantine** tests (bad votes, silent / vote-drop), **client Byzantine** tests (invalid ETH signature binding, tampered wire, leader injection block rule), and **ISTCoin** Besu tests for **approval frontrunning** mitigation, plus existing executor and integration tests.
