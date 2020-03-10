// VeriBlock Blockchain Project
// Copyright 2017-2018 VeriBlock, Inc
// Copyright 2018-2019 Xenios SEZC
// All rights reserved.
// https://www.veriblock.org
// Distributed under the MIT software license, see the accompanying
// file LICENSE or http://www.opensource.org/licenses/mit-license.php.

package org.veriblock.miners.pop.tasks

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import org.veriblock.core.altchain.checkForValidEndorsement
import org.veriblock.core.utilities.Utility
import org.veriblock.core.utilities.createLogger
import org.veriblock.core.utilities.extensions.toHex
import org.veriblock.lite.NodeCoreLiteKit
import org.veriblock.lite.core.TransactionMeta
import org.veriblock.miners.pop.Miner
import org.veriblock.miners.pop.core.MiningOperation
import org.veriblock.miners.pop.core.OperationState
import org.veriblock.miners.pop.core.OperationStateType
import org.veriblock.miners.pop.core.info
import org.veriblock.miners.pop.core.warn
import org.veriblock.miners.pop.securityinheriting.SecurityInheritingMonitor
import org.veriblock.miners.pop.util.VTBDebugUtility
import org.veriblock.sdk.alt.SecurityInheritingChain
import org.veriblock.sdk.alt.model.SecurityInheritingBlock
import org.veriblock.sdk.alt.model.SecurityInheritingTransaction
import org.veriblock.sdk.models.AltPublication
import org.veriblock.sdk.models.BlockStoreException
import org.veriblock.sdk.models.Sha256Hash
import org.veriblock.sdk.services.SerializeDeserializeService
import org.veriblock.sdk.util.Utils

private val logger = createLogger {}

suspend fun runTasks(
    miner: Miner,
    nodeCoreLiteKit: NodeCoreLiteKit,
    securityInheritingChain: SecurityInheritingChain,
    securityInheritingMonitor: SecurityInheritingMonitor,
    operation: MiningOperation
) {
    if (operation.state is OperationState.Failed) {
        logger.warn(operation) { "Attempted to run tasks for a failed operation!" }
        return
    }

    val chainSymbol = securityInheritingChain.key.toUpperCase()

    try {
        operation.runTask("Retrieve Mining Instruction from ${securityInheritingChain.name}", OperationStateType.INSTRUCTION) {
            logger.info(operation) { "Getting the mining instruction..." }
            try {
                val publicationData = securityInheritingChain.getMiningInstruction(operation.blockHeight)
                operation.setMiningInstruction(publicationData)
            } catch (e: Exception) {
                error("Error while trying to get PoP Mining Instruction from ${securityInheritingChain.name}: ${e.message}")
            }
            logger.info(operation) { "Successfully added the mining instruction!" }
        }
        operation.runTask("Create Proof Transaction", OperationStateType.ENDORSEMEMT_TRANSACTION) {
            val state = operation.state
            if (state !is OperationState.Instruction) {
                failTask("CreateProofTransactionTask called without mining instruction!")
            }

            // Something to fill in all the gaps
            logger.info(operation) { "Submitting endorsement VBK transaction..." }
            val transaction = try {
                val endorsementData = SerializeDeserializeService.serialize(state.miningInstruction.publicationData)
                endorsementData.checkForValidEndorsement {
                    logger.error(it) { "Invalid endorsement data" }
                    error("Invalid endorsement data: ${endorsementData.toHex()}")
                }
                nodeCoreLiteKit.network.submitEndorsement(
                    endorsementData,
                    miner.feePerByte,
                    miner.maxFee
                )
            } catch (e: Exception) {
                error("Could not create endorsement VBK transaction: ${e.message}")
            }

            val walletTransaction = nodeCoreLiteKit.transactionMonitor.getTransaction(transaction.id)
            operation.setTransaction(walletTransaction)
            logger.info(operation) { "Successfully added the VBK transaction: ${walletTransaction.id}!" }
        }
        operation.runTask("Confirm transaction", OperationStateType.CONFIRMED) {
            val state = operation.state
            if (state !is OperationState.EndorsementTransaction) {
                failTask("ConfirmTransactionTask called without wallet transaction!")
            }
            logger.info(operation) { "Waiting for the transaction to be included in VeriBlock block..." }
            // We will wait for the transaction to be confirmed, which will trigger DetermineBlockOfProofTask
            val txMetaChannel = state.transaction.transactionMeta.stateChangedBroadcastChannel.openSubscription()
            txMetaChannel.receive() // Skip first state change (PENDING)
            do {
                val metaState = txMetaChannel.receive()
                if (metaState === TransactionMeta.MetaState.PENDING) {
                    error("The VeriBlock chain has reorganized")
                }
            } while (metaState !== TransactionMeta.MetaState.CONFIRMED)
            txMetaChannel.cancel()

            // Transaction has been confirmed!
            operation.setConfirmed()
        }
        operation.runTask("Determine Block of Proof", OperationStateType.BLOCK_OF_PROOF) {
            val state = operation.state
            val transaction = (state as? OperationState.EndorsementTransaction)?.transaction
                ?: failTask("The operation has no transaction set!")

            val blockHash = transaction.transactionMeta.appearsInBestChainBlock
                ?: failTask("Unable to retrieve block of proof from transaction")

            try {
                val block = nodeCoreLiteKit.blockChain.get(blockHash)
                    ?: failTask("Unable to retrieve VBK block $blockHash")
                operation.setBlockOfProof(block)
            } catch (e: BlockStoreException) {
                failTask("Error when retrieving VBK block $blockHash")
            }
            logger.info(operation) { "Successfully added the VBK block of proof!" }
        }
        operation.runTask("Prove Transaction", OperationStateType.TRANSACTION_PROVED) {
            val state = operation.state
            if (state !is OperationState.BlockOfProof) {
                failTask("ProveTransactionTask called without VBK block of proof!")
            }
            val walletTransaction = state.transaction

            logger.info(operation) { "Getting the merkle path for the transaction: ${walletTransaction.id}..." }
            val merklePath = walletTransaction.merklePath
                ?: error("No merkle path found for ${walletTransaction.id}")
            logger.info(operation) { "Successfully retrieved the merkle path for the transaction: ${walletTransaction.id}!" }

            val vbkMerkleRoot = merklePath.merkleRoot.trim(Sha256Hash.VERIBLOCK_MERKLE_ROOT_LENGTH)
            val verified = vbkMerkleRoot == state.blockOfProof.merkleRoot
            if (!verified) {
                error(
                    "Unable to verify merkle path! VBK Transaction's merkle root: $vbkMerkleRoot;" +
                        " Block of proof's merkle root: ${state.blockOfProof.merkleRoot}"
                )
            }

            operation.setMerklePath(merklePath)
            logger.info(operation) { "Successfully added the verified merkle path!" }
        }
        operation.runTask("Wait for next VeriBlock Keystone", OperationStateType.KEYSTONE_OF_PROOF) {
            val state = operation.state
            if (state !is OperationState.BlockOfProof) {
                failTask("RegisterKeystoneListenersTask called without block of proof!")
            }

            val blockOfProof = state.blockOfProof

            logger.info(operation) { "Waiting for the next VBK Keystone..." }
            val keystoneOfProof = nodeCoreLiteKit.blockChain.newBestBlockChannel.asFlow().first {
                if (it.height > blockOfProof.height + 20) {
                    error("The next VBK Keystone was not received!")
                }
                it.height == blockOfProof.height / 20 * 20 + 20
            }
            operation.setKeystoneOfProof(keystoneOfProof)
        }
        operation.runTask("Wait for VeriBlock Publication Data", OperationStateType.VERIBLOCK_PUBLICATIONS) {
            val state = operation.state
            if (state !is OperationState.KeystoneOfProof) {
                failTask("RegisterVeriBlockPublicationPollingTask called without keystone of proof!")
            }
            // We will be waiting for this operation's veriblock publication, which will trigger the SubmitProofOfProofTask
            val publications = nodeCoreLiteKit.network.getVeriBlockPublications(
                operation.id, state.keystoneOfProof.hash.toString(),
                Utils.encodeHex(state.miningInstruction.context[0]),
                Utils.encodeHex(state.miningInstruction.btcContext[0])
            )
            operation.setVeriBlockPublications(publications)
        }
        operation.runTask("Submit Proof of Proof", OperationStateType.SUBMITTED_POP_DATA) {
            val state = operation.state
            if (state !is OperationState.VeriBlockPublications) {
                failTask("SubmitProofOfProofTask called without VeriBlock publications!")
            }
            try {
                val proofOfProof = AltPublication(
                    state.transaction,
                    state.merklePath,
                    state.blockOfProof,
                    emptyList()
                )

                val veriBlockPublications = state.veriBlockPublications

                try {
                    val context = state.miningInstruction
                    val btcContext = context.btcContext
                    // List<byte[]> vbkContext = context.getContext();

                    // Check that the first VTB connects somewhere in the BTC context
                    val firstPublication = veriBlockPublications[0]

                    val serializedAltchainBTCContext = btcContext.joinToString("\n") { Utility.bytesToHex(it) }

                    val serializedBTCHashesInPoPTransaction = VTBDebugUtility.serializeBitcoinBlockHashList(
                        VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(
                            firstPublication.transaction
                        )
                    )

                    if (!VTBDebugUtility.vtbConnectsToBtcContext(btcContext, firstPublication)) {
                        logger.error {
                            """Error: the first VeriBlock Publication with PoP TxID ${firstPublication.transaction.id} does not connect to the altchain context!
                           Altchain Bitcoin Context:
                           $serializedAltchainBTCContext
                           PoP Transaction Bitcoin blocks: $serializedBTCHashesInPoPTransaction""".trimIndent()
                        }
                    } else {
                        logger.debug {
                            """Success: the first VeriBlock Publication with PoP TxID ${firstPublication.transaction.id} connects to the altchain context!
                           Altchain Bitcoin Context:
                           $serializedAltchainBTCContext
                           PoP Transaction Bitcoin blocks: $serializedBTCHashesInPoPTransaction""".trimIndent()
                        }
                    }

                    // Check that every VTB connects to the previous one
                    for (i in 1 until veriBlockPublications.size) {
                        val anchor = veriBlockPublications[i - 1]
                        val toConnect = veriBlockPublications[i]

                        val anchorBTCBlocks = VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(anchor.transaction)
                        val toConnectBTCBlocks = VTBDebugUtility.extractOrderedBtcBlocksFromPopTransaction(toConnect.transaction)

                        val serializedAnchorBTCBlocks = VTBDebugUtility.serializeBitcoinBlockHashList(anchorBTCBlocks)
                        val serializedToConnectBTCBlocks = VTBDebugUtility.serializeBitcoinBlockHashList(toConnectBTCBlocks)

                        if (!VTBDebugUtility.doVtbsConnect(anchor, toConnect)) {
                            logger.error {
                                """Error: VTB at index $i does not connect to the previous VTB!
                               VTB #${i - 1} BTC blocks:
                               $serializedAnchorBTCBlocks
                               VTB #$i BTC blocks:
                               $serializedToConnectBTCBlocks""".trimIndent()
                            }
                        } else {
                            logger.debug { "Success, VTB at index $i connects to VTB at index ${i - 1}!" }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("An error occurred checking VTB connection and continuity!", e)
                }

                val siTxId = securityInheritingChain.submit(proofOfProof, veriBlockPublications)

                logger.info(operation) { "VTB submitted to $chainSymbol! $chainSymbol PoP TxId: $siTxId" }

                operation.setProofOfProofId(siTxId)
            } catch (e: Exception) {
                logger.error("Error submitting proof of proof", e)
                failTask("Error submitting proof of proof")
            }
        }
        operation.runTask("Altchain Endorsement Transaction Confirmation", OperationStateType.ALT_ENDORSEMENT_TRANSACTION_CONFIRMED) {
            val state = operation.state
            if (state !is OperationState.SubmittedPopData) {
                failTask("AltEndorsementTransactionConfirmationTask called without proof of proof txId!")
            }

            val endorsementTransactionId = state.proofOfProofId
            logger.info(operation) { "Waiting for $chainSymbol Endorsement Transaction ($endorsementTransactionId) to be confirmed..." }
            val endorsementTransaction = securityInheritingMonitor.getTransaction(endorsementTransactionId) { transaction ->
                if (transaction.confirmations < 0) {
                    throw AltchainTransactionReorgException(transaction)
                }
                transaction.confirmations >= securityInheritingChain.config.neededConfirmations
            }
            logger.info(operation) {
                "Successfully confirmed $chainSymbol endorsement transaction ${endorsementTransaction.txId}!" +
                    " Confirmations: ${endorsementTransaction.confirmations}"
            }
            operation.setAltEndorsementTransactionConfirmed()
        }
        operation.runTask("Altchain Endorsed Block Confirmation", OperationStateType.ALT_ENDORSED_BLOCK_CONFIRMED) {
            val state = operation.state
            if (state !is OperationState.AltEndorsementTransactionConfirmed) {
                failTask("AltEndorsedBlockConfirmationTask called without having confirmed the transaction!")
            }

            val endorsedBlockHeight = state.miningInstruction.endorsedBlockHeight
            logger.info(operation) { "Waiting for $chainSymbol endorsed block ($endorsedBlockHeight) to be confirmed..." }
            val endorsedBlock = securityInheritingMonitor.getBlockAtHeight(endorsedBlockHeight) { block ->
                block.confirmations >= securityInheritingChain.config.neededConfirmations
            }

            val endorsedBlockHeader = state.miningInstruction.publicationData.header
            val belongsToMainChain = securityInheritingChain.checkBlockIsOnMainChain(endorsedBlockHeight, endorsedBlockHeader)
            if (!belongsToMainChain) {
                error(
                    "Endorsed block header ${endorsedBlockHeader.toHex()} @ $endorsedBlockHeight" +
                        " is not in ${operation.chainId.toUpperCase()}'s main chain"
                )
            }

            logger.info(operation) { "Successfully confirmed $chainSymbol endorsed block ${endorsedBlock.hash}!" }
            operation.setAltEndorsedBlockHash(endorsedBlock.hash)
        }
        operation.runTask("Payout Detection", OperationStateType.COMPLETE) {
            val state = operation.state
            if (state !is OperationState.SubmittedPopData) {
                failTask("PayoutDetectionTask called without having confirmed the endorsed block!")
            }

            val endorsedBlockHeight = state.miningInstruction.endorsedBlockHeight
            val payoutBlockHeight = endorsedBlockHeight + securityInheritingChain.getPayoutInterval()
            logger.info(operation) { "Waiting for $chainSymbol payout block ($payoutBlockHeight) to be confirmed..." }
            val payoutBlock = securityInheritingMonitor.getBlockAtHeight(payoutBlockHeight) { block ->
                if (block.confirmations < 0) {
                    throw AltchainBlockReorgException(block)
                }
                block.confirmations >= securityInheritingChain.config.neededConfirmations
            }
            val coinbaseTransaction = securityInheritingChain.getTransaction(payoutBlock.coinbaseTransactionId)
                ?: failTask("Unable to find transaction ${payoutBlock.coinbaseTransactionId}")
            val rewardVout = coinbaseTransaction.vout.find {
                it.addressHash == state.miningInstruction.publicationData.payoutInfo.toHex()
            }
            if (rewardVout != null) {
                logger.info(operation) {
                    "${operation.chainId.toUpperCase()} PoP Payout detected! Amount: ${rewardVout.value} ${operation.chainId.toUpperCase()}"
                }
                operation.complete(payoutBlock.hash, rewardVout.value)
            } else {
                error(
                    "Unable to find ${operation.chainId.toUpperCase()} PoP payout transaction in the expected block's coinbase!" +
                        " Expected payout block: ${payoutBlock.hash} @ ${payoutBlock.height}"
                )
            }
        }
    } catch (t: Throwable) {
        operation.fail(t.message ?: "Unknown reason")
    }
}

private const val MAX_TASK_RETRIES = 10

private suspend inline fun MiningOperation.runTask(
    taskName: String,
    targetStateType: OperationStateType,
    block: () -> Unit
) {
    // Check if this operation needs to run this task first
    if (state hasType targetStateType) {
        return
    }

    var success = false
    var attempts = 1
    do {
        try {
            block()
            success = true
        } catch (e: TaskException) {
            logger.warn(this) { "Task '$taskName' has failed: ${e.message}" }
            if (attempts < MAX_TASK_RETRIES) {
                attempts++
                val secondsToWait = attempts * attempts * 10
                logger.info(this) { "Will try again in $secondsToWait seconds..." }
                delay(secondsToWait * 1000L)
                logger.info(this) { "Performing attempt #$attempts..." }
            } else {
                logger.warn(this) { "Maximum reattempt amount exceeded for task '$taskName'" }
                throw e
            }
        }
    } while (!success)
}

class TaskException(message: String) : RuntimeException(message)

/**
 *  Throw an exception as the task failed. It is inline so that call stack is not polluted.
 */
private inline fun failTask(reason: String): Nothing {
    throw TaskException(reason)
}

class AltchainBlockReorgException(
    val block: SecurityInheritingBlock
) : IllegalStateException("There was a reorg leaving block ${block.hash} out of the main chain!")

class AltchainTransactionReorgException(
    val transaction: SecurityInheritingTransaction
) : IllegalStateException("There was a reorg leaving transaction ${transaction.txId} out of the main chain!")
