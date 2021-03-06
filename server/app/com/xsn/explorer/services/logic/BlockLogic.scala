package com.xsn.explorer.services.logic

import com.alexitc.playsonify.core.ApplicationResult
import com.xsn.explorer.errors.{BlockNotFoundError, TransactionError}
import com.xsn.explorer.models._
import com.xsn.explorer.models.rpc.{Block, Transaction, TransactionVIN}
import com.xsn.explorer.models.values.{Address, TransactionId}
import org.scalactic.{Bad, Good, One, Or}

class BlockLogic {

  def getCoinbase[Tx](block: Block[Tx]): ApplicationResult[Tx] = {
    val maybe = block.transactions.headOption

    Or.from(maybe, One(TransactionError.CoinbaseNotFound(block.hash)))
  }

  /** Get the coinstake transaction id for the given block.
    *
    * A PoS block contains at least 2 transactions:
    *   - the 1st one is empty
    *   - the 2nd one is the Coinstake transaction.
    */
  def getCoinstakeTransaction[Tx](block: Block[Tx]): ApplicationResult[Tx] = {
    val maybe = block.transactions.lift(1)

    Or.from(maybe, One(BlockNotFoundError))
  }

  def getTPoSTransactionId(
      block: Block[_]
  ): ApplicationResult[TransactionId] = {
    val maybe = block.tposContract

    Or.from(maybe, One(BlockNotFoundError))
  }

  def getTPoSContractDetails(
      tposContract: Transaction[_]
  ): ApplicationResult[TPoSContract.Details] = {
    val maybe = tposContract.vout
      .flatMap(_.scriptPubKey)
      .flatMap(_.getTPoSContractDetails)
      .headOption

    Or.from(maybe, One(BlockNotFoundError))
  }

  /** Computes the rewards for a PoS coinstake transaction.
    *
    * There should be a coinstake reward and possibly a master node reward.
    *
    * The rewards are computed based on the transaction output which is expected to contain between 2 and 4 values:
    *   - the 1st one is empty
    *   - the 2nd one goes to the coinstake
    *   - the 3rd one (if present) will go to the coinstake if the address matches, otherwise it goes to master node.
    *   - the 4th one (if present) will go to the master node.
    *
    * While the previous format should be meet by the RPC server, we compute the rewards based on coinstake address.
    *
    * Sometimes there could be rounding errors, for example, when the input is not exactly divisible by 2, we return 0
    * in that case because the reward could be negative.
    */
  def getPoSRewards(
      coinstakeTx: Transaction[_],
      coinstakeAddress: Address,
      stakedTx: Transaction[TransactionVIN],
      coinstakeInput: BigDecimal,
      totalInput: BigDecimal
  ): ApplicationResult[PoSBlockRewards] = {

    // first vout is empty, useless
    val coinstakeVOUT = coinstakeTx.vout.drop(1)
    if (coinstakeVOUT.nonEmpty && coinstakeVOUT.size <= 3) {
      val value = coinstakeVOUT
        .filter(_.addresses.getOrElse(List.empty) contains coinstakeAddress)
        .map(_.value)
        .sum

      val coinstakeReward =
        BlockReward(coinstakeAddress, (value - totalInput) max 0)

      val masternodeRewardOUT = coinstakeVOUT.filterNot(
        _.addresses.getOrElse(List.empty) contains coinstakeAddress
      )
      val remainingRewardAddressMaybe =
        masternodeRewardOUT.flatMap(_.addresses).flatten.headOption
      val remainingRewardMaybe = remainingRewardAddressMaybe.map { remainingRewardAddress =>
        BlockReward(
          remainingRewardAddress,
          masternodeRewardOUT
            .filter(
              _.addresses.getOrElse(
                List.empty
              ) contains remainingRewardAddress
            )
            .map(_.value)
            .sum
        )
      }

      val (masternodeReward, treasuryReward) = remainingRewardMaybe match {
        case Some(reward) if isTreasuryReward(reward.value) =>
          (None, Some(reward))
        case Some(reward) => (Some(reward), None)
        case None => (None, None)
      }

      Good(
        PoSBlockRewards(
          coinstakeReward,
          masternodeReward,
          treasuryReward,
          coinstakeInput,
          coinstakeTx.time - stakedTx.time
        )
      )
    } else {
      Bad(BlockNotFoundError).accumulating
    }
  }

  def getTPoSRewards(
      coinstakeTx: Transaction[_],
      contract: TPoSContract.Details,
      stakedTx: Transaction[TransactionVIN],
      coinstakeInput: BigDecimal,
      totalInput: BigDecimal
  ): ApplicationResult[TPoSBlockRewards] = {

    /** While we expected the following
      *   - 1st output is empty and it is removed.
      *   - 3 outputs, normal TPoS
      *   - 4 outputs, coin split TPoS
      *
      * In order to display partial solutions, we will just filter by the addresses to get the rewards.
      */
    val coinstakeVOUT = coinstakeTx.vout

    val ownerValue = coinstakeVOUT
      .filter(_.addresses.getOrElse(List.empty) contains contract.owner)
      .map(_.value)
      .sum

    val ownerReward =
      BlockReward(contract.owner, (ownerValue - totalInput) max 0)

    // merchant
    val merchantValue =
      coinstakeVOUT
        .filter(_.addresses.getOrElse(List.empty) contains contract.merchant)
        .map(_.value)
        .sum
    val merchantReward = BlockReward(contract.merchant, merchantValue)

    // master node
    val masternodeRewardOUT = coinstakeVOUT.filterNot { out =>
      out.addresses.getOrElse(List.empty).contains(contract.owner) ||
      out.addresses.getOrElse(List.empty).contains(contract.merchant)
    }
    val remainingRewardAddressMaybe =
      masternodeRewardOUT.flatMap(_.addresses.getOrElse(List.empty)).headOption
    val remainingRewardMaybe = remainingRewardAddressMaybe.map { remainingRewardAddress =>
      BlockReward(
        remainingRewardAddress,
        masternodeRewardOUT
          .filter(
            _.addresses.getOrElse(List.empty) contains remainingRewardAddress
          )
          .map(_.value)
          .sum
      )
    }

    val (masternodeReward, treasuryReward) = remainingRewardMaybe match {
      case Some(reward) if isTreasuryReward(reward.value) =>
        (None, Some(reward))
      case Some(reward) => (Some(reward), None)
      case None => (None, None)
    }

    Good(
      TPoSBlockRewards(
        ownerReward,
        merchantReward,
        masternodeReward,
        treasuryReward,
        coinstakeInput,
        coinstakeTx.time - stakedTx.time
      )
    )
  }

  def isPoS(block: rpc.Block[_], coinbase: rpc.Transaction[_]): Boolean = {
    block.nonce == 0 &&
    coinbase.vin.isEmpty &&
    coinbase.vout.flatMap(_.addresses.getOrElse(List.empty)).isEmpty
  }

  // treasury gets 10% of all rewards and is paid every 43200 blocks but there is one special transactions when
  // it was paid outside this 43200 blocks cycle so we will use the reward amount to detect if its a treasury
  // payment since no other reward should be as high
  private def isTreasuryReward(amount: BigDecimal): Boolean =
    amount > BigDecimal(100)
}
