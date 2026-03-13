import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

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

/** A discrete coin output sitting in the UTXO set. */
case class TxOutput(id: String, owner: String, value: Double)

/** A pointer to an existing TxOutput being spent. */
case class TxInput(outputId: String)

// ── Transaction ───────────────────────────────────────────────────────────────

case class Transaction(id: String, inputs: List[TxInput], outputs: List[TxOutput])

object Transaction {
  private def newId(): String = UUID.randomUUID().toString.take(8)

  /** Coinbase: mints new coins with no inputs (used for block rewards). */
  def coinbase(recipient: String, value: Double): Transaction = {
    val id  = newId()
    val out = TxOutput(s"$id-0", recipient, value)
    Transaction(id, List.empty, List(out))
  }

  /**
   * Spend transaction: grabs enough of the sender's UTXOs to cover `amount`,
   * pays the recipient, and returns change to the sender.
   * Returns None if the sender has insufficient funds.
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

/** Proof-of-work miner: increments nonce until the hash has the required leading zeros. */
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

    while (!hash.startsWith(target)) {
      nonce += 1
      hash = Block.calculateHash(index, timestamp, transactions, previousHash, nonce)
    }

    Block(index, timestamp, transactions, previousHash, nonce, hash)
  }
}

// ── UTXOPool ──────────────────────────────────────────────────────────────────

/** In-memory set of all unspent transaction outputs. */
class UTXOPool {
  private val pool = mutable.Map.empty[String, TxOutput]

  def add(o: TxOutput): Unit              = pool(o.id) = o
  def remove(id: String): Unit            = pool -= id
  def get(id: String): Option[TxOutput]  = pool.get(id)
  def contains(id: String): Boolean      = pool.contains(id)
  def getByOwner(address: String): List[TxOutput] =
    pool.values.filter(_.owner == address).toList
  def totalSupply: Double                 = pool.values.map(_.value).sum
  def snapshot: Map[String, TxOutput]    = pool.toMap
  def clear(): Unit                      = pool.clear()
}

// ── Mempool ───────────────────────────────────────────────────────────────────

/** Pending transactions waiting to be included in the next block. */
class Mempool {
  private val pending = mutable.ListBuffer.empty[Transaction]

  def submit(tx: Transaction): Unit  = pending += tx
  def drain(): List[Transaction]     = { val txs = pending.toList; pending.clear(); txs }
  def peek: List[Transaction]        = pending.toList
  def size: Int                      = pending.size
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

object Wallet {
  /** Generate a fresh wallet with a random SMT address. */
  def generate(): Wallet =
    new Wallet("SMT" + UUID.randomUUID().toString.replace("-", "").take(32).toUpperCase)
}

// ── SummitCoin constants ──────────────────────────────────────────────────────

object SummitCoin {
  val TICKER         = "SMT"
  val MINING_REWARD  = 50.0   // SMT awarded to miner per block
  val NODE_ADDRESS   = "summit-node"
}

// ── Blockchain ────────────────────────────────────────────────────────────────

/**
 * The SummitCoin blockchain.  Holds the canonical chain, UTXO pool, and mempool.
 *
 * Thread safety: all public methods mutate shared state.  For a production node
 * you would wrap calls in a Ref[IO, Blockchain] or similar.  For this portfolio
 * demo, single-threaded access via the REST API is assumed.
 */
class Blockchain {
  val utxoPool: UTXOPool         = new UTXOPool()
  val mempool:  Mempool          = new Mempool()
  private var chain: List[Block] = List(createGenesisBlock())

  // ── Genesis ─────────────────────────────────────────────────────────────────

  private def createGenesisBlock(): Block = {
    val genesisTx = Transaction.coinbase(SummitCoin.NODE_ADDRESS, SummitCoin.MINING_REWARD)
    genesisTx.outputs.foreach(utxoPool.add)
    val timestamp = Instant.now.getEpochSecond
    val hash      = Block.calculateHash(0, timestamp, List(genesisTx), "0" * 64, 0)
    Block(0, timestamp, List(genesisTx), "0" * 64, 0, hash)
  }

  // ── Accessors ────────────────────────────────────────────────────────────────

  def latestBlock: Block       = chain.last
  def chainHeight: Int         = chain.length - 1
  def getChain: List[Block]    = chain
  def getBalance(addr: String) = utxoPool.getByOwner(addr).map(_.value).sum

  /**
   * Restore state from a persisted chain (loaded from disk).
   * Clears the current UTXO pool and replays every transaction
   * from the loaded blocks to reconstruct the final UTXO set.
   * The mempool is not restored — pending transactions don't survive restarts.
   */
  def restoreFromBlocks(blocks: List[Block]): Unit = {
    utxoPool.clear()
    chain = blocks
    for {
      block <- blocks
      tx    <- block.transactions
    } {
      tx.inputs.foreach(inp => utxoPool.remove(inp.outputId))
      tx.outputs.foreach(utxoPool.add)
    }
  }

  // ── Transaction processing ───────────────────────────────────────────────────

  /**
   * Validates a transaction against the current UTXO pool and, if valid,
   * removes spent inputs and adds new outputs.
   * Returns true on success, false if invalid.
   */
  def processTransaction(tx: Transaction): Boolean = {
    val inputIds = tx.inputs.map(_.outputId)

    // Resolve all inputs from the pool
    val resolvedOpt: Option[List[TxOutput]] =
      if (tx.inputs.isEmpty) Some(List.empty)
      else tx.inputs.foldLeft(Option(List.empty[TxOutput])) { (accOpt, inp) =>
        accOpt.flatMap(acc => utxoPool.get(inp.outputId).map(acc :+ _))
      }

    resolvedOpt match {
      case None => false  // at least one input not in pool
      case Some(inputOutputs) =>
        val noDuplicate = inputIds.distinct.length == inputIds.length
        val conserved   = tx.inputs.isEmpty ||
          inputOutputs.map(_.value).sum >= tx.outputs.map(_.value).sum
        if (!noDuplicate || !conserved) false
        else {
          inputIds.foreach(utxoPool.remove)
          tx.outputs.foreach(utxoPool.add)
          true
        }
    }
  }

  /**
   * Validates a transaction for mempool admission (checks UTXOs exist and
   * funds are sufficient) without yet spending the UTXOs.
   * Actual UTXO updates happen at mining time.
   */
  def submitTransaction(tx: Transaction): Either[String, Transaction] = {
    val allInputsPresent = tx.inputs.forall(inp => utxoPool.contains(inp.outputId))
    if (!allInputsPresent)
      return Left("One or more inputs not found in UTXO pool")

    val inputSum  = tx.inputs.flatMap(inp => utxoPool.get(inp.outputId)).map(_.value).sum
    val outputSum = tx.outputs.map(_.value).sum
    if (inputSum < outputSum)
      return Left(s"Insufficient funds: have $inputSum SMT, need $outputSum SMT")

    mempool.submit(tx)
    Right(tx)
  }

  // ── Mining ───────────────────────────────────────────────────────────────────

  /**
   * Drains the mempool, prepends a coinbase reward transaction, runs PoW,
   * and appends the new block to the chain.
   *
   * Invalid pending transactions are silently dropped (their UTXOs may have
   * been spent by an earlier transaction in the same block).
   *
   * This is CPU-intensive — callers should run it on a blocking thread pool.
   */
  def mineBlock(minerAddress: String = SummitCoin.NODE_ADDRESS): Block = {
    val pendingTxs = mempool.drain()

    // Coinbase reward — always valid, no inputs
    val reward = Transaction.coinbase(minerAddress, SummitCoin.MINING_REWARD)
    processTransaction(reward)

    // Process pending transactions; keep only the valid ones
    val validUserTxs = pendingTxs.filter(processTransaction)

    val blockTxs = reward :: validUserTxs
    val newBlock  = Miner.mineBlock(chain.length, blockTxs, latestBlock.hash)
    chain = chain :+ newBlock
    newBlock
  }

  // ── Validation ───────────────────────────────────────────────────────────────

  /**
   * Full chain validation:
   *   1. Hash integrity, chain linkage, and difficulty for every block
   *   2. Complete UTXO replay from scratch to verify coin conservation
   */
  def isValid: Boolean = {
    val structurallyValid = chain.zipWithIndex.forall { case (block, idx) =>
      if (idx == 0) true
      else {
        val prev       = chain(idx - 1)
        val validHash  = block.hash == Block.calculateHash(
          block.index, block.timestamp, block.transactions, block.previousHash, block.nonce)
        val validLink  = block.previousHash == prev.hash
        val validDiff  = block.hash.startsWith("0" * Miner.difficulty)
        validHash && validLink && validDiff
      }
    }

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
          else { tx.inputs.foreach(inp => pool.remove(inp.outputId)); tx.outputs.foreach(pool.add); true }
      }
    }

    val replayPool = new UTXOPool()
    structurallyValid && chain.forall(_.transactions.forall(replayTx(_, replayPool)))
  }
}
