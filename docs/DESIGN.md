# DepChain Stage 1 — Design and Basic HotStuff Understanding

## Basic HotStuff (Algorithm 2) — Summary

- **Model**: Partially synchronous, Byzantine fault tolerant. Up to \(f\) Byzantine nodes among \(n \geq 3f+1\).
- **Views**: Each view has a single leader. Leader rotation: e.g. `leaderId = viewNumber % n`.
- **Four phases** (must be implemented as explicit message rounds):
  1. **PREPARE**: Leader sends proposal (block + highQC). Replicas vote; leader collects \(2f+1\) votes into prepareQC.
  2. **PRE-COMMIT**: Leader sends proposal with prepareQC. Replicas vote; leader collects votes into preCommitQC.
  3. **COMMIT**: Leader sends proposal with preCommitQC. Replicas vote; leader collects votes into commitQC.
  4. **DECIDE**: Leader sends **DECIDE message** containing commitQC to all replicas. **Only when a replica receives this DECIDE message** does it: (1) execute the command (upcall to blockchain layer); (2) advance view. This is a real message round, not local decision upon having commitQC.

- **Lock**: Replicas lock on a block when they pre-commit; they only vote for proposals that extend or match their locked block (safety).
- **QC (Quorum Certificate)**: Certifies that \(2f+1\) replicas voted. Can be threshold signature (single aggregated cert) or collection of individual signatures (acceptable for Stage 1).
- **Pacemaker**: Timeout per view; if view does not progress, replicas switch to next view; new leader uses highest QC to continue.

## Design Challenges Identified

1. **Order of operations in APL**: Authentication must happen before deduplication. Otherwise a Byzantine node could forge message IDs and cause legitimate messages to be discarded when they arrive.
2. **Client does not know leader**: Client must broadcast requests to all members; only the current leader processes and proposes. Replicas ignore client requests when not leader.
3. **DECIDE as explicit round**: Implementation must send and receive DECIDE messages; upcall and view advance only on DECIDE receipt, not on local commitQC.
4. **View change**: On timeout, replicas must move to new view and new leader; new leader must gather highest QC (from replicas or from its state) and continue from there.
5. **Crash vs Byzantine**: Step 4 handles crash with timeout-based FD; Step 5 adds signature verification and Byzantine-resistant logic (reject invalid votes/proposals).

## Layered Communication

- **UDP**: Raw datagrams (loss, delay, duplicate, corruption).
- **Fair Loss Link**: Best-effort send with retries; may duplicate. Built on UDP.
- **APL**: On receive: (1) verify signature with sender's public key; (2) then deduplicate by authenticated message ID. No separate Perfect Link that deduplicates before authentication.

## Client–BFT Gap

- Client: `<append, string>`; expects confirmation (and optionally index).
- BFT: Propose(block), Decide(block) upcall.
- Design: Client broadcasts append request to all members. Leader enqueues and proposes block(s). When replica receives DECIDE, it appends to array and may send confirmation to client(s). Client retries with timeout; service is idempotent (re-send same confirmation if already executed).
