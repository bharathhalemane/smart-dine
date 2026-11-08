package com.example.qrorder.tables.repository;

import com.example.qrorder.tables.model.TableAssignment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TableAssignmentRepository extends MongoRepository<TableAssignment, String> {

    Optional<TableAssignment> findByTableId(String tableId);

    List<TableAssignment> findByHotelId(String hotelId);
}