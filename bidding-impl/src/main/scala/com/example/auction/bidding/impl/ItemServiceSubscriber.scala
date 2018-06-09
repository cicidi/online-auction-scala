package com.example.auction.bidding.impl

import java.util.UUID

import akka.Done
import akka.stream.scaladsl.Flow
import com.example.auction.item.api.{ItemEvent, ItemService}
import com.example.auction.item.api
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry

import scala.concurrent.Future

class ItemServiceSubscriber(persistentEntityRegistry: PersistentEntityRegistry, itemService: ItemService) {

  itemService.itemEvents.subscribe.atLeastOnce(Flow[ItemEvent].mapAsync(1) {

    case auctionStarted: api.AuctionStarted =>
      val auction = Auction(
        itemId = auctionStarted.itemId,
        creator = auctionStarted.creator,
        reservePrice = auctionStarted.reservePrice,
        increment = auctionStarted.increment,
        startTime = auctionStarted.startDate,
        endTime = auctionStarted.endDate
      )
      println(auction);
      entityRef(auctionStarted.itemId).ask(StartAuctionCommand(auction));

    case api.AuctionCancelled(itemId) =>
      entityRef(itemId).ask(CancelAuctionCommand)

    case other =>
      Future.successful(Done)

  })

  private def entityRef(itemId: UUID) = persistentEntityRegistry.refFor[AuctionEntity](itemId.toString)

}
