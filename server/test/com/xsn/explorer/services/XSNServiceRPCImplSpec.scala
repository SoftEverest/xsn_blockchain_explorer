package com.xsn.explorer.services

import akka.actor.ActorSystem
import com.xsn.explorer.config.{ExplorerConfig, RPCConfig, RetryConfig}
import com.xsn.explorer.errors.TransactionError.{
  InvalidRawTransaction,
  MissingInputs,
  RawTransactionAlreadyExists,
  UnconfirmedTransaction
}
import com.xsn.explorer.errors._
import com.xsn.explorer.helpers.{BlockLoader, DataHelper, Executors, TransactionLoader}
import com.xsn.explorer.models.TPoSContract
import com.xsn.explorer.models.TPoSContract.Commission
import com.xsn.explorer.models.rpc.Masternode
import com.xsn.explorer.models.rpc.Merchantnode
import com.xsn.explorer.models.values._
import org.mockito.ArgumentMatchers._
import org.mockito.MockitoSugar._
import org.scalactic.{Bad, Good, One}
import org.scalatest.concurrent.ScalaFutures._
import org.scalatest.matchers.must.Matchers._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AsyncWordSpec
import play.api.libs.json.{JsNull, JsString, JsValue, Json}
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import scala.annotation.nowarn
import scala.concurrent.Future
import scala.concurrent.duration._

@nowarn
class XSNServiceRPCImplSpec extends AsyncWordSpec with BeforeAndAfterAll {

  override def afterAll: Unit = {
    actorSystem.terminate()
    ()
  }

  import DataHelper._

  val ws = mock[WSClient]
  val ec = Executors.externalServiceEC
  val actorSystem = ActorSystem()
  val scheduler = actorSystem.scheduler

  val rpcConfig = new RPCConfig {
    override def password: RPCConfig.Password = RPCConfig.Password("pass")
    override def host: RPCConfig.Host = RPCConfig.Host("localhost")
    override def username: RPCConfig.Username = RPCConfig.Username("user")
  }

  val explorerConfig = new ExplorerConfig {
    override def genesisBlock: Blockhash =
      Blockhash
        .from(
          "00000c822abdbb23e28f79a49d29b41429737c6c7e15df40d1b1f1b35907ae34"
        )
        .get

    override def liteVersionConfig: ExplorerConfig.LiteVersionConfig = ???
  }

  val retryConfig = RetryConfig(1.millisecond, 2.milliseconds)

  val request = mock[WSRequest]
  val response = mock[WSResponse]
  when(ws.url(anyString)).thenReturn(request)
  when(request.withAuth(anyString(), anyString(), any())).thenReturn(request)
  when(request.withHttpHeaders(any())).thenReturn(request)

  val service =
    new XSNServiceRPCImpl(ws, rpcConfig, explorerConfig, retryConfig)(
      ec,
      scheduler
    )

  def createRPCSuccessfulResponse(result: JsValue): String = {
    s"""
       |{
       |  "result": ${Json.prettyPrint(result)},
       |  "id": null,
       |  "error": null
       |}
     """.stripMargin
  }

  def createRPCErrorResponse(errorCode: Int, message: String): String = {
    s"""
       |{
       |  "result": null,
       |  "id": null,
       |  "error": {
       |    "code": $errorCode,
       |    "message": "$message"
       |  }
       |}
     """.stripMargin
  }

  "getTransaction" should {
    "handle coinbase" in {
      val txid = createTransactionId(
        "024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
      )
      val responseBody =
        createRPCSuccessfulResponse(TransactionLoader.json(txid.string))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getTransaction(txid)) { result =>
        result.isGood mustEqual true

        val tx = result.get
        tx.id.string mustEqual "024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
        tx.vin.isEmpty mustEqual true
        tx.vout.size mustEqual 1
      }
    }

    "handle non-coinbase result" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )
      val responseBody =
        createRPCSuccessfulResponse(TransactionLoader.json(txid.string))

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getTransaction(txid)) { result =>
        result.isGood mustEqual true

        val tx = result.get
        tx.id.string mustEqual txid.string
        tx.vin.size mustEqual 1
        tx.vin.head.txid.string mustEqual "585cec5009c8ca19e83e33d282a6a8de65eb2ca007b54d6572167703768967d9"
        tx.vout.size mustEqual 3
      }
    }

    "handle transaction having no blocktime, nor time" in {
      // TODO: Remove this test when https://github.com/X9Developers/XSN/issues/72 is fixed.
      val txid = createTransactionId(
        "f24cd135c34ebb9032f8bc5b45599f1424980d34583df2847c4a4db584c94e97"
      )
      val responseBody =
        createRPCSuccessfulResponse(TransactionLoader.json(txid.string))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getTransaction(txid)) { result =>
        result.isGood mustEqual true

        val tx = result.get
        tx.id mustEqual txid
      }
    }

    "handle transaction not found" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )
      val responseBody =
        createRPCErrorResponse(-5, "No information available about transaction")
      val json = Json.parse(responseBody)
      mockRequest(request, response)(500, json)

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(TransactionError.NotFound(txid)).accumulating
      }
    }

    "handle error with message" in {
      val errorMessage = "Params must be an array"
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )
      val responseBody = createRPCErrorResponse(-32600, errorMessage)
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getTransaction(txid)) { result =>
        val error = XSNMessageError(errorMessage)
        result mustEqual Bad(error).accumulating
      }
    }

    "handle non successful status" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )

      mockRequest(request, response)(403, JsNull)

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(XSNUnexpectedResponseError).accumulating
      }
    }

    "handle unexpected error" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )

      val responseBody = """{"result":null,"error":{},"id":null}"""
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getTransaction(txid)) { result =>
        result mustEqual Bad(XSNUnexpectedResponseError).accumulating
      }
    }

    "handle work queue depth exceeded" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )

      val responseBody = createRPCErrorResponse(-1, "Work queue depth exceeded")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      service.getTransaction(txid).map { result =>
        result mustEqual Bad(XSNWorkQueueDepthExceeded).accumulating
      }
    }

    "handle work queue depth exceeded (no json)" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )

      val responseBody = "Work queue depth exceeded"

      mockRequestString(request, response)(500, responseBody)

      service.getTransaction(txid).map { result =>
        result mustEqual Bad(XSNWorkQueueDepthExceeded).accumulating
      }
    }

    "handle xsn server warming up" in {
      val txid = createTransactionId(
        "0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641"
      )

      val responseBody = createRPCErrorResponse(-28, "Loading block index...")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      service.getTransaction(txid).map { result =>
        result mustEqual Bad(XSNWarmingUp).accumulating
      }
    }

    "handle unconfirmed transaction" in {
      val txid = createTransactionId(
        "6b984d317623fdb3f40e5d64a4236de33b9cb1de5f12a6abe2e8f242f6572655"
      )
      val responseBody =
        createRPCSuccessfulResponse(TransactionLoader.json(txid.toString))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      service.getTransaction(txid).map { result =>
        result mustEqual Bad(One(UnconfirmedTransaction))
      }
    }
  }

  "getRawTransaction" should {
    "retrieve the raw transaction" in {
      val txid = createTransactionId(
        "024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
      )
      val expected = TransactionLoader.json(txid.string)

      val responseBody =
        createRPCSuccessfulResponse(TransactionLoader.json(txid.string))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getRawTransaction(txid)) { result =>
        result mustEqual Good(expected)
      }
    }
  }

  "getBlock" should {
    "return the genesis block" in {
      val block = BlockLoader.json(
        "00000c822abdbb23e28f79a49d29b41429737c6c7e15df40d1b1f1b35907ae34"
      )
      val responseBody = createRPCSuccessfulResponse(block)
      val blockhash = Blockhash
        .from(
          "00000c822abdbb23e28f79a49d29b41429737c6c7e15df40d1b1f1b35907ae34"
        )
        .get

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlock(blockhash)) { result =>
        result.isGood mustEqual true

        val block = result.get
        block.hash mustEqual blockhash
        block.transactions mustEqual List.empty
      }
    }

    "return a block" in {
      val block = BlockLoader.json(
        "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
      )
      val responseBody = createRPCSuccessfulResponse(block)
      val blockhash = Blockhash
        .from(
          "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
        )
        .get

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlock(blockhash)) { result =>
        result.isGood mustEqual true

        val block = result.get
        block.hash.string mustEqual "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
        block.transactions.size mustEqual 2
      }
    }

    "return a TPoS block" in {
      val block = BlockLoader.json(
        "a3a9fb111a3f85c3d920c2dc58ce14d541a65763834247ef958aa3b4d665ef9c"
      )
      val responseBody = createRPCSuccessfulResponse(block)
      val blockhash = Blockhash
        .from(
          "a3a9fb111a3f85c3d920c2dc58ce14d541a65763834247ef958aa3b4d665ef9c"
        )
        .get

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlock(blockhash)) { result =>
        result.isGood mustEqual true

        val block = result.get
        block.hash.string mustEqual blockhash.string
        block.transactions.size mustEqual 2
      }
    }

    "fail on unknown block" in {
      val responseBody =
        """{"result":null,"error":{"code":-5,"message":"Block not found"},"id":null}"""

      val blockhash = Blockhash
        .from(
          "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b80"
        )
        .get

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlock(blockhash)) { result =>
        result mustEqual Bad(BlockNotFoundError).accumulating
      }
    }
  }

  "getRawBlock" should {
    "return a block" in {
      val block = BlockLoader.json(
        "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
      )
      val responseBody = createRPCSuccessfulResponse(block)
      val blockhash = Blockhash
        .from(
          "b72dd1655408e9307ef5874be20422ee71029333283e2360975bc6073bdb2b81"
        )
        .get

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getRawBlock(blockhash)) { result =>
        result mustEqual Good(block)
      }
    }
  }

  "getBlockhash" should {
    "return the blockhash" in {
      val blockhash = Blockhash
        .from(
          "00000766115b26ecbc09cd3a3db6870fdaf2f049d65a910eb2f2b48b566ca7bd"
        )
        .get
      val responseBody = createRPCSuccessfulResponse(JsString(blockhash.string))

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlockhash(Height(3))) { result =>
        result mustEqual Good(blockhash)
      }
    }

    "fail on unknown block" in {
      val responseBody = createRPCErrorResponse(-8, "Block height out of range")

      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getBlockhash(Height(-1))) { result =>
        result mustEqual Bad(BlockNotFoundError).accumulating
      }
    }
  }

  "getServerStatistics" should {
    "return the statistics" in {
      val content =
        """
          |{
          |  "height": 45204,
          |  "bestblock": "60da3eccf50f10254dcc35c9a25006e129bc5f0d101f83bad5ce008cc4b47c75",
          |  "transactions": 93047,
          |  "txouts": 142721,
          |  "hash_serialized_2": "1f439c1d43753b9935abeb8a8de9f9010b96f7533ccfdde4432de3648a6f20de",
          |  "disk_size": 7105097,
          |  "total_amount": 77634169.93285364
          |}
        """.stripMargin

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getServerStatistics()) { result =>
        result.isGood mustEqual true

        val stats = result.get
        stats.height mustEqual Height(45204)
        stats.transactions mustEqual 93047
        stats.totalSupply mustEqual BigDecimal("77634169.93285364")
      }
    }
  }

  "getMasternodeCount" should {
    "return the count" in {
      val content = "10"

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getMasternodeCount()) { result =>
        result mustEqual Good(10)
      }
    }
  }

  "getDifficulty" should {
    "return the difficulty" in {
      val content = "129.1827211827212"

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getDifficulty()) { result =>
        result mustEqual Good(129.1827211827212)
      }
    }
  }

  "getMasternodes" should {
    "return the masternodes" in {
      val content =
        """
          |{
          |  "COutPoint(c3efb8b60bda863a3a963d340901dc2b870e6ea51a34276a8f306d47ffb94f01, 0)": "           WATCHDOG_EXPIRED 70209 XqdmM7rop8Sdgn8UjyNh3Povc3rhNSXYw2 1532897292        0          0      0 45.77.136.212:62583",
          |  "COutPoint(b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01, 1)": "           ENABLED 70209 XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3 1532905050     6010 1532790407 202522 45.32.148.13:62583"
          |}
        """.stripMargin

      val expected = List(
        Masternode(
          txid = TransactionId
            .from(
              "c3efb8b60bda863a3a963d340901dc2b870e6ea51a34276a8f306d47ffb94f01"
            )
            .get,
          ip = "45.77.136.212:62583",
          protocol = "70209",
          status = "WATCHDOG_EXPIRED",
          activeSeconds = 0,
          lastSeen = 1532897292,
          Address.from("XqdmM7rop8Sdgn8UjyNh3Povc3rhNSXYw2").get
        ),
        Masternode(
          txid = TransactionId
            .from(
              "b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01"
            )
            .get,
          ip = "45.32.148.13:62583",
          protocol = "70209",
          status = "ENABLED",
          activeSeconds = 6010,
          lastSeen = 1532905050,
          Address.from("XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3").get
        )
      )
      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getMasternodes()) { result =>
        result.isGood mustEqual true

        val masternodes = result.get
        masternodes mustEqual expected
      }
    }
  }

  "getMerchantnodes" should {
    "return the merchant nodes" in {
      val content =
        """
          |{
          |  "36383165613065623435373332353634303664656666653535303735616465343966306433363232": "           WATCHDOG_EXPIRED 70209 XqdmM7rop8Sdgn8UjyNh3Povc3rhNSXYw2 c3efb8b60bda863a3a963d340901dc2b870e6ea51a34276a8f306d47ffb94f01 1532897292 0 45.77.136.212:62583",
          |  "36383165613065623435373332353634303664656666653535303735616465343966306433363233": "           ENABLED 70209 XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3 b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01 1532905050 6010 45.32.148.13:62583"
          |}
        """.stripMargin

      val expected = List(
        Merchantnode(
          pubkey = "36383165613065623435373332353634303664656666653535303735616465343966306433363232",
          txid = TransactionId
            .from(
              "c3efb8b60bda863a3a963d340901dc2b870e6ea51a34276a8f306d47ffb94f01"
            )
            .get,
          ip = "45.77.136.212:62583",
          protocol = "70209",
          status = "WATCHDOG_EXPIRED",
          activeSeconds = 0,
          lastSeen = 1532897292,
          Address.from("XqdmM7rop8Sdgn8UjyNh3Povc3rhNSXYw2").get
        ),
        Merchantnode(
          pubkey = "36383165613065623435373332353634303664656666653535303735616465343966306433363233",
          txid = TransactionId
            .from(
              "b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01"
            )
            .get,
          ip = "45.32.148.13:62583",
          protocol = "70209",
          status = "ENABLED",
          activeSeconds = 6010,
          lastSeen = 1532905050,
          Address.from("XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3").get
        )
      )
      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.getMerchantnodes()) { result =>
        result.isGood mustEqual true

        val merchantnodes = result.get
        merchantnodes mustEqual expected
      }
    }
  }

  "getMasternode" should {
    "return the masternode" in {
      val content =
        """
          |{
          |  "COutPoint(b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01, 1)": "           ENABLED 70209 XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3 1532905050     6010 1532790407 202522 45.32.148.13:62583"
          |}
        """.stripMargin

      val expected = Masternode(
        txid = TransactionId
          .from(
            "b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01"
          )
          .get,
        ip = "45.32.148.13:62583",
        protocol = "70209",
        status = "ENABLED",
        activeSeconds = 6010,
        lastSeen = 1532905050,
        Address.from("XdNDRAiMUC9KiVRzhCTg9w44jQRdCpCRe3").get
      )

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val ip = IPAddress.from("45.32.148.13").get
      whenReady(service.getMasternode(ip)) { result =>
        result mustEqual Good(expected)
      }
    }

    "fail when the masternode is not found" in {
      val content =
        """
          |{}
        """.stripMargin

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val ip = IPAddress.from("45.32.148.13").get
      whenReady(service.getMasternode(ip)) { result =>
        result mustEqual Bad(MasternodeNotFoundError).accumulating
      }
    }
  }

  "getUnspentOutputs" should {
    "get the results" in {
      val content =
        """
          |[
          |    {
          |        "address": "XeNEPsgeWqNbrEGEN5vqv4wYcC3qQrqNyp",
          |        "height": 22451,
          |        "outputIndex": 0,
          |        "satoshis": 1500000000000,
          |        "script": "76a914285b6f1ccacea0059ff5393cb4eb2f0569e2b3e988ac",
          |        "txid": "ea837f2011974b6a1a2fa077dc33684932c514a4ec6febc10e1a19ebe1336539"
          |    },
          |    {
          |        "address": "XeNEPsgeWqNbrEGEN5vqv4wYcC3qQrqNyp",
          |        "height": 25093,
          |        "outputIndex": 3,
          |        "satoshis": 2250000000,
          |        "script": "76a914285b6f1ccacea0059ff5393cb4eb2f0569e2b3e988ac",
          |        "txid": "96a06b802d1c15818a42aa9b46dd2e236cde746000d35f74d3eb940ab9d5694d"
          |    }
          |]
        """.stripMargin

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val address =
        DataHelper.createAddress("XeNEPsgeWqNbrEGEN5vqv4wYcC3qQrqNyp")
      whenReady(service.getUnspentOutputs(address)) { result =>
        result mustEqual Good(Json.parse(content))
      }
    }
  }

  "isTPoSContract" should {
    "return true when the contract is valid" in {
      val txid = createTransactionId(
        "b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01"
      )
      val content = "Contract is valid"
      val responseBody = createRPCSuccessfulResponse(JsString(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.isTPoSContract(txid)) { result =>
        result mustEqual Good(true)
      }
    }

    "return false when the contract is not valid" in {
      val txid = createTransactionId(
        "b02f99d87194c9400ab147c070bf621770684906dedfbbe9ba5f3a35c26b8d01"
      )
      val content = "Contract invalid, error: Signature invalid"
      val responseBody = createRPCSuccessfulResponse(JsString(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.isTPoSContract(txid)) { result =>
        result mustEqual Good(false)
      }
    }
  }

  "estimateSmartFee" should {
    "return the result" in {
      val content = """{"feerate":0.00001,"blocks":2}"""
      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      whenReady(service.estimateSmartFee(1)) { result =>
        result mustEqual Good(Json.parse(content))
      }
    }
  }

  "getTxOut" should {
    "return value" in {
      val content =
        """
          |{
          |    "bestblock": "02b785c79a55c70beb120920a3df1d3e130724bd2ce188286f9d51c435bd5538",
          |    "coinbase": false,
          |    "confirmations": 1141,
          |    "scriptPubKey": {
          |        "addresses": [
          |            "Xccc7iGpKfgNhLsScUUWZwmTKmPYwb43qN"
          |        ],
          |        "asm": "OP_DUP OP_HASH160 1523235378b73bad58c8e580b7ecc59057e923fa OP_EQUALVERIFY OP_CHECKSIG",
          |        "hex": "76a9141523235378b73bad58c8e580b7ecc59057e923fa88ac",
          |        "reqSigs": 1,
          |        "type": "pubkeyhash"
          |    },
          |    "value": 3233.5
          |}
          |""".stripMargin
      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val txid = createTransactionId(
        "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
      )
      whenReady(service.getTxOut(txid, index = 1, includeMempool = true)) { result =>
        result.isGood mustEqual true
        (result.get \ "scriptPubKey" \ "hex")
          .as[
            String
          ] mustEqual "76a9141523235378b73bad58c8e580b7ecc59057e923fa88ac"
        (result.get \ "value").as[Double] mustEqual 3233.5
      }
    }

    "return a non exist transaction" in {
      val content = "null".stripMargin
      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val txid = createTransactionId(
        "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54c"
      )
      whenReady(service.getTxOut(txid, index = 0, includeMempool = true)) { result =>
        result mustEqual Good(JsNull)
      }
    }
  }

  "sendRawTransaction" should {
    "return the transaction hash" in {
      val content = JsString(
        "ae74538baa914f3799081ba78429d5d84f36a0127438e9f721dff584ac17b346"
      )
      val responseBody = createRPCSuccessfulResponse(content)
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val transaction = HexString
        .from(
          "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
        )
        .get
      whenReady(service.sendRawTransaction(transaction)) { result =>
        result.isGood mustEqual true
        result.get mustEqual "ae74538baa914f3799081ba78429d5d84f36a0127438e9f721dff584ac17b346"
      }
    }

    "return InvalidRawTransaction on error code -26" in {
      val responseBody = createRPCErrorResponse(-26, "error")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val transaction = HexString
        .from(
          "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
        )
        .get
      whenReady(service.sendRawTransaction(transaction)) { result =>
        result mustEqual Bad(One(InvalidRawTransaction))
      }
    }

    "return MissingInputs on error code -25" in {
      val responseBody = createRPCErrorResponse(-25, "error")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val transaction = HexString
        .from(
          "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
        )
        .get
      whenReady(service.sendRawTransaction(transaction)) { result =>
        result mustEqual Bad(One(MissingInputs))
      }
    }

    "return InvalidRawTransaction on error code -22" in {
      val responseBody = createRPCErrorResponse(-22, "error")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val transaction = HexString
        .from(
          "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
        )
        .get
      whenReady(service.sendRawTransaction(transaction)) { result =>
        result mustEqual Bad(One(InvalidRawTransaction))
      }
    }

    "return RawTransactionAlreadyExists on error code -27" in {
      val responseBody = createRPCErrorResponse(-27, "error")
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val transaction = HexString
        .from(
          "af30877625d8f1387399e24bc52626f3c316fb9ec844a5770f7dbd132e34b54b"
        )
        .get
      whenReady(service.sendRawTransaction(transaction)) { result =>
        result mustEqual Bad(One(RawTransactionAlreadyExists))
      }
    }
  }

  "encodeTPOSContract" should {
    "return the tpos contract encoded" in {
      val content = JsString(
        "020000000a001976a91495cf859d7a40c5d7fded2a03cb8d7dcf307eab1188ac1976a914a7e2ba4e0d91273d686f446fa04ca5fe800d452d88ac41201f2d052fb372248f89f9f2c9106be9a670d5538c01e4f39215c92717b847d3ea2466e7d1d88010ff98996913ed024dde8ebc860984f7806e5619c88cabf2ef06"
      )
      val responseBody = createRPCSuccessfulResponse(content)
      val json = Json.parse(responseBody)

      mockRequest(request, response)(200, json)

      val tposAddress = Address.from("XpLy7iJebcUbpmsH1PAiHRn8BrrMdw73KV").get
      val merchantAddress =
        Address.from("XqzYHcK3STW5F22S7kep7dMU4sx3SKFMBv").get
      val merchantCommission = 10
      val signature =
        "201F2D052FB372248F89F9F2C9106BE9A670D5538C01E4F39215C92717B847D3EA2466E7D1D88010FF98996913ED024DDE8EBC860984F7806E5619C88CABF2EF06"

      whenReady(
        service.encodeTPOSContract(
          tposAddress,
          merchantAddress,
          merchantCommission,
          signature
        )
      ) { result =>
        result.isGood mustEqual true
        result.get mustEqual "020000000a001976a91495cf859d7a40c5d7fded2a03cb8d7dcf307eab1188ac1976a914a7e2ba4e0d91273d686f446fa04ca5fe800d452d88ac41201f2d052fb372248f89f9f2c9106be9a670d5538c01e4f39215c92717b847d3ea2466e7d1d88010ff98996913ed024dde8ebc860984f7806e5619c88cabf2ef06"
      }
    }
  }

  "getTPoSContractDetails" should {
    "return the tpos contract details" in {
      val content =
        """
          |{
          |    "tposAddress": "XpLy7iJebcUbpmsH1PAiHRn8BrrMdw73KV",
          |    "merchantAddress": "XqzYHcK3STW5F22S7kep7dMU4sx3SKFMBv",
          |    "commission": 10
          |}
          |""".stripMargin

      val responseBody = createRPCSuccessfulResponse(Json.parse(content))
      val json = Json.parse(responseBody)
      mockRequest(request, response)(200, json)
      val tposAddress = Address.from("XpLy7iJebcUbpmsH1PAiHRn8BrrMdw73KV").get
      val merchantAddress =
        Address.from("XqzYHcK3STW5F22S7kep7dMU4sx3SKFMBv").get
      val merchantCommission = Commission.from(10).get
      val txid = TransactionId
        .from(
          "c3efb8b60bda863a3a963d340901dc2b870e6ea51a34276a8f306d47ffb94f01"
        )
        .get
      val expected =
        TPoSContract.Details(tposAddress, merchantAddress, merchantCommission)

      whenReady(service.getTPoSContractDetails(txid)) { result =>
        result.isGood mustEqual true
        result.get mustEqual expected
      }
    }
  }

  private def mockRequest(
      request: WSRequest,
      response: WSResponse
  )(status: Int, body: JsValue) = {
    when(response.status).thenReturn(status)
    when(response.json).thenReturn(body)
    when(response.body).thenReturn(body.toString())
    when(request.post[AnyRef](any())(any()))
      .thenReturn(Future.successful(response))
  }

  private def mockRequestString(
      request: WSRequest,
      response: WSResponse
  )(status: Int, body: String) = {
    when(response.status).thenReturn(status)
    when(response.body).thenReturn(body)
    when(request.post[String](anyString())(any()))
      .thenReturn(Future.successful(response))
  }
}
