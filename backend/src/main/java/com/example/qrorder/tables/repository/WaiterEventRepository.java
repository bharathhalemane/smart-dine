package com.example.qrorder.tables.repository;

import com.example.qrorder.tables.model.WaiterEvent;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface WaiterEventRepository extends MongoRepository<WaiterEvent, String> {

    List<WaiterEvent> findFirst100ByHotelIdOrderByAtDescIdDesc(String hotelId);

    List<WaiterEvent> findFirst100ByHotelIdAndTableIdOrderByAtDescIdDesc(String hotelId, String tableId);
}