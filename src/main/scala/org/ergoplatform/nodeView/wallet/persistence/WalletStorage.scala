package org.ergoplatform.nodeView.wallet.persistence

import com.google.common.primitives.Ints
import io.iohk.iodb.{ByteArrayWrapper, Store}
import org.ergoplatform.nodeView.history.ErgoHistory
import org.ergoplatform.nodeView.state.{ErgoStateContext, ErgoStateContextSerializer}
import org.ergoplatform.settings.ErgoSettings
import org.ergoplatform.{ErgoAddress, ErgoAddressEncoder}
import scorex.crypto.authds.ADDigest
import scorex.crypto.hash.Blake2b256

import scala.util.Random

/**
  * Persists wallet actor's mutable state.
  */
final class WalletStorage(store: Store, settings: ErgoSettings)
                         (implicit val addressEncoder: ErgoAddressEncoder) {

  import WalletStorage._

  def addTrackedAddresses(addresses: Seq[ErgoAddress]): Unit = {
    val updatedKeys = (readTrackedAddresses ++ addresses).toSet
    val toInsert = Ints.toByteArray(updatedKeys.size) ++ updatedKeys
      .foldLeft(Array.empty[Byte]) { case (acc, address) =>
        val bytes = addressEncoder.toString(address).getBytes("UTF-8")
        acc ++ Ints.toByteArray(bytes.length) ++ bytes
      }
    store.update(randomVersion, Seq.empty, Seq(TrackedAddressesKey -> ByteArrayWrapper(toInsert)))
  }

  def addTrackedAddress(address: ErgoAddress): Unit = addTrackedAddresses(Seq(address))

  def readTrackedAddresses: Seq[ErgoAddress] = store
    .get(TrackedAddressesKey)
    .toSeq
    .flatMap { r =>
      val qty = Ints.fromByteArray(r.data.take(4))
      (0 until qty).foldLeft(Seq.empty[ErgoAddress], r.data.drop(4)) { case ((acc, bytes), _) =>
        val length = Ints.fromByteArray(bytes.take(4))
        val addressTry = addressEncoder.fromString(new String(bytes.slice(4, 4 + length), "UTF-8"))
        addressTry.map(acc :+ _).getOrElse(acc) -> bytes.drop(4 + length)
      }._1
    }

  def updateStateContext(ctx: ErgoStateContext): Unit = store
    .update(randomVersion, Seq.empty, Seq(StateContextKey -> ByteArrayWrapper(ctx.bytes)))

  def readStateContext: ErgoStateContext = store
    .get(StateContextKey)
    .flatMap(r => ErgoStateContextSerializer(settings.chainSettings.voting).parseBytesTry(r.data).toOption)
    .getOrElse(ErgoStateContext.empty(ADDigest @@ Array.fill(32)(0: Byte), settings))

  def updateHeight(height: Int): Unit = store
    .update(randomVersion, Seq.empty, Seq(HeightKey -> ByteArrayWrapper(Ints.toByteArray(height))))

  def readHeight: Int = store
    .get(HeightKey)
    .map(r => Ints.fromByteArray(r.data))
    .getOrElse(ErgoHistory.EmptyHistoryHeight)

  private def randomVersion = Random.nextInt()

}

object WalletStorage {

  val PubKeyLength: Int = 32

  val StateContextKey: ByteArrayWrapper =
    ByteArrayWrapper(Blake2b256.hash("state_ctx"))

  val HeightKey: ByteArrayWrapper =
    ByteArrayWrapper(Blake2b256.hash("height"))

  val TrackedAddressesKey: ByteArrayWrapper =
    ByteArrayWrapper(Blake2b256.hash("tracked_pks"))

}
