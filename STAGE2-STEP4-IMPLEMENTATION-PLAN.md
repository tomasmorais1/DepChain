# Stage 2 — Step 4 Implementation Plan (Consensus -> Transaction Order -> Ledger)

Este plano segue o enunciado (SEC-2526 Stage 2 v2):
- Step 4: ligar o consenso aos componentes do Stage 2;
- o consenso passa a decidir a ordem das transações executadas e appendadas.

---

## 0) Definition of Done (Step 4)

No fim deste step, cada replica deve:
1. receber transacoes de clientes;
2. participar no consenso sobre um bloco de transacoes;
3. aplicar o bloco apenas apos DECIDE;
4. executar as transacoes na ordem decidida;
5. appendar/persistir o bloco no ledger;
6. responder ao cliente com resultado apos commit local.

---

## 1) Refactor de payload de consenso (de String para batch de transacoes)

### Objetivo
Substituir o payload atual (requestId + string) por payload de transacoes.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java
- src/main/java/depchain/client/ClientProtocol.java
- src/main/java/depchain/consensus/Block.java

### Tarefas
- Criar DTO wire para batch (ex.: TxProposalPayload).
- Incluir no payload: requestId(s), lista de Transaction, metadata minima.
- Garantir serializacao deterministica (mesmo input => mesmos bytes).

### Critério de aceitação
- Duas replicas desserializam os mesmos bytes para o mesmo lote de transacoes.

---

## 2) Entrada de transacoes no membro (mempool local)

### Objetivo
Ter pending tx pool por replica para o lider montar propostas.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java
- src/main/java/depchain/client/DepChainClient.java
- src/main/java/depchain/client/ClientProtocol.java

### Tarefas
- Introduzir fila/estrutura para transacoes pendentes (nao strings).
- Manter requestId -> cliente para responder apos DECIDE.
- Rejeitar tx claramente invalidas (gasPrice/gasLimit <= 0, campos nulos).

### Critério de aceitação
- Cada membro recebe e armazena transacoes validas sem executar de imediato.

---

## 3) Leader propose loop com lote de transacoes

### Objetivo
Quando lider, propor um bloco com varias transacoes (nao uma string).

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java
- src/main/java/depchain/consensus/HotStuffReplica.java

### Tarefas
- Selecionar lote da mempool (limite por bloco).
- Ordenar lote pela politica do Step 3 (Transaction.FEE_PRIORITY + nonce).
- Construir Block(view, payloadTxBatch) e chamar replica.propose(...).

### Critério de aceitação
- O payload proposto contem lote ordenado e validavel por replicas.

---

## 4) Validator de bloco no consenso (anti-leader byzantine)

### Objetivo
Replicas so votam em blocos cujo payload seja valido e conhecido.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java
- (callback BlockValidator ja usado no construtor do HotStuffReplica)

### Tarefas
- Atualizar isVerifiedClientBlock para o novo formato de payload (batch).
- Validar que txs do payload existem/foram recebidas e assinatura batem.
- Rejeitar payload malformado, txs forjadas, ordem inconsistente se aplicavel.

### Critério de aceitação
- Um lider byzantino nao consegue forcar DECIDE de transacoes forjadas.

---

## 5) Upcall DECIDE -> executar e appendar no ledger

### Objetivo
No DECIDE, aplicar bloco decidido ao pipeline do Step 3.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java
- src/main/java/depchain/blockchain/BlockchainLedger.java
- src/main/java/depchain/blockchain/BlockJsonStore.java

### Tarefas
- Em onDecide/drainPendingDecides: desserializar payload para List<Transaction>.
- Chamar ledger.appendBlock(transactionsDecididas).
- Persistir LedgerBlock em JSON (height.json).
- Responder aos clientes apos commit local.

### Critério de aceitação
- A ordem executada e exatamente a ordem decidida pelo consenso.

---

## 6) Idempotencia e replay safety

### Objetivo
Impedir dupla aplicacao do mesmo DECIDE.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainMember.java

### Tarefas
- Guardar IDs/hash de blocos ja aplicados.
- Se DECIDE duplicado chegar, ignorar reexecucao e manter resposta consistente.

### Critério de aceitação
- Repetir DECIDE nao altera saldo/nonce duas vezes.

---

## 7) Substituir BlockchainService Stage 1 pelo ledger Stage 2

### Objetivo
Deixar de usar append-only string log como caminho principal.

### Ficheiros principais
- src/main/java/depchain/blockchain/BlockchainService.java
- src/main/java/depchain/blockchain/BlockchainMember.java

### Tarefas
- Encapsular BlockchainLedger + BlockJsonStore dentro de BlockchainService (ou ligar direto no Member).
- Expor metodos para estado atual, altura e blocos persistidos.
- Manter compatibilidade minima com testes antigos (se necessario, adaptar testes).

### Critério de aceitação
- O estado canonico passa a ser o do ledger de transacoes, nao log de strings.

---

## 8) Testes de integracao obrigatorios (Step 4)

Criar/atualizar testes com JUnit para provar o requisito do enunciado:

1. ConsensusLedgerIntegrationTest
- Proposta de lote -> DECIDE -> todas replicas com mesmo height/blockHash.

2. DecideOrderRespectedTest
- Com txs concorrentes, ordem final no bloco segue ordem decidida pelo consenso.

3. ReplayDecideIdempotentTest
- Mesmo bloco decidido duas vezes nao duplica efeitos.

4. ClientEndToEndTransactionTest
- Cliente recebe sucesso/resultado apenas depois do commit decidido.

5. PersistedBlocksAfterDecideTest
- Blocos decididos aparecem em JSON e reabrem corretamente.

---

## 9) Sequencia recomendada de implementacao (para minimizar risco)

1. DTO de payload de tx batch + serializacao/desserializacao.
2. Atualizar BlockchainMember (mempool, propose, validator, onDecide).
3. Integrar BlockchainLedger + BlockJsonStore no caminho DECIDE.
4. Idempotencia/replay safety.
5. Atualizar cliente/protocolo de requests se ainda estiver string-only.
6. Fechar testes de integracao.

---

## 10) Checklist final (Step 4 completo)

- [x] Consenso decide blocos com transacoes (nao strings).
- [x] Replicas executam apenas apos DECIDE.
- [x] Ordem de execucao = ordem decidida.
- [x] LedgerBlock e persistido para cada bloco decidido.
- [x] Respostas ao cliente acontecem apos commit local.
- [x] Replay nao reexecuta.
- [x] Testes de integracao do Step 4 passam.
