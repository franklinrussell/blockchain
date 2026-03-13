# SummitCoin (SMT)

A Bitcoin-style blockchain built from scratch in **Scala 3**, with a REST API node and a
ski-themed e-commerce storefront that accepts SMT as payment.

```
┌─────────────────────────────────────────────────────────────┐
│                   SummitCoin Architecture                   │
│                                                             │
│   ┌──────────────────────┐     ┌────────────────────────┐  │
│   │  SummitCoin Node     │     │  Bigger Beach Towels   │  │
│   │  (Scala 3 / http4s)  │◄────│  (Next.js storefront)  │  │
│   │  localhost:8080      │     │  localhost:3000        │  │
│   └──────────────────────┘     └────────────────────────┘  │
│         │                                                   │
│   ┌─────▼────────────────────────────────────────────────┐  │
│   │  Blockchain                                          │  │
│   │  Block 0 → Block 1 → Block 2 → Block N              │  │
│   │  [coinbase] [tx, tx] [tx, tx] [tx, ...]             │  │
│   └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## What is SummitCoin?

SummitCoin is a portfolio project demonstrating how a blockchain works at the implementation
level — not as an abstraction or a tutorial exercise, but as working code:

- **UTXO model** — coins are discrete outputs, not account balances (same as Bitcoin)
- **SHA-256 proof of work** — miners find a nonce such that `hash(block) < difficulty target`
- **Full chain validation** — every run replays all transactions from genesis to verify integrity
- **REST API node** — a real HTTP server you can call with `curl` or any HTTP client
- **E-commerce integration** — a storefront that uses the chain as its payment layer

The SMT tokens have no monetary value. The faucet gives them away free.
This is about understanding how blockchains work, not about money.

---

## Repository Structure

```
blockchain/                   ← You are here (Scala node)
  SummitCoin.scala            — Core blockchain: UTXO, PoW, Mempool, Wallet
  Server.scala                — http4s REST API
  build.sbt                   — SBT build with http4s, circe, cats-effect

bigger-beach-towels/          ← Separate repo (Next.js storefront)
  app/page.tsx                — Product listing (6 ski/outdoors items)
  app/checkout/page.tsx       — SMT checkout flow
  app/faucet/page.tsx         — First Tracks Faucet (25 free SMT)
  app/explorer/page.tsx       — Block explorer
  app/about/page.tsx          — Project explainer
  lib/api.ts                  — Typed fetch client for the node
  lib/products.ts             — Product catalog
```

---

## Running the Blockchain Node

**Prerequisites:** Java 17+, SBT 1.x

```bash
cd blockchain
sbt run
```

Output:
```
============================================================
  SummitCoin Node  |  SMT Blockchain
============================================================
  Listening on   http://0.0.0.0:8080
  Mining reward  50.0 SMT per block
  PoW difficulty 4 leading zeros
============================================================
```

The genesis block mines automatically on startup, awarding **50 SMT** to `summit-node`.

---

## REST API

| Method | Endpoint                  | Description                                   |
|--------|---------------------------|-----------------------------------------------|
| POST   | `/wallet/new`             | Generate a new SMT wallet address             |
| GET    | `/balance/:address`       | Get SMT balance for any address               |
| GET    | `/chain`                  | Full blockchain as JSON                       |
| GET    | `/transactions/pending`   | View the mempool                              |
| POST   | `/transactions`           | Submit a spend transaction to the mempool     |
| GET    | `/mine`                   | Mine pending transactions into a new block    |
| POST   | `/faucet`                 | Drip 25 SMT to an address and auto-mine       |

### Example: full flow via curl

```bash
# 1. Create a wallet
curl -s -X POST http://localhost:8080/wallet/new | jq .
# { "address": "SMT4A9F...", "balance": 0.0, "unit": "SMT" }

# 2. Request SMT from the faucet (auto-mines)
curl -s -X POST http://localhost:8080/faucet \
  -H "Content-Type: application/json" \
  -d '{"address": "SMT4A9F..."}' | jq .

# 3. Check balance
curl -s http://localhost:8080/balance/SMT4A9F... | jq .
# { "address": "SMT4A9F...", "balance": 25.0, "unit": "SMT" }

# 4. Send a transaction
curl -s -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -d '{"sender": "SMT4A9F...", "recipient": "summit-store", "amount": 15.0}' | jq .

# 5. Mine it
curl -s http://localhost:8080/mine | jq .

# 6. View the chain
curl -s http://localhost:8080/chain | jq '.blocks[] | {index, txCount: (.transactions | length)}'
```

---

## Running the Storefront

**Prerequisites:** Node.js 18+

```bash
cd bigger-beach-towels
npm install
npm run dev
# → http://localhost:3000
```

The storefront expects the SummitCoin node at `http://localhost:8080`.
To override: `NEXT_PUBLIC_NODE_URL=http://your-node:8080 npm run dev`

**Pages:**
- `/` — Shop: 6 ski/après-ski products, all priced in SMT
- `/faucet` — First Tracks Faucet: claim 25 free SMT
- `/explorer` — Block explorer: chain stats, address lookup, full block/tx viewer
- `/about` — How SummitCoin works

---

## How the Blockchain Works

### UTXO Model

Every coin is a `TxOutput` — an object with an owner and a value.
To send coins, you reference existing UTXOs as inputs and create new ones as outputs.
Any remainder comes back to you as a change output.

```
Before:  alice [60 SMT output: abc-0]

Transaction:
  Input:    abc-0  (consume alice's 60 SMT)
  Output 0: bob        gets 45 SMT  (xyz-0)
  Output 1: alice gets 15 SMT change (xyz-1)

After:  bob [45 SMT: xyz-0]  alice [15 SMT: xyz-1]
```

### Proof of Work

Mining = finding an integer `nonce` such that:

```
SHA-256( index + timestamp + transactions + previousHash + nonce )
  starts with "0000"   ← difficulty 4
```

This requires ~65,000 hashes on average. Changing any historical block breaks its hash,
which breaks the chain linkage to every subsequent block.

### Chain Validation (`isValid`)

1. For each block: verify hash integrity, chain linkage, and difficulty target
2. Replay all transactions from genesis into a fresh UTXO pool, verifying conservation at each step

---

## Tech Stack

| Component        | Technology                          |
|------------------|-------------------------------------|
| Blockchain core  | Scala 3, pure stdlib                |
| REST API         | http4s 0.23 + Ember server          |
| Async runtime    | cats-effect 3                       |
| JSON             | circe (auto-derived codecs)         |
| Logging          | log4cats + logback                  |
| Storefront       | Next.js 16, React 19                |
| Styling          | Tailwind CSS v4                     |
| Type safety      | TypeScript 5                        |

---

## Design Notes

**Why no cryptographic signatures?**
Real Bitcoin transactions require the sender to sign with their private key.
This demo omits signatures to keep the focus on the chain mechanics (UTXO, PoW, validation).
Adding signatures would require a keypair per wallet and signature verification in `processTransaction`.

**Thread safety**
The `Blockchain` class uses mutable state. A production node would wrap it in
`cats.effect.Ref[IO, Blockchain]` to serialise access. For this demo, the REST endpoints
run sequentially against shared state.

**Persistence**
Everything is in-memory. Restarting the node resets the chain to genesis.
