package com.example.auction.item.impl

import com.lightbend.lagom.scaladsl.playjson.{JsonSerializerRegistry, JsonSerializer}

object ItemSerializerRegistry extends JsonSerializerRegistry {
  override def serializers = List(
    JsonSerializer[Item],

    JsonSerializer[CreateItemCommand],
    JsonSerializer[StartAuctionCommand],
    JsonSerializer[UpdatePriceCommand],
    JsonSerializer[FinishAuctionCommand],
    JsonSerializer[GetItemCommand.type],

    JsonSerializer[ItemCreatedEvent],
    JsonSerializer[AuctionStartedEvent],
    JsonSerializer[PriceUpdatedEvent],
    JsonSerializer[AuctionFinishedEvent]
  )
}
