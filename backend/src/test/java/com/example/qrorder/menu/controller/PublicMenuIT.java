package com.example.qrorder.menu.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelStatus;
import com.example.qrorder.tenancy.repository.HotelRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class PublicMenuIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired HotelRepository hotels;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;

    String hotelId;

    @BeforeEach
    void seed() {
        hotels.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        Instant now = Instant.now();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR", now))
                .getId();

        // saved out of display order on purpose: Drinks (order 2) before Starters (order 1)
        String drinks = categories.save(new MenuCategory(hotelId, "Drinks", null, 2, now)).getId();
        String starters = categories.save(new MenuCategory(hotelId, "Starters", null, 1, now)).getId();
        MenuCategory hiddenCategory = new MenuCategory(hotelId, "Retired", null, 3, now);
        hiddenCategory.setActive(false);
        String retired = categories.save(hiddenCategory).getId();
        String empty = categories.save(new MenuCategory(hotelId, "Empty", null, 4, now)).getId();

        items.save(new MenuItem(hotelId, drinks, "Lassi", null, 6000L, Diet.VEG, 0, now));
        items.save(new MenuItem(hotelId, starters, "Soup", "Hot", 15000L, Diet.VEG, 1, now));
        MenuItem soldOut = new MenuItem(hotelId, starters, "Paneer Tikka", null, 28000L, Diet.VEG, 0, now);
        soldOut.setAvailable(false);
        items.save(soldOut);
        MenuItem removed = new MenuItem(hotelId, starters, "Removed dish", null, 1000L, Diet.VEG, 2, now);
        removed.setActive(false);
        items.save(removed);
        items.save(new MenuItem(hotelId, retired, "In a retired category", null, 1000L, Diet.VEG, 0, now));
    }

    private String url(String id) {
        return "/api/v1/public/hotels/" + id + "/menu";
    }

    @Test
    void anonymousCustomersSeeTheOrderedMenuWithOnlyLiveCategoriesAndItems() throws Exception {
        mvc.perform(get(url(hotelId)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")))
                .andExpect(jsonPath("$.hotelName").value("Spice Garden"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].name").value("Starters"))
                .andExpect(jsonPath("$.categories[1].name").value("Drinks"))
                .andExpect(jsonPath("$.categories[0].items.length()").value(2))
                .andExpect(jsonPath("$.categories[0].items[0].name").value("Paneer Tikka"))
                .andExpect(jsonPath("$.categories[0].items[1].name").value("Soup"))
                .andExpect(jsonPath("$.categories[1].items[0].name").value("Lassi"));
    }

    @Test
    void unavailableItemsAreShownAsSuchAndInternalFieldsAreNotExposed() throws Exception {
        mvc.perform(get(url(hotelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[0].items[0].available").value(false))
                .andExpect(jsonPath("$.categories[0].items[1].available").value(true))
                .andExpect(jsonPath("$.categories[0].items[0].active").doesNotExist())
                .andExpect(jsonPath("$.categories[0].items[0].hotelId").doesNotExist())
                .andExpect(jsonPath("$.categories[0].items[0].createdAt").doesNotExist());
    }

    @Test
    void anUnknownOrSuspendedHotelIsNotFound() throws Exception {
        mvc.perform(get(url("does-not-exist"))).andExpect(status().isNotFound());

        Hotel hotel = hotels.findById(hotelId).orElseThrow();
        hotel.setStatus(HotelStatus.SUSPENDED);
        hotels.save(hotel);
        mvc.perform(get(url(hotelId))).andExpect(status().isNotFound());
    }
}