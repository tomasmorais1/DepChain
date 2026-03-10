# DepChain — Stage 1

**Requirements:** Java 11+, Maven 3.6+

---

## How to run the demos

### Main demo (5 separate JVMs, one command)

From the **project root**:

```bash
./run-multijvm-demo.sh
```

The script compiles the project, generates a key file if it does not exist, starts 4 members in the background, runs the client (you see the client output in the terminal: 3 appends with indices 0, 1, 2), then stops the members. No need to open multiple terminals.

### Manual 5 terminals

Compile with `mvn compile`. Run once: `java -cp target/classes depchain.Main genconfig` (creates the key file). Then open 4 terminals and run `java -cp target/classes depchain.Main member 0`, `member 1`, `member 2`, `member 3` respectively. When all four show "Member X running", open a 5th terminal and run `java -cp target/classes depchain.Main client`.

---

## How to run the tests

Run **all tests**:

```bash
mvn clean test
```

Run **individual test classes** (useful to demonstrate specific behaviour):

| Command | What it exercises |
|--------|--------------------|
| `mvn test -Dtest=AuthenticatedPerfectLinkTest` | APL: send/receive, reject corrupted messages, deduplication. |
| `mvn test -Dtest=HotStuffIntegrationTest` | Consensus: 4 replicas decide blocks; view change after leader timeout. |
| `mvn test -Dtest=ByzantineTest` | One replica sends invalid votes; correct replicas still decide (Byzantine tolerance). |
| `mvn test -Dtest=IntrusiveNetworkTest` | Consensus with injectable message drop (retries / view change). |
| `mvn test -Dtest=ClientIntegrationTest` | Client broadcasts append to members; leader proposes; all decide and respond. |
| `mvn test -Dtest=MultiProcessConfigIntegrationTest` | Key-file flow: write keys, load 4 members from file, client append succeeds. |
