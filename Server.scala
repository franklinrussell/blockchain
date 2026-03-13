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
 *
 * If the configured DATA_DIR is not writable, falls back to ./data so the
 * node can still persist in ephemeral container storage.
 */
object Persistence {
  // Requested directory from env (e.g. /data on Render)
  private val requestedDir = sys.env.getOrElse("DATA_DIR", "./data")

  // Resolved at runtime by ensureDataDir() — may fall back to ./data
  @volatile private var _activeDir: String = requestedDir

  def activeFilePath: String  = s"${_activeDir}/blockchain.json"
  def filePath:       String  = activeFilePath   // kept for compat
  private def tmpPath: String = activeFilePath + ".tmp"

  private def isWritable(dir: java.nio.file.Path): Boolean =
    try {
      val probe = dir.resolve(".write-probe")
      Files.writeString(probe, "ok")
      Files.deleteIfExists(probe)
      true
    } catch { case _: Throwable => false }

  /**
   * Called once at startup.  Tries to create and verify the primary data
   * directory; falls back to ./data if the primary is not writable.
   * All outcomes are printed to stdout (visible in Render logs).
   */
  def ensureDataDir(): IO[Unit] = IO.blocking {
    val primary = Paths.get(requestedDir)

    def trySetup(dir: java.nio.file.Path): Boolean =
      try {
        Files.createDirectories(dir)
        isWritable(dir)
      } catch { case _: Throwable => false }

    if (trySetup(primary)) {
      println(s"  [Persistence] Data directory ready: $requestedDir")
      println(s"  [Persistence] Active file: ${activeFilePath}")
    } else {
      println(s"  [Persistence] WARNING: $requestedDir is not writable (permission denied or mount failure)")
      val fallback = Paths.get("./data")
      if (trySetup(fallback)) {
        _activeDir = "./data"
        println(s"  [Persistence] Falling back to: ./data  (ephemeral — data lost on restart)")
        println(s"  [Persistence] Active file: ${activeFilePath}")
      } else {
        println(s"  [Persistence] FATAL: ./data fallback also failed — persistence is DISABLED")
      }
    }
  }

  /** Persist the current chain to disk, returns the path written on success. */
  def save(chain: List[Block]): IO[String] = IO.blocking {
    val dir = Paths.get(_activeDir)
    if (!Files.exists(dir)) Files.createDirectories(dir)

    Files.writeString(Paths.get(tmpPath), chain.asJson.spaces2)

    try
      Files.move(Paths.get(tmpPath), Paths.get(activeFilePath),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    catch { case _: java.nio.file.AtomicMoveNotSupportedException =>
      Files.move(Paths.get(tmpPath), Paths.get(activeFilePath),
        StandardCopyOption.REPLACE_EXISTING)
    }

    activeFilePath
  }

  /**
   * Load the chain from disk.
   * Returns None if the file doesn't exist; returns None (and logs a warning)
   * if present but unparseable — the node starts fresh rather than crashing.
   */
  def load(): IO[Option[List[Block]]] = IO.blocking {
    val p = Paths.get(activeFilePath)
    if (!Files.exists(p)) None
    else parser.decode[List[Block]](Files.readString(p)).toOption
  }
}

// ── SummitCoin REST Node ──────────────────────────────────────────────────────

object SummitCoinNode extends IOApp.Simple {

  private val bc = new Blockchain()

  // Either[errorMessage, lastSuccessTimestamp]
  @volatile private var lastSaveStatus: Either[String, Instant] = Left("never")

  given EntityDecoder[IO, TxRequest]     = jsonOf[IO, TxRequest]
  given EntityDecoder[IO, FaucetRequest] = jsonOf[IO, FaucetRequest]

  /**
   * Save the chain, update lastSaveStatus, and log the result.
   * Errors are logged and recorded but NOT re-raised, so mining/faucet
   * routes still return success even when persistence fails.
   */
  private def saveChain: IO[Unit] =
    Persistence.save(bc.getChain)
      .flatMap { path =>
        val n = bc.getChain.length
        IO { lastSaveStatus = Right(Instant.now()) } *>
        IO.println(s"  Blockchain saved to $path ($n blocks)")
      }
      .handleErrorWith { e =>
        val msg = s"${e.getClass.getName}: ${e.getMessage}"
        IO { lastSaveStatus = Left(s"Save failed: $msg") } *>
        IO.println(s"  ERROR: Blockchain save failed!") *>
        IO.println(s"  ${msg}") *>
        IO.println(s"  Active path: ${Persistence.activeFilePath}")
      }

  // ── Routes ──────────────────────────────────────────────────────────────────

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ── GET /health ───────────────────────────────────────────────────────────
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson))

    // ── GET /status ───────────────────────────────────────────────────────────
    case GET -> Root / "status" =>
      val saveInfo = lastSaveStatus match {
        case Right(ts)  => ts.toString
        case Left(msg)  => msg
      }
      Ok(Json.obj(
        "blockHeight"     -> bc.chainHeight.asJson,
        "totalBlocks"     -> bc.getChain.length.asJson,
        "smtInSupply"     -> bc.utxoPool.totalSupply.asJson,
        "chainValid"      -> bc.isValid.asJson,
        "persistenceFile" -> Persistence.activeFilePath.asJson,
        "lastSaved"       -> saveInfo.asJson
      ))

    // ── GET /persist ──────────────────────────────────────────────────────────
    // Manually trigger a save and report the outcome — useful for diagnosis.
    case GET -> Root / "persist" =>
      Persistence.save(bc.getChain)
        .flatMap { path =>
          val n = bc.getChain.length
          IO { lastSaveStatus = Right(Instant.now()) } *>
          IO.println(s"  Manual /persist: saved $n blocks to $path") *>
          Ok(Json.obj(
            "success" -> true.asJson,
            "message" -> s"Saved $n blocks to $path".asJson
          ))
        }
        .handleErrorWith { e =>
          val msg = s"${e.getClass.getName}: ${e.getMessage}"
          IO { lastSaveStatus = Left(s"Save failed: $msg") } *>
          IO.println(s"  Manual /persist FAILED: $msg") *>
          Ok(Json.obj(
            "success" -> false.asJson,
            "error"   -> msg.asJson
          ))
        }

    // ── POST /wallet/new ──────────────────────────────────────────────────────
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

    val startup: IO[Unit] =
      Persistence.ensureDataDir() *>
      Persistence.load().flatMap {
        case None =>
          IO.println("  No existing blockchain found, starting fresh")
        case Some(blocks) =>
          IO.println(s"  Loading blockchain from ${Persistence.activeFilePath}...") *>
          IO.blocking(bc.restoreFromBlocks(blocks))                                 *>
          IO.println(s"  Restored ${blocks.length} blocks from disk")
      }.handleErrorWith { e =>
        IO.println(s"  Warning: could not load blockchain (${e.getMessage}), starting fresh")
      }

    val shutdown: IO[Unit] =
      IO.println("\n  Shutting down — saving blockchain to disk...") *>
      Persistence.save(bc.getChain)
        .flatMap(path => IO.println(s"  Blockchain saved to $path. Goodbye!"))
        .handleErrorWith(e => IO.println(s"  WARNING: shutdown save failed: ${e.getClass.getName}: ${e.getMessage}"))

    val serverPort = sys.env.get("PORT")
      .flatMap(_.toIntOption)
      .flatMap(Port.fromInt)
      .getOrElse(port"8080")

    val corsApp = CORS.policy
      .withAllowOriginAll
      .withAllowMethodsAll
      .withAllowHeadersAll
      .apply(routes.orNotFound)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(serverPort)
      .withHttpApp(corsApp)
      .build
      .use { _ =>
        IO.println("=" * 60)                                                          *>
        IO.println("  SummitCoin Node  |  SMT Blockchain")                            *>
        IO.println("=" * 60)                                                          *>
        startup                                                                       *>
        IO.println(s"  Listening on   http://0.0.0.0:$serverPort")                   *>
        IO.println(s"  Mining reward  ${SummitCoin.MINING_REWARD} SMT per block")    *>
        IO.println(s"  PoW difficulty ${Miner.difficulty} leading zeros")            *>
        IO.println("=" * 60)                                                          *>
        IO.never.onCancel(shutdown)
      }
  }
}
