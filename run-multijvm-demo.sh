#!/usr/bin/env bash
# DepChain multi-JVM demo: 4 members + 1 client, single command.
# For evaluation: runs everything automatically (compile, genconfig if needed, start members, client, then exit).
# Usage: ./run-multijvm-demo.sh   (from project root)

set -e
cd "$(dirname "$0")"

echo "=== DepChain multi-JVM demo (automatic) ==="
echo ""

# 1. Compile + runtime classpath (Besu, web3j, etc. are not in target/classes alone)
echo "[1/4] Compiling..."
mvn -q compile
mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/cp.txt
CP="target/classes:$(cat target/cp.txt)"
KEY_FILE="depchain-multijvm.keys"

# 2. Generate keys if they do not exist
if [ ! -f "$KEY_FILE" ]; then
  echo "[2/4] Generating key file ($KEY_FILE)..."
  java -cp "$CP" depchain.Main genconfig
else
  echo "[2/4] Key file already exists ($KEY_FILE)."
fi

# 3. Start the 4 members in background
echo "[3/4] Starting 4 members in background..."
pids=()
for i in 0 1 2 3; do
  java -cp "$CP" depchain.Main member $i > "member-$i.log" 2>&1 &
  pids+=($!)
done

# Wait for members to be listening
sleep 5
echo "    Members running (PIDs: ${pids[*]})."
echo ""

# 4. Run the client (in foreground to see output)
echo "[4/4] Running the client..."
echo "---"
java -cp "$CP" depchain.Main client
CLIENT_EXIT=$?
echo "---"
echo ""

# Stop the members
echo "Stopping the members..."
for pid in "${pids[@]}"; do
  kill "$pid" 2>/dev/null || true
done
wait 2>/dev/null || true
echo "Demo finished (client exit code: $CLIENT_EXIT)."
echo "Member logs: member-0.log ... member-3.log"
exit $CLIENT_EXIT
