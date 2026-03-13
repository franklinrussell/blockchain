import cats.effect.*
import com.comcast.ip4s.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.time.Instant

// ── Request / Response shapes ─────────────────────────────────────────────────

/** Body for POST /transactions */
case class TxRequest(sender: String, recipient: String, amount: Double)

/** Body for POST /faucet */
case class FaucetRequest(address: String)

// ── Persistence ───────────────────────────────────────────────────────────────

/**
 * Saves and loads the blockchain as a pretty-printed JSON array of blocks.
 *
 * Writes are atomic: the chain is serialised to a .tmp file first, then
 * renamed to the final path.  On most Unix systems rename(2) is atomic
 * within the same filesystem, preventing corruption from mid-write crashes.
 */
object Persistence {
  val filePath = "./data/blockchain.json"
  private val tmpPath  = filePath + ".tmp"

  /** Persist the current chain to disk.  Creates ./data/ if needed. */
  def save(chain: List[Block]): IO[Unit] = IO.blocking {
    val dir = Paths.get("./data")
    if (!Files.exists(dir)) Files.createDirectories(dir)

    Files.writeString(Paths.get(tmpPath), chain.asJson.spaces2)

    // Prefer ATOMIC_MOVE; fall back to plain replace if the filesystem doesn't support it
    try
      Files.move(Paths.get(tmpPath), Paths.get(filePath),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
      Files.move(Paths.get(tmpPath), Paths.get(filePath),
        StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /**
   * Load the chain from disk.
   * Returns None if the file doesn't exist; logs a warning and returns None
   * if the file is present but fails to parse (so the node starts fresh
   * rather than crashing).
   */
  def load(): IO[Option[List[Block]]] = IO.blocking {
    val p = Paths.get(filePath)
    if (!Files.exists(p)) None
    else parser.decode[List[Block]](Files.readString(p)).toOption
  }
}

// ── SummitCoin REST Node ──────────────────────────────────────────────────────

object SummitCoinNode extends IOApp.Simple {

  // Single shared blockchain instance.
  // Note: mutable state is not synchronised beyond IO.blocking serialisation
  // of mining.  A production node would use cats.effect.std.Mutex or Ref.
  private val bc = new Blockchain()

  // Track when we last saved to disk (reported by GET /status)
  @volatile private var lastSaved: Option[Instant] = None

  // circe decoders for incoming JSON request bodies
  given EntityDecoder[IO, TxRequest]    = jsonOf[IO, TxRequest]
  given EntityDecoder[IO, FaucetRequest] = jsonOf[IO, FaucetRequest]

  // Save the chain and record the timestamp
  private def saveChain: IO[Unit] =
    Persistence.save(bc.getChain) *> IO { lastSaved = Some(Instant.now()) }

  // ── Routes ──────────────────────────────────────────────────────────────────

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ── GET /health ───────────────────────────────────────────────────────────
    // Lightweight liveness probe for cloud health checks.
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson))

    // ── GET /status ───────────────────────────────────────────────────────────
    // Node and persistence metadata.
    case GET -> Root / "status" =>
      Ok(Json.obj(
        "blockHeight"     -> bc.chainHeight.asJson,
        "totalBlocks"     -> bc.getChain.length.asJson,
        "smtInSupply"     -> bc.utxoPool.totalSupply.asJson,
        "chainValid"      -> bc.isValid.asJson,
        "persistenceFile" -> Persistence.filePath.asJson,
        "lastSaved"       -> lastSaved.map(_.toString).getOrElse("never").asJson
      ))

    // ── POST /wallet/new ──────────────────────────────────────────────────────
    // Generate a fresh wallet address.
    case POST -> Root / "wallet" / "new" =>
      val wallet = Wallet.generate()
      Ok(Json.obj(
        "address" -> wallet.address.asJson,
        "balance" -> 0.0.asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    // ── GET /balance/:address ─────────────────────────────────────────────────
    case GET -> Root / "balance" / address =>
      Ok(Json.obj(
        "address" -> address.asJson,
        "balance" -> bc.getBalance(address).asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    // ── GET /chain ────────────────────────────────────────────────────────────
    case GET -> Root / "chain" =>
      Ok(Json.obj(
        "height"  -> bc.chainHeight.asJson,
        "length"  -> bc.getChain.length.asJson,
        "supply"  -> bc.utxoPool.totalSupply.asJson,
        "isValid" -> bc.isValid.asJson,
        "blocks"  -> bc.getChain.asJson
      ))

    // ── GET /transactions/pending ─────────────────────────────────────────────
    case GET -> Root / "transactions" / "pending" =>
      val pending = bc.mempool.peek
      Ok(Json.obj(
        "count"        -> pending.length.asJson,
        "transactions" -> pending.asJson
      ))

    // ── POST /transactions ────────────────────────────────────────────────────
    // Submit a spend transaction to the mempool.
    case req @ POST -> Root / "transactions" =>
      req.as[TxRequest].flatMap { body =>
        val senderUTXOs = bc.utxoPool.getByOwner(body.sender)
        Transaction.create(body.sender, body.recipient, body.amount, senderUTXOs) match {
          case None =>
            BadRequest(Json.obj("error" -> "Insufficient funds or no UTXOs found".asJson))
          case Some(tx) =>
            bc.submitTransaction(tx) match {
              case Right(submitted) =>
                Ok(Json.obj(
                  "success"     -> true.asJson,
                  "transaction" -> submitted.asJson,
                  "message"     -> "Transaction added to mempool — heading down the mountain!".asJson
                ))
              case Left(err) =>
                BadRequest(Json.obj("error" -> err.asJson))
            }
        }
      }

    // ── GET /mine ─────────────────────────────────────────────────────────────
    // Mine pending transactions into a new block, then persist.
    case GET -> Root / "mine" =>
      IO.blocking(bc.mineBlock()).flatMap { block =>
        saveChain *>
        Ok(Json.obj(
          "success" -> true.asJson,
          "message" -> s"Block #${block.index} mined — fresh tracks on the chain!".asJson,
          "block"   -> block.asJson
        ))
      }

    // ── POST /faucet ──────────────────────────────────────────────────────────
    // Drip 25 SMT from the node wallet, auto-mine, then persist.
    case req @ POST -> Root / "faucet" =>
      req.as[FaucetRequest].flatMap { body =>
        val nodeUTXOs = bc.utxoPool.getByOwner(SummitCoin.NODE_ADDRESS)
        Transaction.create(SummitCoin.NODE_ADDRESS, body.address, 25.0, nodeUTXOs) match {
          case None =>
            BadRequest(Json.obj("error" -> "Faucet is empty — node has no SMT to send".asJson))
          case Some(tx) =>
            bc.submitTransaction(tx) match {
              case Left(err) => BadRequest(Json.obj("error" -> err.asJson))
              case Right(_)  =>
                IO.blocking(bc.mineBlock()).flatMap { block =>
                  saveChain *>
                  Ok(Json.obj(
                    "success" -> true.asJson,
                    "message" -> s"25 SMT dropped to ${body.address} — first tracks are yours!".asJson,
                    "txId"    -> tx.id.asJson,
                    "block"   -> block.index.asJson
                  ))
                }
            }
        }
      }
  }

  // ── Server entry point ───────────────────────────────────────────────────────

  def run: IO[Unit] = {
    given LoggerFactory[IO] = Slf4jFactory.create[IO]

    // On startup: load persisted chain if available, otherwise start fresh.
    val startup: IO[Unit] = Persistence.load().flatMap {
      case None =>
        IO.println("  No existing blockchain found, starting fresh")
      case Some(blocks) =>
        IO.println(s"  Loading blockchain from ${Persistence.filePath}...") *>
        IO.blocking(bc.restoreFromBlocks(blocks))                          *>
        IO.println(s"  Restored ${blocks.length} blocks from disk")
    }.handleErrorWith { e =>
      IO.println(s"  Warning: could not load blockchain (${e.getMessage}), starting fresh")
    }

    // On shutdown: save the chain before the process exits.
    val shutdown: IO[Unit] =
      IO.println("\n  Shutting down — saving blockchain to disk...") *>
      Persistence.save(bc.getChain)
        .handleErrorWith(e => IO.println(s"  Warning: save failed: ${e.getMessage}")) *>
      IO.println("  Blockchain saved. Goodbye!")

    val corsApp = CORS.policy
      .withAllowOriginAll
      .withAllowMethodsAll
      .withAllowHeadersAll
      .apply(routes.orNotFound)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(corsApp)
      .build
      .use { _ =>
        IO.println("=" * 60)                                             *>
        IO.println("  SummitCoin Node  |  SMT Blockchain")               *>
        IO.println("=" * 60)                                             *>
        startup                                                          *>
        IO.println(s"  Listening on   http://0.0.0.0:8080")             *>
        IO.println(s"  Mining reward  ${SummitCoin.MINING_REWARD} SMT per block") *>
        IO.println(s"  PoW difficulty ${Miner.difficulty} leading zeros") *>
        IO.println("=" * 60)                                             *>
        IO.never.onCancel(shutdown)
      }
  }
}
