package com.example.qrorder.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.admin.model.AdminAuditAction;
import com.example.qrorder.admin.repository.AdminAuditRepository;
import com.example.qrorder.identity.model.PlatformRole;
import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.payout.gateway.RouteGateway;
import com.example.qrorder.payout.model.HotelPayoutAccount;
import com.example.qrorder.payout.model.PayoutTransfer;
import com.example.qrorder.payout.model.TransferStatus;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {
        "app.payments.platform.razorpay.key-id=rzp_test_platform",
        "app.payments.platform.razorpay.key-secret=platform-secret-123456",
        "app.payments.platform.razorpay.webhook-secret=platform-whsec-123456"})
class PayoutApiIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @MockitoBean RouteGateway gateway;
    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired AdminAuditRepository audit;
    @Autowired PasswordEncoder encoder;

    String hotelA;
    String hotelB;
    String admin;
    String ownerA;
    PayoutTransfer failedA;
    PayoutTransfer reviewA;
    PayoutTransfer sentB;

    @BeforeEach
    void seed() throws Exception {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        audit.deleteAll();
        mongo.remove(new Query(), PayoutTransfer.class);
        mongo.remove(new Query(), HotelPayoutAccount.class);
        Instant now = Instant.now();

        User adminUser = account("admin@example.com");
        adminUser.setPlatformRole(PlatformRole.PLATFORM_ADMIN);
        users.save(adminUser);
        User oa = users.save(account("owner-a@example.com"));
        User ob = users.save(account("owner-b@example.com"));
        hotelA = hotels.save(new Hotel("api-a", "Hotel A", oa.getId(), null, "Asia/Kolkata", "INR", now)).getId();
        hotelB = hotels.save(new Hotel("api-b", "Hotel B", ob.getId(), null, "Asia/Kolkata", "INR", now)).getId();
        memberships.save(new HotelMembership(hotelA, oa.getId(), Set.of(HotelRole.OWNER), oa.getId(), now));
        memberships.save(new HotelMembership(hotelB, ob.getId(), Set.of(HotelRole.OWNER), ob.getId(), now));
        mongo.insert(new Document("hotelId", hotelA).append("linkedAccountId", "acc_hotelAAAA1"), "payoutAccounts");

        failedA = mongo.insert(new PayoutTransfer(hotelA, "pa1", "pay_pa1", 5_000, "INR", TransferStatus.FAILED, "x", now));
        reviewA = mongo.insert(new PayoutTransfer(hotelA, "pa2", "pay_pa2", 6_000, "INR", TransferStatus.NEEDS_REVIEW, "x", now));
        sentB = mongo.insert(new PayoutTransfer(hotelB, "pb1", "pay_pb1", 7_000, "INR", TransferStatus.SENT, "x", now));

        admin = login("admin@example.com");
        ownerA = login("owner-a@example.com");
    }

    private User account(String email) {
        User user = new User(email, encoder.encode(PASSWORD), email);
        user.setEmailVerified(true);
        return user;
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions getAs(String token, String url) throws Exception {
        var request = get(url);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private ResultActions retry(String token, String id, String json) throws Exception {
        var request = post("/api/v1/admin/payouts/" + id + "/retry").contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
    }

    @Test
    void anOwnerSeesOnlyTheirOwnHotelsTransfers() throws Exception {
        getAs(ownerA, "/api/v1/hotels/" + hotelA + "/payouts").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        getAs(ownerA, "/api/v1/hotels/" + hotelB + "/payouts").andExpect(status().isNotFound());
        getAs(null, "/api/v1/hotels/" + hotelA + "/payouts").andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminSeesEveryHotelAndCanFilterByStatus() throws Exception {
        getAs(admin, "/api/v1/admin/payouts").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        getAs(admin, "/api/v1/admin/payouts?status=NEEDS_REVIEW").andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(reviewA.getId()));
        getAs(ownerA, "/api/v1/admin/payouts").andExpect(status().isForbidden());
    }

    @Test
    void retryingAFailedTransferSendsItAndIsAudited() throws Exception {
        when(gateway.transfer(any(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new RouteGateway.Result("trf_again", "created"));

        retry(admin, failedA.getId(), "{\"reason\":\"Linked account fixed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));

        assertThat(audit.findAll()).hasSize(1);
        assertThat(audit.findAll().get(0).getAction()).isEqualTo(AdminAuditAction.PAYOUT_RETRIED);
    }

    @Test
    void aTransferThatMaybeWasSentNeedsAnExplicitConfirmation() throws Exception {
        when(gateway.transfer(any(), anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(new RouteGateway.Result("trf_again", "created"));

        retry(admin, reviewA.getId(), "{\"reason\":\"Checked the dashboard\"}").andExpect(status().isBadRequest());
        assertThat(mongo.findById(reviewA.getId(), PayoutTransfer.class).getStatus()).isEqualTo(TransferStatus.NEEDS_REVIEW);

        retry(admin, reviewA.getId(), "{\"reason\":\"Checked the dashboard\",\"confirmNotSent\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void onlyFailedOrReviewTransfersCanBeRetriedAndOnlyByAnAdmin() throws Exception {
        retry(admin, sentB.getId(), "{\"reason\":\"Just trying\"}").andExpect(status().isConflict());
        retry(ownerA, failedA.getId(), "{\"reason\":\"Just trying\"}").andExpect(status().isForbidden());
        retry(null, failedA.getId(), "{\"reason\":\"Just trying\"}").andExpect(status().isUnauthorized());
        assertThat(audit.count()).isZero();
    }
}