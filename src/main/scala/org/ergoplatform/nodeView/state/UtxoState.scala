package org.ergoplatform.nodeView.state

import java.io.File

import io.iohk.iodb.{ByteArrayWrapper, LSMStore, Store}
import org.ergoplatform.ErgoBox
import org.ergoplatform.ErgoLikeContext.Height
import org.ergoplatform.modifiers.history.{ADProofs, Header}
import org.ergoplatform.modifiers.mempool.ErgoTransaction
import org.ergoplatform.modifiers.{ErgoFullBlock, ErgoPersistentModifier}
import org.ergoplatform.settings.Algos.HF
import org.ergoplatform.settings.{Algos, Constants}
import scorex.core.NodeViewHolder.ReceivableMessages.LocallyGeneratedModifier
import scorex.core.VersionTag
import scorex.core.transaction.state.TransactionValidation
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.authds.{ADDigest, ADValue}
import scorex.crypto.hash.Digest32

import scala.util.{Failure, Success, Try}

/**
  * Utxo set implementation
  *
  * @param persistentProver - persistent prover that build authenticated AVL+ tree on top of utxo set
  * @param store - storage of persistentProver that also keeps metadata
  * @param version - current state version
  * @param constants - constants, that do not change with state version changes
  */
class UtxoState(persistentProver: PersistentBatchAVLProver[Digest32, HF],
                override val version: VersionTag,
                override val store: Store,
                override val constants: StateConstants)
  extends ErgoState[UtxoState]
    with TransactionValidation[ErgoTransaction]
    with UtxoStateReader {

  override lazy val rootHash: ADDigest = persistentProver.digest

  private def onAdProofGenerated(proof: ADProofs): Unit = {
    if (constants.nodeViewHolderRef.isEmpty) log.warn("Got proof while nodeViewHolderRef is empty")
    constants.nodeViewHolderRef.foreach(h => h ! LocallyGeneratedModifier(proof))
  }

  import UtxoState.metadata

  override val maxRollbackDepth = 10

  override def rollbackTo(version: VersionTag): Try[UtxoState] = {
    val p = persistentProver
    log.info(s"Rollback UtxoState to version ${Algos.encoder.encode(version)}")
    store.get(ByteArrayWrapper(version)) match {
      case Some(hash) =>
        val rootHash: ADDigest = ADDigest @@ hash.data
        val rollbackResult = p.rollback(rootHash).map { _ =>
          new UtxoState(p, version, store, constants) {
            override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, HF] = p
          }
        }
        store.clean(constants.keepVersions)
        rollbackResult
      case None =>
        Failure(new Error(s"Unable to get root hash at version ${Algos.encoder.encode(version)}"))
    }
  }

  @SuppressWarnings(Array("TryGet"))
  private[state] def applyTransactions(transactions: Seq[ErgoTransaction],
                                       expectedDigest: ADDigest,
                                       height: Height) = Try {

    val createdOutputs = transactions.flatMap(_.outputs).map(o => (ByteArrayWrapper(o.id), o)).toMap
    val totalCost = transactions.map { tx =>
      tx.statelessValidity.get
      val boxesToSpend = tx.inputs.map(_.boxId).map { id =>
        createdOutputs.get(ByteArrayWrapper(id)).orElse(boxById(id)) match {
          case Some(box) => box
          case None => throw new Error(s"Box with id ${Algos.encode(id)} not found")
        }
      }
      tx.statefulValidity(boxesToSpend, stateContext).get
    }.sum

    if (totalCost > Constants.MaxBlockCost) throw new Error(s"Transaction cost $totalCost exeeds limit")

    val mods = ErgoState.stateChanges(transactions).operations.map(ADProofs.changeToMod)
    mods.foldLeft[Try[Option[ADValue]]](Success(None)) { case (t, m) =>
      t.flatMap(_ => {
        persistentProver.performOneOperation(m)
      })
    }.get

    if (!expectedDigest.sameElements(persistentProver.digest)) {
      throw new Error(s"Digest after txs application is wrong. ${Algos.encode(expectedDigest)} expected, " +
        s"${Algos.encode(persistentProver.digest)} given")
    }
  }

  //todo: utxo snapshot could go here
  override def applyModifier(mod: ErgoPersistentModifier): Try[UtxoState] = mod match {
    case fb: ErgoFullBlock =>
      val height = fb.header.height

      log.debug(s"Trying to apply full block with header ${fb.header.encodedId} at height $height")
      val inRoot = rootHash

      val stateTry: Try[UtxoState] = applyTransactions(fb.blockTransactions.txs, fb.header.stateRoot, height).map { _: Unit =>
        val emissionBox = extractEmissionBox(fb)
        val newStateContext = stateContext.appendHeader(fb.header)
        val md = metadata(VersionTag @@ fb.id, fb.header.stateRoot, emissionBox, newStateContext)

        val proofBytes = persistentProver.generateProofAndUpdateStorage(md)
        val proofHash = ADProofs.proofDigest(proofBytes)
        if (fb.aDProofs.isEmpty) onAdProofGenerated(ADProofs(fb.header.id, proofBytes))

        if (!store.get(ByteArrayWrapper(fb.id)).exists(_.data sameElements fb.header.stateRoot)) {
          throw new Error("Storage kept roothash is not equal to the declared one")
        } else if (!(fb.header.ADProofsRoot sameElements proofHash)) {
          throw new Error("Calculated proofHash is not equal to the declared one")
        } else if (!(fb.header.stateRoot sameElements persistentProver.digest)) {
          throw new Error("Calculated stateRoot is not equal to the declared one")
        }

        log.info(s"Valid modifier with header ${fb.header.encodedId} and emission box " +
          s"${emissionBox.map(e => Algos.encode(e.id))} applied to UtxoState with root hash ${Algos.encode(inRoot)}")
        new UtxoState(persistentProver, VersionTag @@ fb.id, store, constants)
      }
      stateTry.recoverWith[UtxoState] { case e =>
        log.warn(s"Error while applying full block with header ${fb.header.encodedId} to UTXOState with root" +
          s" ${Algos.encode(inRoot)}: ", e)
        persistentProver.rollback(inRoot).ensuring(persistentProver.digest.sameElements(inRoot))
        Failure(e)
      }

    case h: Header =>
      Success(new UtxoState(persistentProver, VersionTag @@ h.id, this.store, constants))

    case a: Any =>
      log.info(s"Unhandled modifier: $a")
      Failure(new Exception("unknown modifier"))
  }

  @SuppressWarnings(Array("OptionGet"))
  override def rollbackVersions: Iterable[VersionTag] = persistentProver.storage.rollbackVersions.map { v =>
    VersionTag @@ store.get(ByteArrayWrapper(Algos.hash(v))).get.data
  }

}

object UtxoState {

  def apply(version: VersionTag, store: Store, constants: StateConstants): UtxoState = {
    val persistentProver: PersistentBatchAVLProver[Digest32, HF] = {
      val bp = new BatchAVLProver[Digest32, HF](keyLength = 32, valueLengthOpt = None)
      val np = NodeParameters(keySize = 32, valueSize = None, labelSize = 32)
      val storage: VersionedIODBAVLStorage[Digest32] = new VersionedIODBAVLStorage(store, np)(Algos.hash)
      PersistentBatchAVLProver.create(bp, storage).get
    }
    new UtxoState(persistentProver, version: VersionTag, store: Store, constants: StateConstants)

  }

  private lazy val bestVersionKey = Algos.hash("best state version")
  val EmissionBoxIdKey = Algos.hash("emission box id key")

  private def metadata(modId: VersionTag,
                       stateRoot: ADDigest,
                       currentEmissionBoxOpt: Option[ErgoBox],
                       context: ErgoStateContext): Seq[(Array[Byte], Array[Byte])] = {
    val idStateDigestIdxElem: (Array[Byte], Array[Byte]) = modId -> stateRoot
    val stateDigestIdIdxElem = Algos.hash(stateRoot) -> modId
    val bestVersion = bestVersionKey -> modId
    val eb = EmissionBoxIdKey -> currentEmissionBoxOpt.map(emissionBox => emissionBox.id).getOrElse(Array[Byte]())
    val cb = ErgoStateReader.ContextKey -> context.bytes

    Seq(idStateDigestIdxElem, stateDigestIdIdxElem, bestVersion, eb, cb)
  }

  def create(dir: File, constants: StateConstants, genesisDigest: ADDigest): UtxoState = {
    val store = new LSMStore(dir, keepVersions = constants.keepVersions)
    val dbVersion = store.get(ByteArrayWrapper(bestVersionKey)).map(VersionTag @@ _.data)
      .getOrElse(ErgoState.genesisStateVersion)
    UtxoState(dbVersion, store, constants)
  }

  @SuppressWarnings(Array("OptionGet", "TryGet"))
  def fromBoxHolder(bh: BoxHolder,
                    currentEmissionBoxOpt: Option[ErgoBox],
                    dir: File,
                    constants: StateConstants): UtxoState = {
    val p = new BatchAVLProver[Digest32, HF](keyLength = 32, valueLengthOpt = None)
    bh.sortedBoxes.foreach(b => p.performOneOperation(Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))

    val store = new LSMStore(dir, keepVersions = constants.keepVersions)
    val defaultStateContext = ErgoStateContext(0, p.digest)
    val np = NodeParameters(keySize = 32, valueSize = None, labelSize = 32)
    val storage: VersionedIODBAVLStorage[Digest32] = new VersionedIODBAVLStorage(store, np)(Algos.hash)
    val persistentProver = PersistentBatchAVLProver.create(
      p,
      storage,
      metadata(ErgoState.genesisStateVersion, p.digest, currentEmissionBoxOpt, defaultStateContext),
      paranoidChecks = true
    ).get

    new UtxoState(persistentProver, ErgoState.genesisStateVersion, store, constants)
  }
}

