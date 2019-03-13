package com.wavesplatform.api.grpc

import com.google.protobuf.empty.Empty
import com.google.protobuf.wrappers.{UInt32Value, UInt64Value}
import com.wavesplatform.api.http.{ApiError, BlockDoesNotExist}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.protobuf.block.PBBlock
import com.wavesplatform.state.Blockchain
import io.grpc.stub.StreamObserver
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable

import scala.concurrent.Future

class BlocksApiGrpcImpl(blockchain: Blockchain) extends BlocksApiGrpc.BlocksApi {

  override def getBlocksByAddress(request: BlocksByAddressRequest, responseObserver: StreamObserver[BlockAndHeight]): Unit = {
    val address = request.getAddress.toAddress
    val blocks = Observable
      .fromIterable(request.fromHeight to request.toHeight)
      .map(height => (blockchain.blockAt(height), height))
      .collect { case (Some(block), height) if block.signerData.generator.toAddress == address => BlockAndHeight(Some(block.toPB), height) }

    responseObserver.completeWith(blocks)
  }

  override def getChildBlock(request: BlockIdRequest): Future[PBBlock] = {
    val childBlock = for {
      h <- blockchain.heightOf(request.blockId)
      b <- blockchain.blockAt(h + 1)
    } yield b.toPB

    childBlock.toFuture
  }

  override def calcBlocksDelay(request: BlocksDelayRequest): Future[UInt64Value] = {
    val result = getBlockById(request.blockId).flatMap { block =>
      blockchain
        .parent(block.toVanilla, request.blockNum)
        .map(parent => UInt64Value((block.getHeader.getHeader.timestamp - parent.timestamp) / request.blockNum))
        .toRight(BlockDoesNotExist)
    }

    result.toFuture
  }

  override def getBlockHeight(request: BlockIdRequest): Future[UInt32Value] = {
    blockchain
      .heightOf(request.blockId)
      .map(UInt32Value(_))
      .toFuture
  }

  override def getCurrentHeight(request: Empty): Future[UInt32Value] = {
    Future.successful(UInt32Value(blockchain.height))
  }

  override def getBlockAtHeight(request: UInt32Value): Future[PBBlock] = {
    blockchain.blockAt(request.value).map(_.toPB).toFuture
  }

  override def getBlockHeaderAtHeight(request: UInt32Value): Future[PBBlock.SignedHeader] = {
    blockchain.blockHeaderAndSize(request.value).map { case (header, _) => header.toPBHeader }.toFuture
  }

  override def getBlocksRange(request: BlocksRangeRequest, responseObserver: StreamObserver[PBBlock]): Unit = {
    val stream = Observable
      .fromIterable(request.fromHeight to request.toHeight)
      .map(height => blockchain.blockAt(height))
      .collect { case Some(block) => block.toPB }

    responseObserver.completeWith(stream)
  }

  override def getBlockHeadersRange(request: BlocksRangeRequest, responseObserver: StreamObserver[PBBlock.SignedHeader]): Unit = {
    val stream = Observable
      .fromIterable(request.fromHeight to request.toHeight)
      .map(height => blockchain.blockHeaderAndSize(height))
      .collect { case Some((header, _)) => header.toPBHeader }

    responseObserver.completeWith(stream)
  }

  override def getLastBlock(request: Empty): Future[PBBlock] = {
    blockchain.lastBlock.map(_.toPB).toFuture
  }

  override def getLastBlockHeader(request: Empty): Future[PBBlock.SignedHeader] = {
    blockchain.lastBlockHeaderAndSize
      .map(_._1.toPBHeader)
      .toFuture
  }

  override def getFirstBlock(request: Empty): Future[PBBlock] = {
    Future.successful(blockchain.genesis.toPB)
  }

  override def getBlockBySignature(request: BlockIdRequest): Future[PBBlock] = {
    getBlockById(request.blockId).toFuture
  }

  private[this] def getBlockById(signature: ByteStr): Either[ApiError, PBBlock] = {
    blockchain
      .blockById(signature)
      .toRight(BlockDoesNotExist)
      .map(_.toPB)
  }
}