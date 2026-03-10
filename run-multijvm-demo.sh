#!/usr/bin/env bash
# DepChain multi-JVM demo: 4 members + 1 client, um único comando.
# Para o professor / avaliação: corre tudo automaticamente (compila, genconfig se necessário, arranca members, client, termina).
# Uso: ./run-multijvm-demo.sh   (na raiz do projeto)

set -e
cd "$(dirname "$0")"

echo "=== DepChain multi-JVM demo (automático) ==="
echo ""

# 1. Compilar
echo "[1/4] Compilando..."
mvn -q compile
CP="target/classes"
KEY_FILE="depchain-multijvm.keys"

# 2. Gerar chaves se não existirem
if [ ! -f "$KEY_FILE" ]; then
  echo "[2/4] A gerar ficheiro de chaves ($KEY_FILE)..."
  java -cp "$CP" depchain.Main genconfig
else
  echo "[2/4] Ficheiro de chaves já existe ($KEY_FILE)."
fi

# 3. Arrancar os 4 members em background
echo "[3/4] A arrancar 4 members em background..."
pids=()
for i in 0 1 2 3; do
  java -cp "$CP" depchain.Main member $i > "member-$i.log" 2>&1 &
  pids+=($!)
done

# Esperar que os members estejam a escutar
sleep 5
echo "    Members a correr (PIDs: ${pids[*]})."
echo ""

# 4. Correr o client (em foreground para ver a saída)
echo "[4/4] A correr o client..."
echo "---"
java -cp "$CP" depchain.Main client
CLIENT_EXIT=$?
echo "---"
echo ""

# Parar os members
echo "A parar os members..."
for pid in "${pids[@]}"; do
  kill "$pid" 2>/dev/null || true
done
wait 2>/dev/null || true
echo "Demo terminada (client exit code: $CLIENT_EXIT)."
echo "Logs dos members: member-0.log ... member-3.log"
exit $CLIENT_EXIT
