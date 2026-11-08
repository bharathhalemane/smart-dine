package com.example.qrorder.ordering.repository;

import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderStatus;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {
    Optional<Order> findByCustomerSessionIdAndIdempotencyKey(String customerSessionId, String idempotencyKey);

    Optional<Order> findByIdAndCustomerSessionId(String id, String customerSessionId);

    List<Order> findFirst50ByCustomerSessionIdOrderByCreatedAtDescIdDesc(String customerSessionId);

    Optional<Order> findByIdAndHotelId(String id, String hotelId);

    List<Order> findFirst200ByHotelIdAndStatusInOrderByCreatedAtAscIdAsc(String hotelId, Collection<OrderStatus> statuses);

    List<Order> findByHotelIdAndStatusIn(String hotelId, Collection<OrderStatus> statuses);

    long countByDiningSessionIdAndStatusIn(String diningSessionId, Collection<OrderStatus> statuses);

}