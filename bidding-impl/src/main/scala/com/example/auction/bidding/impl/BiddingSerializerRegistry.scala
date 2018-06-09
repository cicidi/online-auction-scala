package com.example.auction.bidding.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializerRegistry, JsonSerializer}

import scala.collection.immutable.Seq

object BiddingSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    // State
    JsonSerializer[AuctionState],
    // Commands and replies
    JsonSerializer[GetAuctionCommand.type],
    JsonSerializer[StartAuctionCommand],
    JsonSerializer[PlaceBidCommand],
    JsonSerializer[PlaceBidResult],
    JsonSerializer[FinishBiddingCommand.type],
    JsonSerializer[CancelAuctionCommand.type],
    // Events
    JsonSerializer[AuctionStartedEvent],
    JsonSerializer[BidPlacedEvent],
    JsonSerializer[BiddingFinishedEvent.type],
    JsonSerializer[AuctionCancelledEvent.type]
  )
}
