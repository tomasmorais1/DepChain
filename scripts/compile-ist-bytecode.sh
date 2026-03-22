#!/usr/bin/env bash
# Recompile ISTCoin.sol and refresh ISTCoin.creation.hex + ISTCoin.runtime.hex (Solidity 0.8.x).
# Requires: Node.js (npx) and Python 3.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 << 'PY'
import json, subprocess, sys
sol = "src/main/resources/contracts/ISTCoin.sol"
with open(sol) as f:
    content = f.read()
inp = {
    "language": "Solidity",
    "sources": {"ISTCoin.sol": {"content": content}},
    "settings": {
        "optimizer": {"enabled": True, "runs": 200},
        "outputSelection": {"*": {"*": ["evm.bytecode.object", "evm.deployedBytecode.object"]}},
    },
}
raw = subprocess.check_output(
    ["npx", "--yes", "solc@0.8.31", "--standard-json"],
    input=json.dumps(inp).encode(),
    stderr=subprocess.DEVNULL,
)
text = raw.decode()
i = text.find("{")
if i < 0:
    sys.exit("solc produced no JSON")
out = json.loads(text[i:])
errs = [e for e in out.get("errors", []) if e.get("severity") == "error"]
if errs:
    for e in errs:
        print(e.get("formattedMessage", e), file=sys.stderr)
    sys.exit(1)
c = out["contracts"]["ISTCoin.sol"]["ISTCoin"]["evm"]
open("src/main/resources/contracts/ISTCoin.creation.hex", "w").write(c["bytecode"]["object"])
open("src/main/resources/contracts/ISTCoin.runtime.hex", "w").write(c["deployedBytecode"]["object"])
print("Wrote ISTCoin.creation.hex and ISTCoin.runtime.hex")
PY
