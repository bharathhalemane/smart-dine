package com.example.qrorder.menu.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class MenuItemIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String starters;
    String mains;

    @BeforeEach
    void clean() {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        starters = category(hotelId, "Starters", 0, true);
        mains = category(hotelId, "Mains", 1, true);
    }

    private String category(String hotel, String name, int order, boolean active) {
        MenuCategory category = new MenuCategory(hotel, name, null, order, Instant.now());
        category.setActive(active);
        return categories.save(category).getId();
    }

    private void seedMember(String email, HotelRole role) {
        seedMember(hotelId, email, role);
    }

    private void seedMember(String hotel, String email, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), "Test");
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String base() {
        return "/api/v1/hotels/" + hotelId + "/menu/items";
    }

    private ResultActions create(String bearer, String json) throws Exception {
        return call(post(base()), bearer, json);
    }

    private String createdId(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String item(String category, String name, long price) {
        return "{\"categoryId\":\"%s\",\"name\":\"%s\",\"priceMinor\":%d}".formatted(category, name, price);
    }

    @Test
    void aManagerCreatesAnItemWithThePriceInPaise() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        create(token, "{\"categoryId\":\"%s\",\"name\":\"  Paneer   Tikka \",\"description\":\"Charred, with mint chutney\",\"priceMinor\":24900,\"diet\":\"VEG\"}"
                .formatted(starters))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Paneer Tikka"))
                .andExpect(jsonPath("$.priceMinor").value(24900))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.diet").value("VEG"))
                .andExpect(jsonPath("$.categoryId").value(starters))
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.available").value(true));
        create(token, item(starters, "Soup", 14000))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sortOrder").value(1));

        MenuItem saved = items.findAll().stream().filter(i -> i.getName().equals("Paneer Tikka")).findFirst().orElseThrow();
        assertThat(saved.getHotelId()).isEqualTo(hotelId);
        assertThat(saved.getPriceMinor()).isEqualTo(24900L);
        assertThat(saved.getNameKey()).isEqualTo("paneer tikka");
    }

    @Test
    void anyMemberMayReadButOnlyMenuManagersMayChange() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        seedMember("cook@example.com", HotelRole.KITCHEN);
        seedMember("waiter@example.com", HotelRole.WAITER);
        String id = createdId(create(login("manager@example.com"), item(starters, "Soup", 14000)));

        for (String email : List.of("cook@example.com", "waiter@example.com")) {
            String token = login(email);
            call(get(base()), token, null).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            call(get(base() + "/" + id), token, null).andExpect(status().isOk());

            create(token, item(starters, "Salad", 9000)).andExpect(status().isForbidden());
            call(patch(base() + "/" + id), token, "{\"priceMinor\":1}").andExpect(status().isForbidden());
            call(delete(base() + "/" + id), token, null).andExpect(status().isForbidden());
            call(get(base() + "?includeInactive=true"), token, null).andExpect(status().isForbidden());
        }
        assertThat(items.count()).isEqualTo(1);
        assertThat(items.findAll().get(0).getPriceMinor()).isEqualTo(14000L);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        create(token, item(starters, "Soup", -1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        create(token, item(starters, "Soup", 10_000_001)).andExpect(status().isBadRequest());
        create(token, "{\"categoryId\":\"%s\",\"name\":\"Soup\"}".formatted(starters)).andExpect(status().isBadRequest());
        create(token, item(starters, "", 100)).andExpect(status().isBadRequest());
        create(token, item(starters, "x".repeat(101), 100)).andExpect(status().isBadRequest());
        create(token, "{\"name\":\"Soup\",\"priceMinor\":100}").andExpect(status().isBadRequest());
        create(token, "{\"categoryId\":\"%s\",\"name\":\"Soup\",\"priceMinor\":10.5}".formatted(starters)) // no fractions of a paisa
                .andExpect(status().isBadRequest());
        assertThat(items.count()).isZero();

        create(token, item(starters, "Free papad", 0)).andExpect(status().isCreated()); // zero is allowed
    }

    @Test
    void theCategoryMustBelongToThisHotelAndBeActive() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String foreign = category(otherHotelId, "Drinks", 0, true);
        String inactive = category(hotelId, "Old specials", 5, false);

        create(token, item(foreign, "Lassi", 6000)).andExpect(status().isNotFound());
        create(token, item("no-such-id", "Lassi", 6000)).andExpect(status().isNotFound());
        create(token, item(inactive, "Lassi", 6000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(items.count()).isZero();
    }

    @Test
    void namesAreUniqueWithinACategoryIgnoringCase() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        seedMember(otherHotelId, "other@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");

        create(token, item(starters, "Soup", 14000)).andExpect(status().isCreated());
        create(token, item(starters, "  SOUP ", 15000))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));

        create(token, item(mains, "Soup", 18000)).andExpect(status().isCreated()); // another category: fine
        String otherCategory = category(otherHotelId, "Starters", 0, true);
        call(post("/api/v1/hotels/" + otherHotelId + "/menu/items"), login("other@example.com"),
                item(otherCategory, "Soup", 12000)).andExpect(status().isCreated()); // another hotel: fine
        assertThat(items.count()).isEqualTo(3);
    }

    @Test
    void updatingChangesOnlyTheFieldsSentAndMovingCategoriesIsChecked() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        String soup = createdId(create(token, item(starters, "Soup", 14000)));
        createdId(create(token, item(mains, "Biryani", 29000)));
        createdId(create(token, item(mains, "Soup", 18000)));
        String foreign = category(otherHotelId, "Drinks", 0, true);

        call(patch(base() + "/" + soup), token, "{\"priceMinor\":15500,\"description\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Soup"))
                .andExpect(jsonPath("$.priceMinor").value(15500))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.categoryId").value(starters));

        call(patch(base() + "/" + soup), token, "{\"categoryId\":\"%s\"}".formatted(mains)) // "Soup" exists in Mains
                .andExpect(status().isConflict());
        call(patch(base() + "/" + soup), token, "{\"categoryId\":\"%s\"}".formatted(foreign)).andExpect(status().isNotFound());
        call(patch(base() + "/" + soup), token, "{\"name\":\"   \"}").andExpect(status().isBadRequest());

        call(patch(base() + "/" + soup), token, "{\"name\":\"Tomato Soup\",\"categoryId\":\"%s\"}".formatted(mains))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(mains))
                .andExpect(jsonPath("$.sortOrder").value(2)); // appended after Biryani (0) and Soup (1)

        MenuItem saved = items.findByIdAndHotelId(soup, hotelId).orElseThrow();
        assertThat(saved.getCategoryId()).isEqualTo(mains);
        assertThat(saved.getPriceMinor()).isEqualTo(15500L);
    }

    @Test
    void deleteDeactivatesAndInactiveItemsAreHiddenUnlessAManagerAsks() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        seedMember("waiter@example.com", HotelRole.WAITER);
        String manager = login("manager@example.com");
        String waiter = login("waiter@example.com");
        String soup = createdId(create(manager, item(starters, "Soup", 14000)));
        createdId(create(manager, item(starters, "Salad", 9000)));

        call(delete(base() + "/" + soup), manager, null).andExpect(status().isNoContent());

        assertThat(items.findByIdAndHotelId(soup, hotelId).orElseThrow().isActive()).isFalse();
        call(get(base()), waiter, null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Salad"));
        call(get(base() + "?includeInactive=true"), manager, null).andExpect(jsonPath("$.length()").value(2));

        call(patch(base() + "/" + soup), manager, "{\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        call(get(base()), waiter, null).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listsAreSortedAndCanBeFilteredByCategory() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String token = login("manager@example.com");
        create(token, "{\"categoryId\":\"%s\",\"name\":\"C dish\",\"priceMinor\":100,\"sortOrder\":5}".formatted(starters));
        create(token, "{\"categoryId\":\"%s\",\"name\":\"A dish\",\"priceMinor\":100,\"sortOrder\":1}".formatted(starters));
        create(token, "{\"categoryId\":\"%s\",\"name\":\"B dish\",\"priceMinor\":100,\"sortOrder\":3}".formatted(starters));
        create(token, item(mains, "Biryani", 29000));

        call(get(base() + "?categoryId=" + starters), token, null)
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("A dish"))
                .andExpect(jsonPath("$[1].name").value("B dish"))
                .andExpect(jsonPath("$[2].name").value("C dish"));
        call(get(base() + "?categoryId=" + mains), token, null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].name").value("Biryani"));
        call(get(base()), token, null).andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void anItemFromAnotherHotelIsNotFoundAndStaysUntouched() throws Exception {
        seedMember("manager@example.com", HotelRole.MANAGER);
        String foreignCategory = category(otherHotelId, "Drinks", 0, true);
        String foreign = items.save(new MenuItem(otherHotelId, foreignCategory, "Lassi", null, 6000L,
                Diet.VEG, 0, Instant.now())).getId();
        String token = login("manager@example.com");

        call(get(base() + "/" + foreign), token, null).andExpect(status().isNotFound());
        call(patch(base() + "/" + foreign), token, "{\"priceMinor\":1}").andExpect(status().isNotFound());
        call(delete(base() + "/" + foreign), token, null).andExpect(status().isNotFound());

        MenuItem untouched = items.findByIdAndHotelId(foreign, otherHotelId).orElseThrow();
        assertThat(untouched.getPriceMinor()).isEqualTo(6000L);
        assertThat(untouched.isActive()).isTrue();
    }
}