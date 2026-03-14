import cats.effect.*
import com.comcast.ip4s.*
import doobie.*
import doobie.implicits.*
import doobie.hikari.HikariTransactor
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

case class TxRequest(sender: String, recipient: String, amount: Double)
case class FaucetRequest(address: String)

// ── Persistence ───────────────────────────────────────────────────────────────

/** Common interface for all persistence backends. */
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
    """.update.run.void

  def initSchema(): IO[Unit] = createTable.transact(xa)

  def save(chain: List[Block]): IO[Unit] =
    sql"""
      INSERT INTO blockchain (id, chain_data, saved_at)
      VALUES (1, ${chain.asJson.noSpaces}, NOW())
      ON CONFLICT (id) DO UPDATE SET
        chain_data = EXCLUDED.chain_data,
        saved_at   = EXCLUDED.saved_at
    """.update.run.void.transact(xa)

  def load(): IO[Option[List[Block]]] =
    sql"SELECT chain_data FROM blockchain WHERE id = 1"
      .query[String]
      .option
      .transact(xa)
      .map(_.flatMap(json => parser.decode[List[Block]](json).toOption))
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

  def load(): IO[Option[List[Block]]] = IO.blocking {
    val p = Paths.get(activeFilePath)
    if (!Files.exists(p)) None
    else parser.decode[List[Block]](Files.readString(p)).toOption
  }
}

// ── Memory-only backend (last resort if everything fails) ─────────────────────

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

  /** Build a HikariTransactor from a standard DATABASE_URL string. */
  private def makeTransactor(dbUrl: String): Resource[IO, HikariTransactor[IO]] = {
    // Supabase / Render give a URL like:
    //   postgresql://user:pass@host:5432/dbname
    // JDBC wants:
    //   jdbc:postgresql://host:5432/dbname  + separate user/pass
    val uri      = new java.net.URI(dbUrl.replace("postgresql://", "http://"))
    val jdbcUrl  = s"jdbc:postgresql://${uri.getHost}:${uri.getPort}${uri.getPath}?sslmode=require"
    val Array(user, pass) = uri.getUserInfo.split(":", 2)

    HikariTransactor.newHikariTransactor[IO](
      "org.postgresql.Driver",
      jdbcUrl,
      user,
      pass,
      scala.concurrent.ExecutionContext.global
    )
  }

  /** Save chain, log result, never propagate the error (so routes always succeed). */
  private def saveChain(backend: PersistenceBackend): IO[Unit] =
    backend.save(bc.getChain)
      .flatMap { _ =>
        val n = bc.getChain.length
        IO { lastSaveStatus = Right(Instant.now()) } *>
        IO.println(s"  Blockchain saved via ${backend.modeName} ($n blocks)")
      }
      .handleErrorWith { e =>
        val msg = s"${e.getClass.getName}: ${e.getMessage}"
        IO { lastSaveStatus = Left(s"Save failed: $msg") } *>
        IO.println(s"  ERROR: Blockchain save failed (${backend.modeName}): $msg")
      }

  // ── Route builder — takes the resolved backend as a parameter ─────────────

  def buildRoutes(backend: PersistenceBackend): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ── GET /health ───────────────────────────────────────────────────────────
    case GET -> Root / "health" =>
      Ok(Json.obj("status" -> "ok".asJson))

    // ── GET /status ───────────────────────────────────────────────────────────
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

    // ── GET /persist ──────────────────────────────────────────────────────────
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
        saveChain(backend) *>
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
                  saveChain(backend) *>
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

    val serverPort = sys.env.get("PORT")
      .flatMap(_.toIntOption)
      .flatMap(Port.fromInt)
      .getOrElse(port"8080")

    // Resolve which backend to use, then run the server inside its resource scope
    val program: Resource[IO, Unit] = sys.env.get("DATABASE_URL") match {

      case Some(dbUrl) =>
        // ── Database path ────────────────────────────────────────────────────
        makeTransactor(dbUrl).evalMap { xa =>
          val backend = new DbBackend(xa)
          val startup: IO[Unit] =
            IO.println("  [Persistence] Mode: database (Supabase PostgreSQL)") *>
            backend.initSchema() *>
            backend.load().flatMap {
              case None         => IO.println("  No existing blockchain found, starting fresh")
              case Some(blocks) =>
                IO.println(s"  Loading blockchain from database...") *>
                IO.blocking(bc.restoreFromBlocks(blocks))            *>
                IO.println(s"  Restored ${blocks.length} blocks from database")
            }.handleErrorWith { e =>
              IO.println(s"  Warning: DB load failed (${e.getMessage}), starting fresh")
            }

          val shutdown: IO[Unit] =
            IO.println("\n  Shutting down — saving blockchain to database...") *>
            backend.save(bc.getChain)
              .flatMap(_ => IO.println("  Blockchain saved to database. Goodbye!"))
              .handleErrorWith(e => IO.println(s"  WARNING: shutdown save failed: ${e.getMessage}"))

          runServer(backend, startup, shutdown, serverPort)
        }

      case None =>
        // ── File path (local dev) ─────────────────────────────────────────────
        Resource.eval {
          val backend = FileBackend
          val startup: IO[Unit] =
            IO.println("  [Persistence] Mode: file (DATABASE_URL not set)") *>
            backend.ensureDataDir() *>
            backend.load().flatMap {
              case None         => IO.println("  No existing blockchain found, starting fresh")
              case Some(blocks) =>
                IO.println(s"  Loading blockchain from ${FileBackend.activeFilePath}...") *>
                IO.blocking(bc.restoreFromBlocks(blocks))                                 *>
                IO.println(s"  Restored ${blocks.length} blocks from disk")
            }.handleErrorWith { e =>
              IO.println(s"  Warning: file load failed (${e.getMessage}), starting fresh")
            }

          val shutdown: IO[Unit] =
            IO.println("\n  Shutting down — saving blockchain to disk...") *>
            backend.save(bc.getChain)
              .flatMap(_ => IO.println(s"  Blockchain saved to ${FileBackend.activeFilePath}. Goodbye!"))
              .handleErrorWith(e => IO.println(s"  WARNING: shutdown save failed: ${e.getMessage}"))

          runServer(backend, startup, shutdown, serverPort)
        }
    }

    program.useForever
  }

  private def runServer(
    backend:    PersistenceBackend,
    startup:    IO[Unit],
    shutdown:   IO[Unit],
    serverPort: com.comcast.ip4s.Port
  ): IO[Unit] = {
    val corsApp = CORS.policy
      .withAllowOriginAll
      .withAllowMethodsAll
      .withAllowHeadersAll
      .apply(buildRoutes(backend).orNotFound)

    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(serverPort)
      .withHttpApp(corsApp)
      .build
      .use { _ =>
        IO.println("=" * 60)                                                        *>
        IO.println("  SummitCoin Node  |  SMT Blockchain")                          *>
        IO.println("=" * 60)                                                        *>
        startup                                                                     *>
        IO.println(s"  Listening on   http://0.0.0.0:$serverPort")                 *>
        IO.println(s"  Mining reward  ${SummitCoin.MINING_REWARD} SMT per block")  *>
        IO.println(s"  PoW difficulty ${Miner.difficulty} leading zeros")          *>
        IO.println("=" * 60)                                                        *>
        IO.never.onCancel(shutdown)
      }
  }
}
