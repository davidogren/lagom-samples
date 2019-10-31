package com.example.shoppingcart.impl;

import akka.Done;
import akka.NotUsed;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.japi.Pair;
import com.example.shoppingcart.api.ShoppingCartView;
import com.example.shoppingcart.api.ShoppingCartItem;
import com.example.shoppingcart.api.ShoppingCartReportView;
import com.example.shoppingcart.api.ShoppingCartService;
import com.lightbend.lagom.javadsl.api.ServiceCall;
import com.lightbend.lagom.javadsl.api.broker.Topic;
import com.lightbend.lagom.javadsl.api.transport.BadRequest;
import com.lightbend.lagom.javadsl.api.transport.NotFound;
import com.lightbend.lagom.javadsl.broker.TopicProducer;
import com.lightbend.lagom.javadsl.persistence.PersistentEntityRegistry;

import javax.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;


/**
 * Implementation of the {@link ShoppingCartService}.
 */
public class ShoppingCartServiceImpl implements ShoppingCartService {

    private final PersistentEntityRegistry persistentEntityRegistry;

    private final ReportRepository reportRepository;

    private final ClusterSharding clusterSharing;

    @Inject
    public ShoppingCartServiceImpl(ClusterSharding clusterSharing,
                                   PersistentEntityRegistry persistentEntityRegistry,
                                   ReportRepository reportRepository) {
        this.clusterSharing = clusterSharing;
        this.persistentEntityRegistry = persistentEntityRegistry;
        this.reportRepository = reportRepository;

        // register entity on shard
        this.clusterSharing.init(
                Entity.of(
                        ShoppingCart.ENTITY_TYPE_KEY,
                        ShoppingCart::create
                )
        );
    }

    private EntityRef<ShoppingCart.Command> entityRef(String id) {
        return clusterSharing.entityRefFor(ShoppingCart.ENTITY_TYPE_KEY, id);
    }

    private final Duration askTimeout = Duration.ofSeconds(5);

    @Override
    public ServiceCall<NotUsed, ShoppingCartView> get(String id) {
        return request ->
                entityRef(id)
                        .ask(ShoppingCart.Get::new, askTimeout)
                        .thenApply(summary -> asShoppingCartView(id, summary));
    }

    @Override
    public ServiceCall<NotUsed, ShoppingCartReportView> getReport(String id) {
        return request ->
                reportRepository.findById(id).thenApply(report -> {
                    if (report != null)
                        return new ShoppingCartReportView(id, report.getCreationDate(), report.getCheckoutDate());
                    else
                        throw new NotFound("Couldn't find a shopping cart report for '" + id + "'");
                });
    }

    @Override
    public ServiceCall<ShoppingCartItem, Done> addItem(String id) {
        return item ->
                convertErrors(
                        entityRef(id)
                                .<ShoppingCart.Confirmation>ask(replyTo -> new ShoppingCart.AddItem(item.getItemId(), item.getQuantity(), replyTo), askTimeout)
                );
    }

    @Override
    public ServiceCall<NotUsed, ShoppingCartView> removeItem(String cartId, String itemId) {
        return request ->
            entityRef(cartId)
                .<ShoppingCart.Confirmation>ask(replyTo ->
                    new ShoppingCart.RemoveItem(itemId, replyTo), askTimeout).thenApply(
                        confirmation -> confirmationToResult(cartId, confirmation)
                    );
    }

    @Override
    public ServiceCall<NotUsed, Done> checkout(String id) {
        return request ->
                convertErrors(
                        entityRef(id)
                                .ask(ShoppingCart.Checkout::new, askTimeout)
                );
    }

    @Override
    public Topic<ShoppingCartView> shoppingCartTopic() {
        // We want to publish all the shards of the shopping cart events
        return TopicProducer.taggedStreamWithOffset(ShoppingCart.Event.TAG.allTags(), (tag, offset) ->
                // Load the event stream for the passed in shard tag
                persistentEntityRegistry.eventStream(tag, offset)
                        // We only want to publish checkout events
                        .filter(pair -> pair.first() instanceof ShoppingCart.CheckedOut)
                        // Now we want to convert from the persisted event to the published event.
                        // To do this, we need to load the current shopping cart state.
                        .mapAsync(4, eventAndOffset -> {
                            ShoppingCart.CheckedOut checkedOut = (ShoppingCart.CheckedOut) eventAndOffset.first();
                            return entityRef(checkedOut.getShoppingCartId())
                                    .ask(ShoppingCart.Get::new, askTimeout)
                                    .thenApply(summary -> Pair.create(
                                            asShoppingCartView(checkedOut.getShoppingCartId(), summary),
                                            eventAndOffset.second()
                                    ));
                        })
        );
    }

    private <T> CompletionStage<Done> convertErrors(CompletionStage<T> future) {
        return future.exceptionally(ex -> {
            if (ex instanceof ShoppingCartException) {
                throw new BadRequest(ex.getMessage());
            } else {
                throw new BadRequest("Error updating shopping cart");
            }
        }).thenApply(__ -> Done.getInstance());
    }

    private ShoppingCartView confirmationToResult(String cartId, ShoppingCart.Confirmation confirmation) {
        if (confirmation instanceof ShoppingCart.Accepted) {
            ShoppingCart.Accepted accepted = (ShoppingCart.Accepted) confirmation;
            return asShoppingCartView(cartId, accepted.getSummary());
        }

        ShoppingCart.Rejected rejected = (ShoppingCart.Rejected) confirmation;
        throw new BadRequest(rejected.getReason());
    }

    private ShoppingCartView asShoppingCartView(String id, ShoppingCart.Summary summary) {
        List<ShoppingCartItem> items = new ArrayList<>();
        for (Map.Entry<String, Integer> item : summary.getItems().entrySet()) {
            items.add(new ShoppingCartItem(item.getKey(), item.getValue()));
        }
        return new ShoppingCartView(id, items, summary.getCheckoutDate());
    }

}
