# DepChain — Stage 1

Simplified permissioned blockchain with Basic HotStuff BFT consensus (SEC 2025–2026).

## Build and test

```bash
mvn clean compile test
```

## Run demo

Starts 4 blockchain members and 1 client; the client appends 3 strings.

```bash
mvn exec:java -Dexec.mainClass="depchain.demo.Demo"
```

Or after packaging:

```bash
mvn package
java -jar target/depchain-1.0-SNAPSHOT.jar
```

To run with **5 separate processes** (one JVM per member and one for the client), see [docs/CORRER-MANUALMENTE.md](docs/CORRER-MANUALMENTE.md): `java -cp target/classes depchain.Main member 0` (and 1, 2, 3) then `depchain.Main client`.

## Run tests (including demos for dependability)

- **Unit / integration**
  - `mvn test` — runs all tests.

- **APL (Authenticated Perfect Links)**
  - `mvn test -Dtest=AuthenticatedPerfectLinkTest` — send/receive, corruption rejection, deduplication.

- **Consensus (HotStuff)**
  - `mvn test -Dtest=HotStuffIntegrationTest` — 4 replicas decide blocks; view change after leader timeout.
  - `mvn test -Dtest=ByzantineTest` — one replica sends invalid votes; correct replicas still decide (Byzantine detection).
  - `mvn test -Dtest=IntrusiveNetworkTest` — consensus with injectable network (drop probability 0%; can be increased in code for demos).

- **Client**
  - `mvn test -Dtest=ClientIntegrationTest` — client broadcasts append to members; leader proposes; all decide and respond.

## Dependability and security mechanisms (demos)

1. **Byzantine behaviour**  
   `ByzantineTest`: one replica uses `CorruptingConsensusNetwork` so its votes have invalid signatures. The leader collects only valid votes; quorum is still reached and the correct value is decided. Run: `mvn test -Dtest=ByzantineTest`.

2. **View change after leader timeout**  
   `HotStuffIntegrationTest#viewChangeAfterLeaderTimeout`: replica 0 (leader) never proposes; after a timeout all replicas move to the next view; replica 1 becomes leader and proposes; all decide. Run: `mvn test -Dtest=HotStuffIntegrationTest#viewChangeAfterLeaderTimeout`.

3. **Message drop (intrusive test harness)**  
   `IntrusiveNetworkTest` uses `DropConsensusNetwork` to inject loss on sends. With 0% drop the test passes; for demos, increase the drop probability in the test to observe resilience (retries and view change). Run: `mvn test -Dtest=IntrusiveNetworkTest`.

4. **Invalid client messages**  
   Client protocol expects: type byte, requestId, string length, string. Malformed or oversized messages are ignored by `ClientProtocol.parseRequest` and never trigger a proposal.

## Project layout

- `config` — static membership, node addresses, PKI.
- `transport` — UDP, Fair Loss Link.
- `links` — Authenticated Perfect Links (sign then deduplicate).
- `consensus` — Basic HotStuff (4 phases: PREPARE, PRE_COMMIT, COMMIT, DECIDE), pacemaker.
- `blockchain` — in-memory append-only log; `BlockchainMember` wires consensus, client listener, and responses.
- `client` — client library: broadcast append, wait for response.
- `demo` — demo application (4 members + 1 client).

## Report

The project includes a short report in LNCS format: `report/report.tex`. It covers design justification, threat analysis and protection mechanisms, and dependability guarantees. To build the PDF (requires a LaTeX installation with `llncs` class):

```bash
cd report && pdflatex report.tex
```

## Requirements

- Java 11+
- Maven 3.6+

No external libraries beyond JUnit (test). Crypto: Java Crypto API (RSA). No TLS; communication over UDP as per assignment.
