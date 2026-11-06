package com.example.qrorder.menu.repository;

import com.example.qrorder.menu.model.MenuCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MenuCategoryRepository extends MongoRepository<MenuCategory, String> {

    Optional<MenuCategory> findByIdAndHotelId(String id, String hotelId);

    List<MenuCategory> findByHotelId(String hotelId);

    List<MenuCategory> findByHotelIdAndActiveTrue(String hotelId);

    boolean existsByHotelIdAndNameKey(String hotelId, String nameKey);
}