package com.example.auction.item.impl

import java.time.{Duration, Instant}
import java.util.UUID

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import play.api.libs.json.{Format, Json}
import com.example.auction.utils.JsonFormats._

class ItemEntity extends PersistentEntity {
  override type Command = ItemCommand
  override type Event = ItemEvent
  override type State = Option[Item]

  override def initialState: Option[Item] = None

  override def behavior: Behavior = {
    case None => notCreated
    case Some(item) if item.status == ItemStatus.CreatedStatus => created(item)
    case Some(item) if item.status == ItemStatus.AuctionStatus => auction(item)
    case Some(item) if item.status == ItemStatus.CompletedStatus => completed
    case Some(item) if item.status == ItemStatus.CancelledStatus => cancelled
  }

  private val getItemCommand = Actions().onReadOnlyCommand[GetItemCommand.type, Option[Item]] {
    case (GetItemCommand, ctx, state) => ctx.reply(state)
  }

  private val notCreated = {
    Actions().onCommand[CreateItemCommand, Done] {
      case (CreateItemCommand(item), ctx, state) =>
        ctx.thenPersist(ItemCreatedEvent(item))(_ => ctx.reply(Done))
    }.onEvent {
      case (ItemCreatedEvent(item), state) => Some(item)
    }.orElse(getItemCommand)
  }

  private def created(item: Item) = {
    Actions().onCommand[StartAuctionCommand, Done] {
      case (StartAuctionCommand(userId), ctx, _) =>
        if (item.creator != userId) {
          ctx.invalidCommand("Only the creator of an auction can start it")
          ctx.done
        } else {
          ctx.thenPersist(AuctionStartedEvent(Instant.now()))(_ => ctx.reply(Done))
        }
    }.onEvent {
      case (AuctionStartedEvent(time), Some(item)) => Some(item.start(time))
    }.orElse(getItemCommand)
  }

  private def auction(item: Item) = {
    Actions().onCommand[UpdatePriceCommand, Done] {
      case (UpdatePriceCommand(price), ctx, _) =>
        ctx.thenPersist(PriceUpdatedEvent(price))(_ => ctx.reply(Done))
    }.onCommand[FinishAuctionCommand, Done] {
      case (FinishAuctionCommand(winner, price), ctx, _) =>
        ctx.thenPersist(AuctionFinishedEvent(winner, price))(_ => ctx.reply(Done))
    }.onEvent {
      case (PriceUpdatedEvent(price), _) => Some(item.updatePrice(price))
      case (AuctionFinishedEvent(winner, price), _) => Some(item.end(winner, price))
    }.onReadOnlyCommand[StartAuctionCommand, Done] {
      case (_, ctx, _) => ctx.reply(Done)
    }.orElse(getItemCommand)
  }

  private val completed = {
    Actions().onReadOnlyCommand[UpdatePriceCommand, Done] {
      case (_, ctx, _) => ctx.reply(Done)
    }.onReadOnlyCommand[FinishAuctionCommand, Done] {
      case (_, ctx, _) => ctx.reply(Done)
    }.onReadOnlyCommand[StartAuctionCommand, Done] {
      case (_, ctx, _) => ctx.reply(Done)
    }.orElse(getItemCommand)
  }

  private val cancelled = completed

}

object ItemStatus extends Enumeration {
  val CreatedStatus, AuctionStatus, CompletedStatus, CancelledStatus = Value
  type Status = Value

  implicit val format: Format[Status] = enumFormat(ItemStatus)
}

case class Item(
  id: UUID,
  creator: UUID,
  title: String,
  description: String,
  currencyId: String,
  increment: Int,
  reservePrice: Int,
  price: Option[Int],
  status: ItemStatus.Status,
  auctionDuration: Duration,
  auctionStart: Option[Instant],
  auctionEnd: Option[Instant],
  auctionWinner: Option[UUID]
) {

  def start(startTime: Instant) = {
    assert(status == ItemStatus.CreatedStatus)
    copy(
      status = ItemStatus.AuctionStatus,
      auctionStart = Some(startTime),
      auctionEnd = Some(startTime.plus(auctionDuration))
    )
  }

  def end(winner: Option[UUID], price: Option[Int]) = {
    assert(status == ItemStatus.AuctionStatus)
    copy(
      status = ItemStatus.CompletedStatus,
      price = price,
      auctionWinner = winner
    )
  }

  def updatePrice(price: Int) = {
    assert(status == ItemStatus.AuctionStatus)
    copy(
      price = Some(price)
    )
  }

  def cancel = {
    assert(status == ItemStatus.AuctionStatus || status == ItemStatus.CompletedStatus)
    copy(
      status = ItemStatus.CancelledStatus
    )
  }
}

object Item {
  implicit val format: Format[Item] = Json.format
}

sealed trait ItemCommand

case object GetItemCommand extends ItemCommand with ReplyType[Option[Item]] {
  implicit val format: Format[GetItemCommand.type] = singletonFormat(GetItemCommand)
}

case class CreateItemCommand(item: Item) extends ItemCommand with ReplyType[Done]

object CreateItemCommand {
  implicit val format: Format[CreateItemCommand] = Json.format
}

case class StartAuctionCommand(userId: UUID) extends ItemCommand with ReplyType[Done]

object StartAuctionCommand {
  implicit val format: Format[StartAuctionCommand] = Json.format
}

case class UpdatePriceCommand(price: Int) extends ItemCommand with ReplyType[Done]

object UpdatePriceCommand {
  implicit val format: Format[UpdatePriceCommand] = Json.format
}

case class FinishAuctionCommand(winner: Option[UUID], price: Option[Int]) extends ItemCommand with ReplyType[Done]

object FinishAuctionCommand {
  implicit val format: Format[FinishAuctionCommand] = Json.format
}

sealed trait ItemEvent extends AggregateEvent[ItemEvent] {
  override def aggregateTag = ItemEvent.Tag
}

object ItemEvent {
  val NumShards = 4
  val Tag = AggregateEventTag.sharded[ItemEvent](NumShards)
}

case class ItemCreatedEvent(item: Item) extends ItemEvent

object ItemCreatedEvent {
  implicit val format: Format[ItemCreatedEvent] = Json.format
}

case class AuctionStartedEvent(startTime: Instant) extends ItemEvent

object AuctionStartedEvent {
  implicit val format: Format[AuctionStartedEvent] = Json.format
}

case class PriceUpdatedEvent(price: Int) extends ItemEvent

object PriceUpdatedEvent {
  implicit val format: Format[PriceUpdatedEvent] = Json.format
}

case class AuctionFinishedEvent(winner: Option[UUID], price: Option[Int]) extends ItemEvent

object AuctionFinishedEvent {
  implicit val format: Format[AuctionFinishedEvent] = Json.format
}
