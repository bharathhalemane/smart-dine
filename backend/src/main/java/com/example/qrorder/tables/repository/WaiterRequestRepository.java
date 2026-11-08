package com.example.qrorder.tables.repository;

import com.example.qrorder.tables.model.RequestKind;
import com.example.qrorder.tables.model.RequestStatus;
import com.example.qrorder.tables.model.WaiterRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WaiterRequestRepository extends MongoRepository<WaiterRequest, String> {

    Optional<WaiterRequest> findByIdAndHotelId(String id, String hotelId);

    List<WaiterRequest> findFirst200ByHotelIdAndStatusOrderByCreatedAtAscIdAsc(String hotelId, RequestStatus status);

    List<WaiterRequest> findFirst20ByHotelIdAndRequestedByUserIdOrderByCreatedAtDescIdDesc(String hotelId,
                                                                                           String userId);

    Optional<WaiterRequest> findByTableIdAndRequestedByUserIdAndKindAndStatus(String tableId, String userId,
                                                                              RequestKind kind,
                                                                              RequestStatus status);
}