package com.example.qrorder.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.media.model.MediaAsset;
import com.example.qrorder.media.model.MediaPurpose;
import com.example.qrorder.media.model.MediaStatus;
import com.example.qrorder.media.repository.MediaAssetRepository;
import com.example.qrorder.media.service.MediaService;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.menu.model.MenuCategory;
import com.example.qrorder.menu.model.MenuItem;
import com.example.qrorder.menu.repository.MenuCategoryRepository;
import com.example.qrorder.menu.repository.MenuItemRepository;
import com.example.qrorder.menu.service.PublicMenuService;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.support.FakeObjectStorage;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(properties = "app.media.public-base-url=https://cdn.test")
class MediaUploadIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F',
            0, 1, 1, 0, 0, 1, 0, 1, 0, 0};

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired MenuCategoryRepository categories;
    @Autowired MenuItemRepository items;
    @Autowired MediaAssetRepository assets;
    @Autowired MediaService mediaService;
    @Autowired PublicMenuService publicMenu;
    @Autowired FakeObjectStorage storage;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String categoryId;
    String itemId;
    String owner;
    String waiter;
    String otherOwner;

    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        hotels.deleteAll();
        memberships.deleteAll();
        categories.deleteAll();
        items.deleteAll();
        assets.deleteAll();
        storage.clear();

        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        MenuCategory category = new MenuCategory(hotelId, "Starters", null, 0, Instant.now());
        category.setActive(true);
        categoryId = categories.save(category).getId();
        itemId = items.save(new MenuItem(hotelId, categoryId, "Soup", null, 15000L, Diet.VEG, 0, Instant.now())).getId();

        seedMember(hotelId, "owner@example.com", HotelRole.OWNER);
        seedMember(hotelId, "waiter@example.com", HotelRole.WAITER);
        seedMember(otherHotelId, "owner-b@example.com", HotelRole.OWNER);
        owner = login("owner@example.com");
        waiter = login("waiter@example.com");
        otherOwner = login("owner-b@example.com");
    }

    // ---------- helpers ----------

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

    private ResultActions send(MockHttpServletRequestBuilder request, String bearer, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String media(String hotel, String path) {
        return "/api/v1/hotels/" + hotel + "/media" + path;
    }

    private String uploadBody(String contentType, long size) {
        return "{\"purpose\":\"MENU_ITEM\",\"contentType\":\"%s\",\"sizeBytes\":%d}".formatted(contentType, size);
    }

    private ResultActions requestUpload(String token, String contentType, long size) throws Exception {
        return send(post(media(hotelId, "/uploads")), token, uploadBody(contentType, size));
    }

    private String newAsset(String token, String contentType, long size) throws Exception {
        MvcResult result = requestUpload(token, contentType, size).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.assetId");
    }

    private String keyOf(String assetId) {
        return assets.findById(assetId).orElseThrow().getObjectKey();
    }

    private ResultActions complete(String token, String assetId) throws Exception {
        return send(post(media(hotelId, "/" + assetId + "/complete")), token, null);
    }

    private String readyAsset() throws Exception {
        String id = newAsset(owner, "image/jpeg", JPEG.length);
        storage.put(keyOf(id), "image/jpeg", JPEG);
        complete(owner, id).andExpect(status().isOk());
        return id;
    }

    private ResultActions attach(String token, String item, String assetId) throws Exception {
        return send(put("/api/v1/hotels/" + hotelId + "/menu/items/" + item + "/image"), token,
                "{\"assetId\":\"%s\"}".formatted(assetId));
    }

    // ---------- requesting an upload ----------

    @Test
    void anOwnerGetsASignedUploadAndAPendingAsset() throws Exception {
        MvcResult result = requestUpload(owner, "image/jpeg", JPEG.length)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upload.method").value("PUT"))
                .andExpect(jsonPath("$.upload.url").value(org.hamcrest.Matchers.startsWith("https://fake-storage.test/")))
                .andExpect(jsonPath("$.upload.headers['Content-Type']").value("image/jpeg"))
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.assetId");

        MediaAsset asset = assets.findById(id).orElseThrow();
        assertThat(asset.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(asset.getHotelId()).isEqualTo(hotelId);
        assertThat(asset.getObjectKey()).startsWith("hotels/" + hotelId + "/menu/").endsWith(".jpg");
    }

    @Test
    void onlyJpegPngAndWebpAreAccepted() throws Exception {
        for (String bad : new String[] {"image/svg+xml", "application/pdf", "text/html", "image/gif"}) {
            requestUpload(owner, bad, 1000).andExpect(status().isBadRequest());
        }
        for (String good : new String[] {"image/jpeg", "image/png", "image/webp"}) {
            requestUpload(owner, good, 1000).andExpect(status().isCreated());
        }
    }

    @Test
    void emptyAndOversizedFilesAreRefused() throws Exception {
        requestUpload(owner, "image/jpeg", 0).andExpect(status().isBadRequest());
        requestUpload(owner, "image/jpeg", 5L * 1024 * 1024 + 1).andExpect(status().isPayloadTooLarge());
        requestUpload(owner, "image/jpeg", 5L * 1024 * 1024).andExpect(status().isCreated());
    }

    @Test
    void aWaiterCannotRequestAnUpload() throws Exception {
        requestUpload(waiter, "image/jpeg", 1000).andExpect(status().isForbidden());
        assertThat(assets.count()).isZero();
    }

    @Test
    void tooManyUnfinishedUploadsAreRefused() throws Exception {
        for (int i = 0; i < 100; i++) {
            assets.save(new MediaAsset(hotelId, MediaPurpose.MENU_ITEM, "hotels/%s/menu/p%d.jpg".formatted(hotelId, i),
                    "image/jpeg", 10, "u", Instant.now()));
        }
        requestUpload(owner, "image/jpeg", 1000).andExpect(status().isTooManyRequests());
    }

    // ---------- completing ----------

    @Test
    void completingBeforeUploadingIsRefusedAndKeepsTheAssetPending() throws Exception {
        String id = newAsset(owner, "image/jpeg", JPEG.length);
        complete(owner, id).andExpect(status().isConflict());
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.PENDING);
    }

    @Test
    void aValidUploadBecomesReadyAndCompletingTwiceIsFine() throws Exception {
        String id = newAsset(owner, "image/jpeg", JPEG.length);
        String key = keyOf(id);
        storage.put(key, "image/jpeg", JPEG);

        complete(owner, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.url").value("https://cdn.test/" + key));
        complete(owner, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.READY);
    }

    @Test
    void aFileThatIsNotReallyAnImageIsRejectedAndDeleted() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        String id = newAsset(owner, "image/jpeg", html.length);
        String key = keyOf(id);
        storage.put(key, "image/jpeg", html);

        complete(owner, id).andExpect(status().isBadRequest());
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.REJECTED);
        assertThat(storage.exists(key)).isFalse();
    }

    @Test
    void aFileOfTheWrongSizeOrTypeIsRejectedAndDeleted() throws Exception {
        String wrongSize = newAsset(owner, "image/jpeg", 100);
        storage.put(keyOf(wrongSize), "image/jpeg", JPEG);
        complete(owner, wrongSize).andExpect(status().isBadRequest());
        assertThat(storage.exists(keyOf(wrongSize))).isFalse();

        String wrongType = newAsset(owner, "image/png", JPEG.length);
        storage.put(keyOf(wrongType), "image/jpeg", JPEG);
        complete(owner, wrongType).andExpect(status().isBadRequest());
        assertThat(assets.findById(wrongType).orElseThrow().getStatus()).isEqualTo(MediaStatus.REJECTED);
    }

    @Test
    void anotherHotelCannotCompleteOrUseAnAssetItDoesNotOwn() throws Exception {
        String id = newAsset(owner, "image/jpeg", JPEG.length);
        storage.put(keyOf(id), "image/jpeg", JPEG);

        // the other hotel's owner, on their own hotel's route, with this hotel's asset id
        send(post(media(otherHotelId, "/" + id + "/complete")), otherOwner, null).andExpect(status().isNotFound());
        // and on this hotel's route
        send(post(media(hotelId, "/" + id + "/complete")), otherOwner, null).andExpect(status().isNotFound());
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.PENDING);
    }

    // ---------- attaching to a menu item ----------

    @Test
    void anAttachedImageShowsOnTheItemAndOnThePublicMenu() throws Exception {
        String id = readyAsset();
        String url = "https://cdn.test/" + keyOf(id);

        attach(owner, itemId, id).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(url));

        var item = publicMenu.menuFor(hotelId).categories().get(0).items().get(0);
        assertThat(item.imageUrl()).isEqualTo(url);
    }

    @Test
    void replacingTheImageDiscardsTheOldFile() throws Exception {
        String first = readyAsset();
        attach(owner, itemId, first).andExpect(status().isOk());
        String second = readyAsset();
        attach(owner, itemId, second).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://cdn.test/" + keyOf(second)));

        assertThat(assets.findById(first).orElseThrow().getStatus()).isEqualTo(MediaStatus.DELETED);
        assertThat(storage.exists(keyOf(first))).isFalse();
        assertThat(storage.exists(keyOf(second))).isTrue();
    }

    @Test
    void attachingTheSameImageAgainIsHarmlessButTwoItemsCannotShareOne() throws Exception {
        String other = items.save(new MenuItem(hotelId, categoryId, "Salad", null, 9000L, Diet.VEG, 1, Instant.now())).getId();
        String id = readyAsset();

        attach(owner, itemId, id).andExpect(status().isOk());
        attach(owner, itemId, id).andExpect(status().isOk());
        attach(owner, other, id).andExpect(status().isConflict());
    }

    @Test
    void anUnconfirmedOrForeignImageCannotBeAttached() throws Exception {
        String pending = newAsset(owner, "image/jpeg", JPEG.length);
        attach(owner, itemId, pending).andExpect(status().isConflict());

        MediaAsset foreign = assets.save(new MediaAsset(otherHotelId, MediaPurpose.MENU_ITEM,
                "hotels/" + otherHotelId + "/menu/x.jpg", "image/jpeg", 10, "u", Instant.now()));
        attach(owner, itemId, foreign.getId()).andExpect(status().isNotFound());
        attach(owner, itemId, "64b0000000000000000000aa").andExpect(status().isNotFound());
    }

    @Test
    void aWaiterCannotAttachAnImage() throws Exception {
        String id = readyAsset();
        attach(waiter, itemId, id).andExpect(status().isForbidden());
    }

    @Test
    void removingTheImageClearsTheItemAndDeletesTheFile() throws Exception {
        String id = readyAsset();
        attach(owner, itemId, id).andExpect(status().isOk());

        send(delete("/api/v1/hotels/" + hotelId + "/menu/items/" + itemId + "/image"), owner, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertThat(assets.findById(id).orElseThrow().getStatus()).isEqualTo(MediaStatus.DELETED);
        assertThat(storage.exists(keyOf(id))).isFalse();
    }

    // ---------- housekeeping ----------

    @Test
    void staleUnfinishedUploadsExpireAndFreshOnesDoNot() throws Exception {
        String staleKey = "hotels/" + hotelId + "/menu/stale.jpg";
        String staleId = assets.save(new MediaAsset(hotelId, MediaPurpose.MENU_ITEM, staleKey, "image/jpeg", 10, "u",
                Instant.now().minus(Duration.ofHours(3)))).getId();
        storage.put(staleKey, "image/jpeg", JPEG);
        String freshId = newAsset(owner, "image/jpeg", JPEG.length);

        assertThat(mediaService.expireStale()).isEqualTo(1);
        assertThat(assets.findById(staleId).orElseThrow().getStatus()).isEqualTo(MediaStatus.EXPIRED);
        assertThat(storage.exists(staleKey)).isFalse();
        assertThat(assets.findById(freshId).orElseThrow().getStatus()).isEqualTo(MediaStatus.PENDING);
    }
}