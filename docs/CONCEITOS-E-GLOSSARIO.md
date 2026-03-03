# Conceitos e glossário do DepChain

Este documento resume os conceitos base do projeto e a terminologia usada no código e no GUIDE.md, para ter uma compreensão total do sistema.

---

## 1. O que é o projeto no geral

O **DepChain** é uma “blockchain” simplificada onde:

- **Vários nós** precisam de **acordar** na mesma sequência de “blocos” (aqui: strings a acrescentar).
- A rede é **má** (UDP: perdas, atrasos, duplicados).
- Alguns nós podem ser **maliciosos** (dizem uma coisa e fazem outra).
- Não podes usar TLS; a segurança vem de **assinaturas** em cima do UDP.

Ou seja: é um sistema **distribuído** em que o objetivo é **consenso** (todos concordam no mesmo valor) mesmo com falhas de rede e nós maliciosos.

---

## 2. Members vs Clientes vs Membership

### Members são "pessoas" pré-definidas?

Não. **Members** não são pessoas — são **servidores / processos** (programas a correr numa máquina). O que é "pré-definido" é **quem são** esses servidores: uma lista fixa de **n** nós (IDs, endereços, chaves públicas) conhecida antes de arrancar.

- **Members** = conjunto fixo de **nós do sistema** que correm o consenso e mantêm o "livro de registos" (o array de strings). São como os "validadores" da blockchain.
- Cada member tem duas portas: uma para **consenso** (falarem entre si) e outra para **clientes** (receber pedidos de append).
- No código: cada `BlockchainMember` é um nó completo (consenso + receção de clientes + array).

### Clientes são "pessoas reais" que querem escrever mensagens?

Sim, no sentido em que **clientes** são quem **usa** o sistema: a aplicação (ou utilizador) que quer **acrescentar uma string** ao registo. Não participam no consenso; só enviam pedidos e esperam resposta.

- O **cliente** chama `append("minha mensagem")`.
- A biblioteca `DepChainClient` envia esse pedido por UDP para a **porta de clientes** de **todos** os members (porque o cliente não sabe quem é o leader).

### Para onde vão as mensagens? Quem as recebe?

- O **pedido** (a string a acrescentar) é enviado **para todos os members** (cada um recebe na sua porta de clientes).
- Os members guardam o pedido numa fila. O **leader** da view atual retira um pedido da fila, monta um **bloco** (requestId + string) e **propõe** esse bloco no consenso.
- O **consenso** (HotStuff) faz todos os members **acordar** nesse bloco (votos, QCs, 4 fases).
- Quando o bloco é **decidido**, **cada member** aplica-o localmente: acrescenta a string ao seu **array** (o "livro de registos") e, se tiver o endereço do cliente guardado, envia uma **resposta** (índice ou falha) por UDP **de volta ao cliente**.

Resumo do fluxo: **Cliente** → (pedido) → **Todos os members** → (só o leader propõe) → **Consenso entre members** → (decisão) → **Cada member** atualiza o array e **um deles** responde ao **cliente**. As "mensagens" (strings) ficam no **array partilhado** que todos os members mantêm; a resposta vai para quem fez o pedido (o cliente).

### Membership

- É a **lista estática** de todos os members: IDs, endereços (host + porta de consenso e de clientes) e **chave pública** de cada um.
- Conhecida **antes** de arrancar o sistema; usada para saber onde enviar mensagens e para verificar assinaturas.
- No código: `Membership` guarda `memberIds`, `addresses`, `publicKeys`, e calcula `n`, `f`, `getQuorumSize()`, `getLeaderId(view)`.

---



- São os **nós que participam no consenso** e mantêm o “livro de registos” (o array de strings).
- Cada member tem:
  - uma **porta de consenso** (UDP + Fair Loss + APL) para falar com os outros members;
  - uma **porta de clientes** para receber pedidos de append.
- Todos correm a mesma lógica (HotStuff); um deles é o **leader** da view atual.
- No código: `BlockchainMember` = um nó completo (consenso + clientes + blockchain).

### Clientes

- São **quem usa** o sistema (a aplicação do utilizador).
- Não participam no consenso. Só:
  - enviam pedidos “append esta string” para **todos** os members (não sabem quem é o leader);
  - esperam uma resposta (índice ou falha).
- No código: `DepChainClient` = biblioteca que a aplicação usa para `append(string)`.

### Membership

- É a **lista fixa** de quem são os members: IDs, endereços (host+porta) e **chave pública** de cada um.
- Conhecida **antes** de arrancar o sistema.
- No código: `Membership` guarda `memberIds`, `addresses`, `publicKeys`, e calcula `n`, `f`, `getQuorumSize()`, `getLeaderId(view)`.

**Resumo:** **members** = nós que decidem; **clientes** = quem pede; **membership** = “quem são os members e onde estão”.

---

## 3. O que é um “Bizantino”

Um nó **Bizantino** é um nó **malicioso** ou **em falha arbitrária**: pode enviar mensagens erradas, contraditórias ou inventadas (não é só “crash”).

- **BFT** = Byzantine Fault Tolerant = o sistema continua **correto** mesmo com até **f** nós Bizantinos.
- Para isso é preciso **n ≥ 3f + 1** nós no total. Ex.: n=4 → f=1 (toleramos 1 Bizantino); n=7 → f=2.
- A matemática: com 2f+1 votos “certos”, mesmo que f votem mal, a maioria ainda é de nós corretos, por isso o valor decidido é válido.

No projeto: `f = (n-1)/3` em `Membership`; os votos no consenso são **2f+1** (quorum).

### Matemática BFT: o que significa cada letra e porquê

- **n** = número total de **members** (réplicas) no sistema. Ex.: n = 4.
- **f** = número máximo de nós **Bizantinos** que o sistema tolera. Calculado como **f = (n−1)/3** (divisão inteira). Ex.: n=4 → f=1; n=7 → f=2.
- **3f + 1** = número **mínimo** de nós necessários para tolerar **f** Bizantinos. A fórmula garante que mesmo que **f** nós mintam, sobram **n − f ≥ 2f + 1** nós corretos, e **2f + 1** é maior que f, logo a maioria dos votos é sempre de nós corretos.
- **2f + 1** = **quorum** = número de votos (assinaturas) necessários para um QC. Com 2f+1 votos, pelo menos **f+1** são de nós corretos, portanto a decisão está alinhada com a maioria honesta.

**Contexto:** Com **n ≥ 3f + 1**, qualquer conjunto de 2f+1 nós contém sempre uma maioria de corretos; assim o valor decidido é válido mesmo com até f Bizantinos.

---

## 4. Votos: quem vota e como

### Quem vota

- **Só os members (réplicas)** votam. Clientes não votam.
- Em cada **fase** do HotStuff (PREPARE, PRE-COMMIT, COMMIT), o leader propõe algo; as **réplicas** (cada uma) decidem se aceitam e enviam um **voto** (assinado).

### Como votam

1. O **leader** envia uma proposta (bloco + QC da fase anterior) a **todos** (mensagem PREPARE, PRE_COMMIT ou COMMIT).
2. Cada **réplica** (não-leader):
   - verifica a proposta (view, leader, lock, QC);
   - se aceitar, assina `(view, phase, blockHash)` com a sua **chave privada** e envia um **VOTE** só para o **leader** (não broadcast).
3. O **leader**:
   - recebe votos;
   - verifica cada **assinatura** com a chave pública do `senderId` (em `Membership`);
   - quando tem **2f+1** votos válidos, junta-os num **Quorum Certificate (QC)** e envia a mensagem da **fase seguinte** a todos (ou DECIDE no fim).

No código: `sendVote` envia o voto só ao leader (`network.sendTo(leaderId, wire)`); `handleVote` no leader verifica assinatura, junta em `votes`, e quando `votes.size() >= getQuorumSize()` constrói o QC e avança.

---

## 5. O que é o leader

- O **leader** é **um** dos members que, numa **view** (número de ronda), tem o papel especial:
  - **Propor** o bloco (retira da fila de pedidos e envia PREPARE).
  - **Receber** os votos das réplicas, juntar 2f+1 num QC e enviar a mensagem da próxima fase (PRE_COMMIT, COMMIT, DECIDE).
- Quem é o leader é **determinístico**: `leaderId = memberIds.get(viewNumber % n)`. Ou seja, view 0 → member 0, view 1 → member 1, etc. (`Membership.getLeaderId(viewNumber)`).
- Se o leader falhar ou a rede atrasar, o **pacemaker** (timeout) faz as réplicas avançar de **view**; o novo leader é outro member e o processo continua.

---

## 6. O que é o consenso

- **Consenso** = todos os nós **corretos** acordam no **mesmo valor** (aqui: no mesmo bloco / mesma sequência de blocos).
- O **algoritmo** usado é o **Basic HotStuff**: o bloco passa por **4 fases** (PREPARE → PRE-COMMIT → COMMIT → DECIDE); em cada fase é preciso um **QC** (2f+1 assinaturas) para avançar; na fase DECIDE o bloco é “decidido” e aplicado (upcall `onDecide(block)`).
- Objetivo: mesmo com perdas de mensagens e até f nós Bizantinos, todos aplicam o **mesmo** bloco na **mesma** view.

---

## 7. O que é o Fair Loss

- **Fair Loss** é um modelo de rede em que:
  - a mensagem **pode** perder-se;
  - mas se enviarmos **várias vezes**, “eventualmente” **pelo menos uma** chega (não é perda total permanente).
- No projeto: em cima do **UDP** (que não garante nada), o `FairLossLink` faz **retransmissões** (envia uma vez e depois mais algumas com atraso) para aproximar este comportamento.
- O Fair Loss **não** deduplica: podem chegar **cópias** da mesma mensagem; a camada de cima (APL) é que garante “no máximo uma entrega por (sender, messageId)” e só **depois** de verificar a assinatura.

---

## 8. Assinaturas

### O que são as assinaturas em concreto?

No projeto usamos **assinaturas digitais** com criptografia assimétrica (RSA) e o algoritmo **SHA256withRSA** (Java):

- **Par de chaves:** Cada member tem uma **chave privada** (secreta) e uma **chave pública** (conhecida por todos, na `Membership`). Só quem tem a chave privada consegue **assinar**; qualquer um com a chave pública consegue **verificar**.
- **Assinar** = aplicar uma função matemática aos **bytes do conteúdo** (ex.: `senderId + messageId + payload`) usando a **chave privada**. O resultado é um blob de bytes chamado **assinatura**. Se o conteúdo mudar um bit, a assinatura deixa de ser válida.
- **Verificar** = usando a **chave pública** do suposto remetente, aplicar a função inversa: dados o conteúdo e a assinatura, a verificação devolve "válida" ou "inválida". Se for válida, sabemos que (1) o conteúdo não foi alterado e (2) só quem tem a chave privada desse nó poderia ter produzido aquela assinatura.

Assim, **não é possível** um nó A falsificar uma mensagem como se viesse do nó B (porque A não tem a chave privada de B) nem alterar o conteúdo sem a assinatura falhar na verificação.

Há **dois** sítios onde as assinaturas entram:

### A) APL (Authenticated Perfect Link) – transporte entre members

- Cada mensagem entre members inclui: `senderId`, `messageId`, `payload`.
- O **conteúdo a assinar** é `senderId + messageId + payload` (em `APLMessage.getSignedContent()`).
- O **remetente** assina com a sua **chave privada**; envia na rede: conteúdo + assinatura.
- O **destinatário**:
  1. Obtém a chave **pública** do `senderId` (em `Membership`);
  2. **Verifica** a assinatura; se falhar, descarta;
  3. **Só depois** faz deduplicação por `(senderId, messageId)`.
- Objetivo: **autenticidade** (quem enviou) e **integridade** (não foi alterado). Assim, um atacante não pode falsificar mensagens de outro nó nem fazer descartar a mensagem legítima com um messageId falsificado (por isso a ordem “primeiro assinatura, depois deduplicação”).

### B) Votos no HotStuff

- Cada **voto** (PREPARE_VOTE, PRE_COMMIT_VOTE, COMMIT_VOTE) é assinado sobre `(view, phase, blockHash)`.
- A réplica assina com a sua chave privada; o **leader** verifica cada voto com a chave pública dessa réplica (`verifyVoteSignature`).
- O **QC** é um conjunto de **2f+1** assinaturas (uma por réplica). Qualquer um que receba uma mensagem com um QC pode verificar cada assinatura com `Membership.getPublicKey(replicaId)` (`verifyQC` no `HotStuffReplica`).

**Resumo:** assinaturas servem para **não poderem falsificar** quem disse o quê (tanto nas mensagens de rede como nos votos do consenso).

---

## 9. Glossário (naming)

| Termo | Significado no projeto |
|-------|-------------------------|
| **Member** | Nó que participa no consenso e tem o array (blockchain); `BlockchainMember`. |
| **Client** | Aplicação que chama `append(string)`; usa `DepChainClient`. |
| **Membership** | Lista estática de members (ids, endereços, chaves públicas); `Membership`. |
| **Réplica** | O mesmo que “member” no contexto do consenso; cada instância de `HotStuffReplica`. |
| **Leader** | O member que numa view propõe e agrega votos; `getLeaderId(view)`. |
| **View** | Número de ronda do consenso; em cada view há um leader; quando há timeout, view aumenta. |
| **Block** | Unidade que se está a decidir (aqui: view + payload; payload = string ou requestId+string). |
| **QC (Quorum Certificate)** | Certificado de que 2f+1 réplicas votaram (prepare/pre-commit/commit); contém as assinaturas. |
| **PREPARE / PRE_COMMIT / COMMIT / DECIDE** | As 4 fases do HotStuff; em cada uma há proposta → votos → QC → mensagem seguinte. |
| **Lock (lockedBlockHash)** | Após pre-commit, a réplica “trava” nesse bloco; só vota em propostas compatíveis (segurança). |
| **Upcall / onDecide** | Quando o bloco é decidido (mensagem DECIDE), o consenso chama `decideCallback.onDecide(block)`; é aí que o `BlockchainMember` faz append ao array e responde ao cliente. |
| **Fair Loss** | Link que retransmite para “eventualmente” a mensagem chegar; não garante entrega única. |
| **APL** | Authenticated Perfect Link: entrega no máximo uma vez por (sender, messageId), com verificação de assinatura antes de deduplicar. |
| **BFT / Bizantino** | Tolerância a até f nós maliciosos; n ≥ 3f+1; votos em quorum 2f+1. |

---

*Ver também: [GUIDE.md](GUIDE.md) para a arquitetura completa e cada ficheiro do projeto.*
