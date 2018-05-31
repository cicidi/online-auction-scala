package com.example.auction.bidding.impl

import java.time.Instant
import java.util.UUID

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, AggregateEventTagger, PersistentEntity}
import play.api.libs.json.{Format, Json}
import com.example.auction.utils.JsonFormats._
import com.lightbend.lagom.scaladsl.api.transport.{TransportErrorCode, TransportException}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType

/**
  * The auction persistent entity.
  */
class AuctionEntity extends PersistentEntity {

  import AuctionStatus._

  override type State = AuctionState
  override type Command = AuctionCommand
  override type Event = AuctionEvent

  override def initialState: AuctionState = AuctionState.notStarted


  override def behavior: Behavior = {
    case AuctionState(_, NotStarted, _) => notStarted
    case AuctionState(_, UnderAuction, _) => underAuction
    case AuctionState(_, Complete, _) => complete
    case AuctionState(_, Cancelled, _) => cancelled
  }

  private def cancelActions = Actions().onCommand[CancelAuctionCommand.type, Done] {
    case (CancelAuctionCommand, ctx, _) =>
      ctx.thenPersist(AuctionCancelledEvent)(_ => ctx.reply(Done))
  }.onEvent {
    case (AuctionCancelledEvent, auctionState) => auctionState.withStatus(Cancelled)
  }

  private def getAuctionAction = Actions().onReadOnlyCommand[GetAuctionCommand.type, AuctionState] {
    case (GetAuctionCommand, ctx, auctionState) => ctx.reply(auctionState)
  }

  /**
    * Behavior for the not started state.
    */
  private val notStarted = {
    getAuctionAction orElse {

      Actions().onCommand[StartAuction, Done] {
        case (StartAuction(auction), ctx, _) =>
          ctx.thenPersist(AuctionStartedEvent(auction))(_ => ctx.reply(Done))
      }.onReadOnlyCommand[PlaceBidCommand, PlaceBidResult] {
        case (PlaceBidCommand(_, _), ctx, auctionState) =>
          ctx.reply(createResult(PlaceBidStatus.NotStarted, auctionState))
      }.onEvent {
        case (AuctionStartedEvent(auction), _) =>
          AuctionState.start(auction)
      }

    } orElse cancelActions
  }

  /**
    * Behavior for the under auction state.
    */
  private val underAuction = {
    getAuctionAction orElse {

      Actions().onReadOnlyCommand[StartAuction, Done] {
        case (StartAuction(_), ctx, _) =>
          ctx.reply(Done)
      }.onCommand[PlaceBidCommand, PlaceBidResult] {
        case (placeBid: PlaceBidCommand, ctx, auctionState) =>
          handlePlaceBidWhileUnderAuction(placeBid, ctx, auctionState)
      }.onCommand[FinishBiddingCommand.type, Done] {
        case (FinishBiddingCommand, ctx, auctionState) =>
          ctx.thenPersist(BiddingFinishedEvent)(_ => ctx.reply(Done))
      }.onEvent {
        case (BidPlacedEvent(bid), auctionState) => auctionState.bid(bid)
        case (BiddingFinishedEvent, auctionState) => auctionState.withStatus(Complete)
      }

    } orElse cancelActions
  }

  /**
    * Behavior for the completed state.
    */
  private val complete = {
    getAuctionAction orElse {

      Actions().onReadOnlyCommand[StartAuction, Done] {
        case (StartAuction(_), ctx, _) =>
          ctx.reply(Done)
      }.onReadOnlyCommand[FinishBiddingCommand.type, Done] {
        case (FinishBiddingCommand, ctx, _) =>
          ctx.reply(Done)
      }.onReadOnlyCommand[PlaceBidCommand, PlaceBidResult] {
        case (PlaceBidCommand(_, _), ctx, auctionState) =>
          ctx.reply(createResult(PlaceBidStatus.Finished, auctionState))
      }

    } orElse cancelActions
  }

  /**
    * Behavior for the cancelled state.
    */
  private val cancelled = {
    getAuctionAction orElse {

      Actions().onReadOnlyCommand[StartAuction, Done] {
        case (StartAuction(_), ctx, _) =>
          ctx.reply(Done)
      }.onReadOnlyCommand[FinishBiddingCommand.type, Done] {
        case (FinishBiddingCommand, ctx, _) =>
          ctx.reply(Done)
      }.onReadOnlyCommand[PlaceBidCommand, PlaceBidResult] {
        case (PlaceBidCommand(_, _), ctx, auctionState) =>
          ctx.reply(createResult(PlaceBidStatus.Cancelled, auctionState))
      }.onReadOnlyCommand[CancelAuctionCommand.type, Done] {
        case (CancelAuctionCommand, ctx, _) =>
          ctx.reply(Done)
      }
    }
  }

  /**
    * The main logic for handling of bids.
    */
  private def handlePlaceBidWhileUnderAuction(bid: PlaceBidCommand, ctx: CommandContext[PlaceBidResult], auctionState: AuctionState): Persist = {
    val AuctionState(Some(auction), _, history) = auctionState
    val now = Instant.now
    // Even though we're not in the finished state yet, we should check
    if (auction.endTime.isBefore(now)) {
      reply(ctx, createResult(PlaceBidStatus.Finished, auctionState))
    } else if (auction.creator == bid.bidder) {
      throw BidValidationException("An auctions creator cannot bid in their own auction.")
    }  else {


      history.headOption match {

        case Some(Bid(currentBidder, _, currentPrice, _)) if bid.bidPrice >= currentPrice && bid.bidder == currentBidder
          && bid.bidPrice >= auction.reservePrice =>
          // Allow the current bidder to update their bid
          ctx.thenPersist(BidPlacedEvent(Bid(bid.bidder, now, currentPrice, bid.bidPrice))) { _ =>
            ctx.reply(PlaceBidResult(PlaceBidStatus.Accepted, currentPrice, Some(bid.bidder)))
          }

        case None if bid.bidPrice < auction.increment =>
          reply(ctx, createResult(PlaceBidStatus.TooLow, auctionState))
        case Some(Bid(_, _, currentPrice, _)) if bid.bidPrice < currentPrice + auction.increment =>
          reply(ctx, createResult(PlaceBidStatus.TooLow, auctionState))

        case Some(currentBid @ Bid(_, _, _, currentMaximum)) if bid.bidPrice <= currentMaximum =>
          handleAutomaticOutbid(bid, ctx, auction, now, currentBid)

        case _ if bid.bidPrice < auction.reservePrice =>
          ctx.thenPersist(BidPlacedEvent(Bid(bid.bidder, now, bid.bidPrice, bid.bidPrice))) { _ =>
            ctx.reply(PlaceBidResult(PlaceBidStatus.AcceptedBelowReserve, bid.bidPrice, Some(bid.bidder)))
          }

        case Some(Bid(_, _, _, currentMaximum)) =>
          val nextIncrement = Math.min(currentMaximum + auction.increment, bid.bidPrice)
          ctx.thenPersist(BidPlacedEvent(Bid(bid.bidder, now, nextIncrement, bid.bidPrice))) { _ =>
            ctx.reply(PlaceBidResult(PlaceBidStatus.Accepted, nextIncrement, Some(bid.bidder)))
          }

        case None =>
          // Ensure that the bid is both at least the reserve, and at least the increment
          val firstBid = Math.max(auction.reservePrice, auction.increment)
          ctx.thenPersist(BidPlacedEvent(Bid(bid.bidder, now, firstBid, bid.bidPrice))) { _ =>
            ctx.reply(PlaceBidResult(PlaceBidStatus.Accepted, firstBid, Some(bid.bidder)))
          }
      }
    }
  }

  /**
    * Handle the situation where a bid will be accepted, but it will be automatically outbid by the current bidder.
    *
    * This emits two events, one for the bid currently being replace, and another automatic bid for the current bidder.
    */
  private def handleAutomaticOutbid(bid: PlaceBidCommand, ctx: CommandContext[PlaceBidResult], auction: Auction,
                                    now: Instant, currentBid: Bid): Persist = {
    // Adjust the bid so that the increment for the current maximum makes the current maximum a valid bid
    val adjustedBidPrice = Math.min(bid.bidPrice, currentBid.maximumBid - auction.increment)
    val newBidPrice = adjustedBidPrice + auction.increment

    ctx.thenPersistAll(
      BidPlacedEvent(Bid(bid.bidder, now, adjustedBidPrice, bid.bidPrice)),
      BidPlacedEvent(Bid(currentBid.bidder, now, newBidPrice, currentBid.maximumBid))
    ) { () =>
      ctx.reply(PlaceBidResult(PlaceBidStatus.AcceptedOutbid, newBidPrice, Some(currentBid.bidder)))
    }
  }

  private def reply(ctx: CommandContext[PlaceBidResult], result: PlaceBidResult): Persist = {
    ctx.reply(result)
    ctx.done
  }

  private def createResult(status: PlaceBidStatus.Status, auctionState: AuctionState): PlaceBidResult = {
    auctionState.biddingHistory.headOption match {
      case Some(Bid(bidder, _, price, _)) =>
        PlaceBidResult(status, price, Some(bidder))
      case None =>
        PlaceBidResult(status, 0, None)
    }
  }
}

/**
  * An auction.
  *
  * @param itemId The item under auction.
  * @param creator The user that created the item.
  * @param reservePrice The reserve price of the auction.
  * @param increment The minimum increment between bids.
  * @param startTime The time the auction started.
  * @param endTime The time the auction will end.
  */
case class Auction(itemId: UUID, creator: UUID, reservePrice: Int, increment: Int, startTime: Instant, endTime: Instant)

object Auction {
  implicit val format: Format[Auction] = Json.format
}

/**
  * A bid.
  *
  * @param bidder The bidder.
  * @param bidTime The time the bid was placed.
  * @param bidPrice The bid price.
  * @param maximumBid The maximum the bidder is willing to bid.
  */
case class Bid(bidder: UUID, bidTime: Instant, bidPrice: Int, maximumBid: Int)

object Bid {
  implicit val format: Format[Bid] = Json.format
}

/**
  * The auction state.
  */
case class AuctionState(auction: Option[Auction], status: AuctionStatus.Status, biddingHistory: Seq[Bid]) {
  def withStatus (status: AuctionStatus.Status) = copy(status = status)
  def bid(bid: Bid) = if (biddingHistory.headOption.exists(_.bidder == bid.bidder)) {
    copy(biddingHistory = bid +: biddingHistory.tail)
  } else {
    copy(biddingHistory = bid +: biddingHistory)
  }
}

object AuctionState {
  implicit val format: Format[AuctionState] = Json.format
  val notStarted = AuctionState(None, AuctionStatus.NotStarted, Nil)
  def start(auction: Auction): AuctionState = AuctionState(Some(auction), AuctionStatus.UnderAuction, Nil)
}

/**
  * Auction status.
  */
object AuctionStatus extends Enumeration {
  type Status = Value
  val NotStarted, UnderAuction, Complete, Cancelled = Value

  implicit val format: Format[Status] = enumFormat(AuctionStatus)
}

/**
  * An auction command.
  */
trait AuctionCommand

/**
  * Start the auction.
  */
case class StartAuction(auction: Auction) extends AuctionCommand with ReplyType[Done]

object StartAuction {
  implicit val format: Format[StartAuction] = Json.format
}

/**
  * Cancel the auction.
  */
case object CancelAuctionCommand extends AuctionCommand with ReplyType[Done] {
  implicit val format: Format[CancelAuctionCommand.type] = singletonFormat(CancelAuctionCommand)
}

/**
  * Place a bid on the auction.
  */
case class PlaceBidCommand(bidPrice: Int, bidder: UUID) extends AuctionCommand with ReplyType[PlaceBidResult]

object PlaceBidCommand {
  implicit val format: Format[PlaceBidCommand] = Json.format
}

/**
  * The status of the result of placing a bid.
  */
object PlaceBidStatus extends Enumeration {
  /**
    * The bid was accepted, and is the current highest bid.
    */
  val Accepted,
  /**
    * The bid was accepted, but was outbidded by the maximum bid of the current highest bidder.
    */
  AcceptedOutbid,
  /**
    * The bid was accepted, but is below the reserve.
    */
  AcceptedBelowReserve,
  /**
    * The bid was not at least the current bid plus the increment.
    */
  TooLow,
  /**
    * The auction hasn't started.
    */
  NotStarted,
  /**
    * The auction has already finished.
    */
  Finished,
  /**
    * The auction has been cancelled.
    */
  Cancelled = Value

  type Status = Value

  implicit val format: Format[Status] = enumFormat(PlaceBidStatus)
}

case class PlaceBidResult(status: PlaceBidStatus.Status, currentPrice: Int, currentBidder: Option[UUID])

object PlaceBidResult {
  implicit val format: Format[PlaceBidResult] = Json.format
}

/**
  * Finish bidding.
  */
case object FinishBiddingCommand extends AuctionCommand with ReplyType[Done] {
  implicit val format: Format[FinishBiddingCommand.type] = singletonFormat(FinishBiddingCommand)
}

/**
  * Get the auction.
  */
case object GetAuctionCommand extends AuctionCommand with ReplyType[AuctionState] {
  implicit val format: Format[GetAuctionCommand.type] = singletonFormat(GetAuctionCommand)
}

/**
  * A persisted auction event.
  */
trait AuctionEvent extends AggregateEvent[AuctionEvent] {
  override def aggregateTag: AggregateEventTagger[AuctionEvent] = AuctionEvent.Tag
}

object AuctionEvent {
  val NumShards = 4
  val Tag = AggregateEventTag.sharded[AuctionEvent](4)
}

/**
  * The auction started.
  *
  * @param auction The auction details.
  */
case class AuctionStartedEvent(auction: Auction) extends AuctionEvent

object AuctionStartedEvent {
  implicit val format: Format[AuctionStartedEvent] = Json.format
}

/**
  * A bid was placed
  *
  * @param bid The bid.
  */
case class BidPlacedEvent(bid: Bid) extends AuctionEvent

object BidPlacedEvent {
  implicit val format: Format[BidPlacedEvent] = Json.format
}

/**
  * Bidding finished.
  */
case object BiddingFinishedEvent extends AuctionEvent {
  implicit val format: Format[BiddingFinishedEvent.type] = singletonFormat(BiddingFinishedEvent)
}

/**
  * The auction was cancelled.
  */
case object AuctionCancelledEvent extends AuctionEvent {
  implicit val format: Format[AuctionCancelledEvent.type] = singletonFormat(AuctionCancelledEvent)
}

/**
  * Exception thrown when a bid fails validation.
  */
case class BidValidationException(message: String) extends TransportException(TransportErrorCode.PolicyViolation, message)
