package com.example.qrorder.ordering.service;

import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class OrderTransitionService{

    private final MongoTemplate mongo;
    private final Clock clock;

    public OrderTransitionService(MongoTemplate mongo, Clock clock){
        this.mongo = mongo;
        this.clock =clock;
    }


    public Optional<Order> transition(Criteria scope, OrderStatus expected, OrderStatus next, OrderActor actor,
                                      String reason){
        if(!expected.canTransitionTo(next)){
            throw new IllegalStateException("Transitioin" + expected +" -> " + next +" is not in the state machine");
        }

        Instant now = clock.instant();

        Query query = Query.query(scope.and("status").is(expected));
        Update update = new Update()
                .set("status",next)
                .push("statusHistory", new OrderStatusChange(expected, next, actor.type(), actor.id(),reason, now))
                .inc("version", 1);
        if(next == OrderStatus.CANCELLED){
            update.set("cancelledAt", now).set("cancelledBy", actor.type());
            if(reason != null){
                update.set("cancelReason", reason);
            }
        }

        return Optional.ofNullable(mongo.findAndModify(query, update,FindAndModifyOptions.options().returnNew(true), Order.class));
    }
}