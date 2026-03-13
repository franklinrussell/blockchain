import cats.effect.*
import com.comcast.ip4s.*
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.CORS
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

// ── Request / Response shapes ─────────────────────────────────────────────────

/** Body for POST /transactions */
case class TxRequest(sender: String, recipient: String, amount: Double)

/** Body for POST /faucet */
case class FaucetRequest(address: String)

// ── SummitCoin REST Node ──────────────────────────────────────────────────────

object SummitCoinNode extends IOApp.Simple {

  // Single shared blockchain instance (in-memory, not thread-safe by design —
  // for a production node wrap in cats.effect.Ref)
  private val bc = new Blockchain()

  // circe decoders for incoming JSON request bodies
  given EntityDecoder[IO, TxRequest]    = jsonOf[IO, TxRequest]
  given EntityDecoder[IO, FaucetRequest] = jsonOf[IO, FaucetRequest]

  // ── Routes ──────────────────────────────────────────────────────────────────

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ── POST /wallet/new ─────────────────────────────────────────────────────
    // Generate a fresh wallet address.
    case POST -> Root / "wallet" / "new" =>
      val wallet = Wallet.generate()
      Ok(Json.obj(
        "address" -> wallet.address.asJson,
        "balance" -> 0.0.asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    // ── GET /balance/:address ─────────────────────────────────────────────────
    // Return the SMT balance for a wallet address.
    case GET -> Root / "balance" / address =>
      Ok(Json.obj(
        "address" -> address.asJson,
        "balance" -> bc.getBalance(address).asJson,
        "unit"    -> SummitCoin.TICKER.asJson
      ))

    // ── GET /chain ────────────────────────────────────────────────────────────
    // Return the full blockchain as JSON.
    case GET -> Root / "chain" =>
      Ok(Json.obj(
        "height"  -> bc.chainHeight.asJson,
        "length"  -> bc.getChain.length.asJson,
        "supply"  -> bc.utxoPool.totalSupply.asJson,
        "isValid" -> bc.isValid.asJson,
        "blocks"  -> bc.getChain.asJson
      ))

    // ── GET /transactions/pending ─────────────────────────────────────────────
    // View the mempool.
    case GET -> Root / "transactions" / "pending" =>
      val pending = bc.mempool.peek
      Ok(Json.obj(
        "count"        -> pending.length.asJson,
        "transactions" -> pending.asJson
      ))

    // ── POST /transactions ────────────────────────────────────────────────────
    // Submit a new spend transaction to the mempool.
    // Body: { "sender": "SMT...", "recipient": "SMT...", "amount": 25.0 }
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
    // Mine all pending mempool transactions into a new block.
    // Automatically prepends a 50 SMT coinbase reward to summit-node.
    case GET -> Root / "mine" =>
      // Mining is CPU-bound — run on the blocking thread pool
      IO.blocking(bc.mineBlock()).flatMap { block =>
        Ok(Json.obj(
          "success" -> true.asJson,
          "message" -> s"Block #${block.index} mined — fresh tracks on the chain!".asJson,
          "block"   -> block.asJson
        ))
      }

    // ── POST /faucet ──────────────────────────────────────────────────────────
    // Drip 25 SMT to any address from the node wallet, then mine immediately.
    // Body: { "address": "SMT..." }
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
      .use { server =>
        IO.println("=" * 60)  *>
        IO.println("  SummitCoin Node  |  SMT Blockchain")  *>
        IO.println("=" * 60)  *>
        IO.println(s"  Listening on   http://0.0.0.0:8080") *>
        IO.println(s"  Mining reward  ${SummitCoin.MINING_REWARD} SMT per block") *>
        IO.println(s"  PoW difficulty ${Miner.difficulty} leading zeros") *>
        IO.println("=" * 60)  *>
        IO.never
      }
  }
}
