package com.example.qrorder.menu.repository;

import com.example.qrorder.menu.model.MenuItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MenuItemRepository extends MongoRepository<MenuItem, String> {

    Optional<MenuItem> findByIdAndHotelId(String id, String hotelId);

    List<MenuItem> findByHotelId(String hotelId);

    List<MenuItem> findByHotelIdAndCategoryId(String hotelId, String categoryId);

    boolean existsByHotelIdAndCategoryIdAndNameKey(String hotelId, String categoryId, String nameKey);

    List<MenuItem> findByHotelIdAndActiveTrue(String hotelId);
}