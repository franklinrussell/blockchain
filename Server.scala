import cats.effect.*
import com.comcast.ip4s.*
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
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
import scala.concurrent.duration.*

// ── Request / Response shapes ─────────────────────────────────────────────────

case class TxRequest(sender: String, recipient: String, amount: Double)
case class FaucetRequest(address: String)

// ── Persistence ───────────────────────────────────────────────────────────────

sealed trait PersistenceBackend {
  def modeName: String
  def save(chain: List[Block]): IO[Unit]
  def load(): IO[Option[List[Block]]]
}

// ── Database backend (Supabase / PostgreSQL) ──────────────────────────────────

class DbBackend(xa: Transactor[IO]) extends PersistenceBackend {
  val modeName = "database"

  private val createTable: ConnectionIO[Unit] =
    sql"""
      CREATE TABLE IF NOT EXISTS blockchain (
        id         INTEGER PRIMARY KEY DEFAULT 1,
        chain_data TEXT    NOT NULL,
        saved_at   TIMESTAMP DEFAULT NOW()
      )
    """.update.run.map(_ => ())

  def initSchema(): IO[Unit] = createTable.transact(xa)

  def save(chain: List[Block]): IO[Unit] =
    sql"""
      INSERT INTO blockchain (id, chain_data, saved_at)
      VALUES (1, ${chain.asJson.noSpaces}, NOW())
      ON CONFLICT (id) DO UPDATE SET
        chain_data = EXCLUDED.chain_data,
        saved_at   = EXCLUDED.saved_at
    """.update.run.map(_ => ()).transact(xa)

  def load(): IO[Option[List[Block]]] =
    IO.println("  [DB] Querying database for existing blockchain...") *>
    sql"SELECT chain_data FROM blockchain WHERE id = 1"
      .query[String]
      .option
      .transact(xa)
      .flatMap {
        case None =>
          IO.println("  [DB] No row found (id=1) — table is empty") *>
          IO.pure(None)
        case Some(json) =>
          IO.println(s"  [DB] Row found, deserializing JSON (${json.length} chars)...") *>
          (parser.decode[List[Block]](json) match {
            case Right(blocks) =>
              IO.println(s"  [DB] Deserialized ${blocks.length} blocks successfully") *>
              IO.pure(Some(blocks))
            case Left(err) =>
              IO.println(s"  [DB] JSON decode FAILED: $err") *>
              IO.pure(None)
          })
      }
}

// ── File backend (local dev fallback) ────────────────────────────────────────

object FileBackend extends PersistenceBackend {
  val modeName = "file"

  private val requestedDir = sys.env.getOrElse("DATA_DIR", "./data")
  @volatile private var _activeDir: String = requestedDir

  def activeFilePath: String  = s"${_activeDir}/blockchain.json"
  private def tmpPath: String = activeFilePath + ".tmp"

  private def isWritable(dir: java.nio.file.Path): Boolean =
    try {
      val probe = dir.resolve(".write-probe")
      Files.writeString(probe, "ok")
      Files.deleteIfExists(probe)
      true
    } catch { case _: Throwable => false }

  def ensureDataDir(): IO[Unit] = IO.blocking {
    val primary = Paths.get(requestedDir)
    def trySetup(dir: java.nio.file.Path): Boolean =
      try { Files.createDirectories(dir); isWritable(dir) }
      catch { case _: Throwable => false }

    if (trySetup(primary)) {
      println(s"  [File] Data directory ready: $requestedDir")
    } else {
      println(s"  [File] WARNING: $requestedDir not writable — falling back to ./data")
      val fallback = Paths.get("./data")
      if (trySetup(fallback)) {
        _activeDir = "./data"
        println(s"  [File] Using fallback: ./data (ephemeral — data lost on restart)")
      } else {
        println(s"  [File] ERROR: ./data fallback also failed — persistence disabled")
      }
    }
    println(s"  [File] Active file: ${activeFilePath}")
  }

  def save(chain: List[Block]): IO[Unit] = IO.blocking {
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
  }

  def load(): IO[Option[List[Block]]] =
    IO.blocking {
      val p = Paths.get(activeFilePath)
      if (!Files.exists(p)) None else Some(Files.readString(p))
    }.flatMap {
      case None =>
        IO.println(s"  [File] No file at $activeFilePath — starting fresh") *>
        IO.pure(None)
      case Some(json) =>
        IO.println(s"  [File] Read ${json.length} chars from $activeFilePath, deserializing...") *>
        (parser.decode[List[Block]](json) match {
          case Right(blocks) =>
            IO.println(s"  [File] Deserialized ${blocks.length} blocks successfully") *>
            IO.pure(Some(blocks))
          case Left(err) =>
            IO.println(s"  [File] JSON decode FAILED: $err") *>
            IO.pure(None)
        })
    }
}

// ── Memory-only backend (last resort) ────────────────────────────────────────

object MemoryBackend extends PersistenceBackend {
  val modeName = "memory"
  def save(chain: List[Block]): IO[Unit] = IO.unit
  def load(): IO[Option[List[Block]]]    = IO.pure(None)
}

// ── SummitCoin REST Node ──────────────────────────────────────────────────────

object SummitCoinNode extends IOApp.Simple {

  private val bc = new Blockchain()

  @volatile private var lastSaveStatus: Either[String, Instant] = Left("never")

  given EntityDecoder[IO, TxRequest]     = jsonOf[IO, TxRequest]
  given EntityDecoder[IO, FaucetRequest] = jsonOf[IO, FaucetRequest]

  /**
   * Build a HikariTransactor from a standard postgresql:// URL.
   * Uses doobie's ExecutionContexts.fixedThreadPool for the connect EC
   * so that blocking JDBC connection acquisition runs on a dedicated
   * thread pool, not the cats-effect compute pool.
   */
  private def makeTransactor(dbUrl: String): Resource[IO, HikariTransactor[IO]] = {
    val uri     = new java.net.URI(dbUrl.replace("postgresql://", "http://"))
    // Neon omits the port (uri.getPort == -1); Supabase includes it.
    // Always append sslmode=require — both Neon and Supabase require SSL.
    val portStr = if (uri.getPort > 0) s":${uri.getPort}" else ""
    val jdbcUrl = s"jdbc:postgresql://${uri.getHost}$portStr${uri.getPath}?sslmode=require"
    val Array(user, pass) = uri.getUserInfo.split(":", 2)

    ExecutionContexts.fixedThreadPool[IO](8).flatMap { connectEC =>
      HikariTransactor.newHikariTransactor[IO](
        "org.postgresql.Driver",
        jdbcUrl,
        user,
        pass,
        connectEC
      )
    }
  }

  /** Save chain, log result, swallow errors so routes always succeed. */
  private def saveChain(backend: PersistenceBackend, context: String = "save"): IO[Unit] =
    IO.println(s"  [$context] Saving to ${backend.modeName}...") *>
    backend.save(bc.getChain)
      .flatMap { _ =>
        val n = bc.getChain.length
        IO { lastSaveStatus = Right(Instant.now()) } *>
        IO.println(s"  [$context] Saved successfully ($n blocks)")
      }
      .handleErrorWith { e =>
        val msg = s"${e.getClass.getName}: ${e.getMessage}"
        IO { lastSaveStatus = Left(s"Save failed: $msg") } *>
        IO.println(s"  [$context] Save FAILED: $msg")
      }

  // ── Routes ───────────────────────────────────────────────────────────────────

  def buildRoutes(backend: PersistenceBackend): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson))

    case GET -> Root / "status" =>
      val saveInfo = lastSaveStatus match {
        case Right(ts) => ts.toString
        case Left(msg) => msg
      }
      Ok(Json.obj(
        "blockHeight"     -> bc.chainHeight.asJson,
        "totalBlocks"     -> bc.getChain.length.asJson,
        "smtInSupply"     -> bc.utxoPool.totalSupply.asJson,
        "chainValid"      -> bc.isValid.asJson,
        "persistenceMode" -> backend.modeName.asJson,
        "lastSaved"       -> saveInfo.asJson
      ))

    case GET -> Root / "persist" =>
      backend.save(bc.getChain)
        .flatMap { _ =>
          val n = bc.getChain.length
          IO { lastSaveStatus = Right(Instant.now()) } *>
          IO.println(s"  Manual /persist: saved $n blocks via ${backend.modeName}") *>
          Ok(Json.obj(
            "success" -> true.asJson,
            "message" -> s"Saved $n blocks via ${backend.modeName}".asJson
          ))
        }
        .handleErrorWith { e =>
          val msg = s"${e.getClass.getName}: ${e.getMessage}"
          IO { lastSaveStatus = Left(s"Save failed: $msg") } *>
          IO.println(s"  Manual /persist FAILED: $msg") *>
          Ok(Json.obj("success" -> false.asJson, "error" -> msg.asJson))
        }

    case POST -> Root / "wallet" / "new" =>
      val wallet = Wallet.generate()
      Ok(Json.obj(
        "address" -> wallet.address.asJson,
        "balance" -> 0.0.asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    case GET -> Root / "balance" / address =>
      Ok(Json.obj(
        "address" -> address.asJson,
        "balance" -> bc.getBalance(address).asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    case GET -> Root / "chain" =>
      Ok(Json.obj(
        "height"  -> bc.chainHeight.asJson,
        "length"  -> bc.getChain.length.asJson,
        "supply"  -> bc.utxoPool.totalSupply.asJson,
        "isValid" -> bc.isValid.asJson,
        "blocks"  -> bc.getChain.asJson
      ))

    case GET -> Root / "transactions" / "pending" =>
      val pending = bc.mempool.peek
      Ok(Json.obj(
        "count"        -> pending.length.asJson,
        "transactions" -> pending.asJson
      ))

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

    case GET -> Root / "mine" =>
      IO.blocking(bc.mineBlock()).flatMap { block =>
        IO.println(s"  Block #${block.index} mined, saving to ${backend.modeName}...") *>
        saveChain(backend, s"Block #${block.index}") *>
        Ok(Json.obj(
          "success" -> true.asJson,
          "message" -> s"Block #${block.index} mined — fresh tracks on the chain!".asJson,
          "block"   -> block.asJson
        ))
      }

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
                  IO.println(s"  Block #${block.index} mined (faucet), saving to ${backend.modeName}...") *>
                  saveChain(backend, s"Block #${block.index} faucet") *>
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

    // Read PORT as a plain Int first, then convert — avoids Option.flatMap chains
    // that could silently fall back to 8080 on Render's injected port value.
    val portNum    = sys.env.getOrElse("PORT", "8080").toInt
    val serverPort = Port.fromInt(portNum).getOrElse(port"8080")

    // Resolve persistence backend as a Resource so the DB connection pool
    // is acquired before startup and released cleanly on shutdown.
    val backendResource: Resource[IO, PersistenceBackend] =
      sys.env.get("DATABASE_URL") match {
        case Some(dbUrl) =>
          makeTransactor(dbUrl).evalMap { xa =>
            val backend = new DbBackend(xa)
            IO.println("  [Persistence] Mode: database (Supabase PostgreSQL)") *>
            backend.initSchema().as(backend: PersistenceBackend)
          }
        case None =>
          Resource.eval(
            IO.println("  [Persistence] Mode: file (DATABASE_URL not set)") *>
            FileBackend.ensureDataDir().as(FileBackend: PersistenceBackend)
          )
      }

    // Run: acquire backend → restore chain → start server → block forever
    // NOTE: loadChain runs BEFORE EmberServerBuilder starts so the chain is
    // fully restored before the first HTTP request can arrive.
    backendResource.use { backend =>

      val loadChain: IO[Unit] =
        IO.println("  Querying database for existing blockchain...") *>
        backend.load().flatMap {
          case None =>
            IO.println("  Database returned empty — starting fresh with genesis block")
          case Some(blocks) =>
            IO.println(s"  Found ${blocks.length} blocks in database, restoring...") *>
            IO.blocking(bc.restoreFromBlocks(blocks))                                *>
            // IO.delay defers evaluation so bc.chainHeight is read AFTER restoreFromBlocks runs
            IO.delay(s"  Restored blockchain: height=${bc.chainHeight}, supply=${bc.utxoPool.totalSupply} SMT")
              .flatMap(IO.println)
        }.handleErrorWith { e =>
          IO.println(s"  Database query FAILED: ${e.getClass.getName}: ${e.getMessage}") *>
          IO.println(s"  Starting fresh due to error")
        }

      val shutdown: IO[Unit] =
        IO.delay(bc.getChain.length).flatMap { n =>
          IO.println(s"\n  Shutting down — saving $n blocks to ${backend.modeName}...") *>
          backend.save(bc.getChain)
            .flatMap(_ => IO.println(s"  Blockchain saved ($n blocks). Goodbye!"))
            .handleErrorWith(e => IO.println(s"  WARNING: shutdown save failed: ${e.getMessage}"))
        }

      val corsApp = CORS.policy
        .withAllowOriginAll
        .withAllowMethodsAll
        .withAllowHeadersAll
        .apply(buildRoutes(backend).orNotFound)

      // Restore chain BEFORE the server socket opens so no request sees stale state
      loadChain *>
      EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("0.0.0.0").get)
        .withPort(serverPort)
        .withHttpApp(corsApp)
        .build
        .use { _ =>
          val periodicSave: IO[Nothing] =
            (IO.sleep(30.seconds) *> saveChain(backend, "auto-save")).foreverM

          IO.println("=" * 60)                                                       *>
          IO.println("  SummitCoin Node  |  SMT Blockchain")                         *>
          IO.println("=" * 60)                                                       *>
          IO.println(s"  PORT env       = ${sys.env.getOrElse("PORT", "(not set)")}") *>
          IO.println(s"  Binding to     0.0.0.0:$serverPort")                       *>
          IO.println(s"  Listening on   http://0.0.0.0:$serverPort")                *>
          IO.println(s"  Mining reward  ${SummitCoin.MINING_REWARD} SMT per block") *>
          IO.println(s"  PoW difficulty ${Miner.difficulty} leading zeros")         *>
          IO.println(s"  Auto-save      every 30 seconds")                          *>
          IO.println("=" * 60)                                                       *>
          periodicSave.background.surround(IO.never.onCancel(shutdown))
        }
    }
  }
}
