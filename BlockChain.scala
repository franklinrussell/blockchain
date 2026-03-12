import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

// ── Crypto ────────────────────────────────────────────────────────────────────
object Crypto {
  def sha256(input: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(input.getBytes("UTF-8"))
      .map("%02x".format(_))
      .mkString
}

// ── UTXO primitives ───────────────────────────────────────────────────────────
case class TxOutput(id: String, owner: String, value: Double)
case class TxInput(outputId: String)

// ── Transaction ───────────────────────────────────────────────────────────────
case class Transaction(id: String, inputs: List[TxInput], outputs: List[TxOutput])

object Transaction {
  private def newId(): String = UUID.randomUUID().toString.take(8)

  /** Coinbase: creates coins from nothing (genesis / mining reward). */
  def coinbase(recipient: String, value: Double): Transaction = {
    val id  = newId()
    val out = TxOutput(s"$id-0", recipient, value)
    Transaction(id, List.empty, List(out))
  }

  /**
   * Spend transaction: gather sender UTXOs as inputs, pay recipient, return
   * change to sender.  Returns None if funds are insufficient.
   */
  def create(
    sender: String,
    recipient: String,
    amount: Double,
    senderUTXOs: List[TxOutput]
  ): Option[Transaction] = {
    val (collectedInputs, collectedTotal) = senderUTXOs
      .foldLeft((List.empty[TxInput], 0.0)) { case ((acc, sum), o) =>
        if (sum >= amount) (acc, sum)
        else (acc :+ TxInput(o.id), sum + o.value)
      }

    if (collectedTotal < amount) None
    else {
      val id           = newId()
      val recipientOut = TxOutput(s"$id-0", recipient, amount)
      val change       = collectedTotal - amount
      val outputs =
        if (change > 0) List(recipientOut, TxOutput(s"$id-1", sender, change))
        else            List(recipientOut)
      Some(Transaction(id, collectedInputs, outputs))
    }
  }
}

// ── Block ─────────────────────────────────────────────────────────────────────
case class Block(
  index: Int,
  timestamp: Long,
  transactions: List[Transaction],
  previousHash: String,
  nonce: Long,
  hash: String
)

object Block {
  def calculateHash(
    index: Int,
    timestamp: Long,
    transactions: List[Transaction],
    previousHash: String,
    nonce: Long
  ): String =
    Crypto.sha256(s"$index$timestamp$transactions$previousHash$nonce")
}

// ── Miner ─────────────────────────────────────────────────────────────────────
object Miner {
  val difficulty = 4
  val target     = "0" * difficulty

  def mineBlock(
    index: Int,
    transactions: List[Transaction],
    previousHash: String
  ): Block = {
    val timestamp = Instant.now.getEpochSecond
    var nonce     = 0L
    var hash      = ""

    println(s"Mining block $index...")
    val startTime = System.currentTimeMillis()

    while (!hash.startsWith(target)) {
      nonce += 1
      hash = Block.calculateHash(index, timestamp, transactions, previousHash, nonce)
    }

    val elapsed = System.currentTimeMillis() - startTime
    println(s"Block $index mined!  nonce=$nonce  time=${elapsed}ms")
    println(s"  Hash: $hash")
    println("-" * 60)

    Block(index, timestamp, transactions, previousHash, nonce, hash)
  }
}

// ── UTXOPool ──────────────────────────────────────────────────────────────────
class UTXOPool {
  private val pool = scala.collection.mutable.Map.empty[String, TxOutput]

  def add(o: TxOutput): Unit          = pool(o.id) = o
  def remove(id: String): Unit        = pool -= id
  def get(id: String): Option[TxOutput] = pool.get(id)
  def contains(id: String): Boolean   = pool.contains(id)
  def getByOwner(address: String): List[TxOutput] =
    pool.values.filter(_.owner == address).toList
  def snapshot: Map[String, TxOutput] = pool.toMap
}

// ── Wallet ────────────────────────────────────────────────────────────────────
class Wallet(val address: String) {
  def getBalance(pool: UTXOPool): Double =
    pool.getByOwner(address).map(_.value).sum

  def createTransaction(
    recipient: String,
    amount: Double,
    pool: UTXOPool
  ): Option[Transaction] =
    Transaction.create(address, recipient, amount, pool.getByOwner(address))
}

// ── Blockchain ────────────────────────────────────────────────────────────────
class Blockchain {
  val utxoPool: UTXOPool           = new UTXOPool()
  private var chain: List[Block]   = List(createGenesisBlock())

  private def createGenesisBlock(): Block = {
    val genesisTx = Transaction.coinbase("genesis", 100.0)
    genesisTx.outputs.foreach(utxoPool.add)
    val timestamp = Instant.now.getEpochSecond
    val hash      = Block.calculateHash(0, timestamp, List(genesisTx), "0" * 64, 0)
    Block(0, timestamp, List(genesisTx), "0" * 64, 0, hash)
  }

  def latestBlock: Block = chain.last

  /**
   * Validates a transaction against the current UTXO pool, then updates the
   * pool (remove spent inputs, add new outputs).
   */
  def processTransaction(tx: Transaction): Boolean = {
    val inputIds = tx.inputs.map(_.outputId)

    // Resolve inputs; fail if any are missing from the pool
    val resolvedOpt: Option[List[TxOutput]] =
      if (tx.inputs.isEmpty) Some(List.empty)
      else tx.inputs.foldLeft(Option(List.empty[TxOutput])) { (accOpt, inp) =>
        accOpt.flatMap(acc => utxoPool.get(inp.outputId).map(acc :+ _))
      }

    resolvedOpt match {
      case None => false
      case Some(inputOutputs) =>
        // No double-spend within this tx
        val noDup = inputIds.distinct.length == inputIds.length
        // Conservation check
        val conserved = tx.inputs.isEmpty || {
          inputOutputs.map(_.value).sum >= tx.outputs.map(_.value).sum
        }
        if (!noDup || !conserved) false
        else {
          inputIds.foreach(utxoPool.remove)
          tx.outputs.foreach(utxoPool.add)
          true
        }
    }
  }

  /** Process all transactions, then mine and append the block. */
  def addBlock(txs: List[Transaction]): Block = {
    txs.foreach { tx =>
      require(processTransaction(tx), s"Invalid transaction ${tx.id}")
    }
    val newBlock = Miner.mineBlock(chain.length, txs, latestBlock.hash)
    chain = chain :+ newBlock
    newBlock
  }

  def getBalance(address: String): Double =
    utxoPool.getByOwner(address).map(_.value).sum

  /** Validates hash integrity + a full UTXO replay from scratch. */
  def isValid: Boolean = {
    // 1. Hash / chain / difficulty checks
    val structurallyValid = chain.zipWithIndex.forall { case (block, index) =>
      if (index == 0) true
      else {
        val prev       = chain(index - 1)
        val validHash  = block.hash == Block.calculateHash(
          block.index, block.timestamp, block.transactions, block.previousHash, block.nonce)
        val validChain = block.previousHash == prev.hash
        val validDiff  = block.hash.startsWith("0" * Miner.difficulty)
        validHash && validChain && validDiff
      }
    }

    // 2. UTXO replay
    def replayTx(tx: Transaction, pool: UTXOPool): Boolean = {
      val resolvedOpt: Option[List[TxOutput]] =
        if (tx.inputs.isEmpty) Some(List.empty)
        else tx.inputs.foldLeft(Option(List.empty[TxOutput])) { (accOpt, inp) =>
          accOpt.flatMap(acc => pool.get(inp.outputId).map(acc :+ _))
        }
      resolvedOpt match {
        case None => false
        case Some(inputOutputs) =>
          val conserved = tx.inputs.isEmpty ||
            inputOutputs.map(_.value).sum >= tx.outputs.map(_.value).sum
          if (!conserved) false
          else {
            tx.inputs.foreach(inp => pool.remove(inp.outputId))
            tx.outputs.foreach(pool.add)
            true
          }
      }
    }

    val replayPool = new UTXOPool()
    structurallyValid && chain.forall(_.transactions.forall(replayTx(_, replayPool)))
  }

  def printChain(): Unit =
    chain.foreach { block =>
      println(s"Block #${block.index}")
      println(s"  Timestamp:     ${block.timestamp}")
      println(s"  Transactions:")
      block.transactions.foreach { tx =>
        println(s"    tx ${tx.id}")
        println(s"      inputs:  ${tx.inputs.map(_.outputId).mkString(", ").take(60)}")
        tx.outputs.foreach { o =>
          println(f"      output:  ${o.owner}%-10s  ${o.value}%.2f  (id=${o.id})")
        }
      }
      println(s"  Previous Hash: ${block.previousHash}")
      println(s"  Nonce:         ${block.nonce}")
      println(s"  Hash:          ${block.hash}")
      println()
    }
}

// ── Main ──────────────────────────────────────────────────────────────────────
@main def run(): Unit = {
  println("=" * 60)
  println("FranklinCoin — UTXO Blockchain")
  println("=" * 60)
  println()

  val bc      = new Blockchain()
  val genesis = new Wallet("genesis")
  val alice   = new Wallet("alice")
  val bob     = new Wallet("bob")
  val charlie = new Wallet("charlie")

  println(s"Genesis block created.  genesis balance = ${genesis.getBalance(bc.utxoPool)}")
  println()

  // Block 1: genesis → alice 60 (genesis gets 40 change)
  val tx1 = genesis.createTransaction("alice", 60.0, bc.utxoPool).get
  bc.addBlock(List(tx1))
  println(s"After block 1:  genesis=${genesis.getBalance(bc.utxoPool)}  alice=${alice.getBalance(bc.utxoPool)}")
  println()

  // Block 2: alice → bob 25 (alice gets 35 change)
  //           genesis → charlie 15 (genesis gets 25 change)
  val tx2a = alice.createTransaction("bob",     25.0, bc.utxoPool).get
  val tx2b = genesis.createTransaction("charlie", 15.0, bc.utxoPool).get
  bc.addBlock(List(tx2a, tx2b))
  println(s"After block 2:  genesis=${genesis.getBalance(bc.utxoPool)}  alice=${alice.getBalance(bc.utxoPool)}  bob=${bob.getBalance(bc.utxoPool)}  charlie=${charlie.getBalance(bc.utxoPool)}")
  println()

  // Block 3: bob → charlie 10 (bob gets 15 change)
  val tx3 = bob.createTransaction("charlie", 10.0, bc.utxoPool).get
  bc.addBlock(List(tx3))
  println()

  // Final balances
  println("=" * 60)
  println("Final balances:")
  println(f"  genesis : ${genesis.getBalance(bc.utxoPool)}%.2f")
  println(f"  alice   : ${alice.getBalance(bc.utxoPool)}%.2f")
  println(f"  bob     : ${bob.getBalance(bc.utxoPool)}%.2f")
  println(f"  charlie : ${charlie.getBalance(bc.utxoPool)}%.2f")
  val total = List(genesis, alice, bob, charlie).map(_.getBalance(bc.utxoPool)).sum
  println(f"  total   : $total%.2f  (should be 100.00)")
  println()

  // Full chain
  println("=" * 60)
  println("Full chain:")
  println("=" * 60)
  println()
  bc.printChain()

  println("=" * 60)
  println(s"Chain valid? ${bc.isValid}")
  println("=" * 60)
}
