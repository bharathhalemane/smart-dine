package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.menu.model.Diet;
import com.example.qrorder.ordering.model.Order;
import com.example.qrorder.ordering.model.OrderLine;
import com.example.qrorder.ordering.model.OrderStatus;
import com.example.qrorder.ordering.model.OrderStatusChange;
import com.example.qrorder.ordering.repository.OrderRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.WaiterEvent;
import com.example.qrorder.tables.model.WaiterEventType;
import com.example.qrorder.tables.repository.DiningTableRepository;
import com.example.qrorder.tables.repository.TableAssignmentRepository;
import com.example.qrorder.tables.repository.WaiterEventRepository;
import com.example.qrorder.tables.repository.WaiterRequestRepository;
import com.example.qrorder.tenancy.model.Hotel;
import com.example.qrorder.tenancy.model.HotelMembership;
import com.example.qrorder.tenancy.model.HotelRole;
import com.example.qrorder.tenancy.repository.HotelMembershipRepository;
import com.example.qrorder.tenancy.repository.HotelRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class WaiterAuditIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired TableAssignmentRepository assignments;
    @Autowired WaiterRequestRepository requests;
    @Autowired WaiterEventRepository events;
    @Autowired OrderRepository orders;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;
    String t3;
    String history;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        assignments.deleteAll();
        requests.deleteAll();
        events.deleteAll();
        orders.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "w2@example.com", "Waiter Two", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);
        t1 = table("T1");
        t3 = table("T3");
        history = "/api/v1/hotels/" + hotelId + "/waiter-assignments/history";
    }

    // ---------- helpers ----------

    private void member(String hotel, String email, String name, HotelRole role) {
        User user = new User(email, encoder.encode(PASSWORD), name);
        user.setEmailVerified(true);
        user = users.save(user);
        memberships.save(new HotelMembership(hotel, user.getId(), Set.of(role), user.getId(), Instant.now()));
    }

    private String table(String number) {
        return tables.save(new DiningTable(hotelId, number, 4, null, Instant.now())).getId();
    }

    private String userId(String email) {
        return users.findAll().stream().filter(u -> u.getEmail().equals(email)).findFirst().orElseThrow().getId();
    }

    private String login(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String email, String json) throws Exception {
        if (email != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email));
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mvc.perform(request);
    }

    private String tableUrl(String tableId, String suffix) {
        return "/api/v1/hotels/" + hotelId + "/tables/" + tableId + "/waiter" + suffix;
    }

    private void assign(String tableId, String email) throws Exception {
        send(put(tableUrl(tableId, "")), "manager@example.com", "{\"userId\":\"" + userId(email) + "\"}")
                .andExpect(status().isOk());
    }

    private List<String> types(String query) throws Exception {
        String body = send(get(history + query), "manager@example.com", null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    private String readyOrder() {
        Order saved = orders.save(new Order(hotelId, "ds-1", t1, "T1", "cust-1", "Guest 1", "INR",
                List.of(new OrderLine("item-1", "Paneer Tikka", Diet.VEG, null, null, List.of(), 28000L, 2, 56000L,
                        null)),
                56000L, null, null, Instant.now().minus(2, ChronoUnit.HOURS)));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(saved.getId())),
                new Update().set("status", OrderStatus.READY).set("statusHistory", List.of(new OrderStatusChange(
                        OrderStatus.PREPARING, OrderStatus.READY, "STAFF", "someone", null, Instant.now()))),
                Order.class);
        return saved.getId();
    }

    // ---------- tests ----------

    @Test
    void everyAssignmentChangeIsLoggedNewestFirstWithWhoAndWhat() throws Exception {
        assign(t1, "w1@example.com");
        assign(t1, "w1@example.com"); // same waiter again: nothing changed, nothing logged
        assign(t1, "w2@example.com");
        send(delete(tableUrl(t1, "")), "manager@example.com", null).andExpect(status().isNoContent());
        send(delete(tableUrl(t1, "")), "manager@example.com", null).andExpect(status().isNoContent()); // nothing left
        send(post(tableUrl(t1, "/claim")), "w1@example.com", null).andExpect(status().isOk());
        send(post(tableUrl(t1, "/release")), "w1@example.com", null).andExpect(status().isNoContent());
        send(post(tableUrl(t3, "/claim")), "w2@example.com", null).andExpect(status().isOk());
        HotelMembership w2 = memberships.findByHotelIdAndUserId(hotelId, userId("w2@example.com")).orElseThrow();
        w2.setRoles(Set.of(HotelRole.CASHIER));
        memberships.save(w2);
        send(post(tableUrl(t3, "/claim")), "w1@example.com", null).andExpect(status().isOk());

        assertThat(types("")).containsExactly("TAKEN_OVER", "CLAIMED", "RELEASED", "CLAIMED", "UNASSIGNED",
                "ASSIGNED", "ASSIGNED");

        send(get(history), "manager@example.com", null)
                .andExpect(jsonPath("$[0].tableNumber").value("T3"))
                .andExpect(jsonPath("$[0].actorName").value("Waiter One"))
                .andExpect(jsonPath("$[0].fromName").value("Waiter Two"))
                .andExpect(jsonPath("$[0].toName").value("Waiter One"))
                .andExpect(jsonPath("$[4].type").value("UNASSIGNED"))
                .andExpect(jsonPath("$[4].fromName").value("Waiter Two"))
                .andExpect(jsonPath("$[5].fromName").value("Waiter One"))
                .andExpect(jsonPath("$[5].toName").value("Waiter Two"))
                .andExpect(jsonPath("$[6].actorName").value("Manager Meera"))
                .andExpect(jsonPath("$[6].fromUserId").doesNotExist())
                .andExpect(jsonPath("$[6].toName").value("Waiter One"))
                .andExpect(jsonPath("$[6].at").isNotEmpty());
    }

    @Test
    void pickupsDropsAndRequestsAreLoggedToo() throws Exception {
        String orderId = readyOrder();
        String base = "/api/v1/hotels/" + hotelId;

        send(post(base + "/waiter/orders/" + orderId + "/pickup"), "w1@example.com", null).andExpect(status().isOk());
        send(post(base + "/waiter/orders/" + orderId + "/drop"), "w1@example.com", null)
                .andExpect(status().isNoContent());

        assign(t1, "w1@example.com");
        MvcResult help = send(post(tableUrl(t3, "/requests")), "w1@example.com", "{\"kind\":\"HELP\"}")
                .andExpect(status().isCreated()).andReturn();
        String helpId = JsonPath.read(help.getResponse().getContentAsString(), "$.id");
        send(post(base + "/waiter-requests/" + helpId + "/respond"), "w2@example.com", null)
                .andExpect(status().isOk());

        MvcResult handOver = send(post(tableUrl(t1, "/requests")), "w1@example.com",
                "{\"kind\":\"REASSIGN\",\"toUserId\":\"" + userId("w2@example.com") + "\"}")
                .andExpect(status().isCreated()).andReturn();
        String handOverId = JsonPath.read(handOver.getResponse().getContentAsString(), "$.id");
        send(post(base + "/waiter-requests/" + handOverId + "/approve"), "manager@example.com", null)
                .andExpect(status().isOk());

        MvcResult declined = send(post(tableUrl(t1, "/requests")), "w2@example.com",
                "{\"kind\":\"REASSIGN\"}").andExpect(status().isCreated()).andReturn();
        send(post(base + "/waiter-requests/" + JsonPath.read(declined.getResponse().getContentAsString(), "$.id")
                + "/decline"), "manager@example.com", "{\"reason\":\"busy\"}").andExpect(status().isOk());

        MvcResult cancelled = send(post(tableUrl(t3, "/requests")), "w2@example.com", "{\"kind\":\"HELP\"}")
                .andExpect(status().isCreated()).andReturn();
        send(post(base + "/waiter-requests/" + JsonPath.read(cancelled.getResponse().getContentAsString(), "$.id")
                + "/cancel"), "w2@example.com", null).andExpect(status().isOk());

        assertThat(types("")).containsExactly("REQUEST_CANCELLED", "HELP_REQUESTED", "REASSIGN_DECLINED",
                "REASSIGN_REQUESTED", "REASSIGN_APPROVED", "REASSIGN_REQUESTED", "HELP_ACCEPTED", "HELP_REQUESTED",
                "ASSIGNED", "DROPPED", "PICKED_UP");

        send(get(history), "manager@example.com", null)
                .andExpect(jsonPath("$[2].note").value("busy"))
                .andExpect(jsonPath("$[4].fromName").value("Waiter One"))
                .andExpect(jsonPath("$[4].toName").value("Waiter Two"))
                .andExpect(jsonPath("$[4].actorName").value("Manager Meera"))
                .andExpect(jsonPath("$[6].actorName").value("Waiter Two"))
                .andExpect(jsonPath("$[9].type").value("DROPPED"))
                .andExpect(jsonPath("$[9].orderId").value(orderId))
                .andExpect(jsonPath("$[9].fromName").value("Waiter One"))
                .andExpect(jsonPath("$[10].type").value("PICKED_UP"))
                .andExpect(jsonPath("$[10].orderId").value(orderId))
                .andExpect(jsonPath("$[10].toName").value("Waiter One"));
    }

    @Test
    void theLogCanBeFilteredByTableAndLimited() throws Exception {
        assign(t1, "w1@example.com");
        assign(t3, "w2@example.com");
        assign(t1, "w2@example.com");

        assertThat(types("")).hasSize(3);
        assertThat(types("?tableId=" + t1)).containsExactly("ASSIGNED", "ASSIGNED");
        assertThat(types("?tableId=" + t3)).containsExactly("ASSIGNED");
        assertThat(types("?limit=2")).hasSize(2);
        assertThat(types("?tableId=no-such-table")).isEmpty();

        send(get(history + "?limit=0"), "manager@example.com", null).andExpect(status().isBadRequest());
        send(get(history + "?limit=101"), "manager@example.com", null).andExpect(status().isBadRequest());
    }

    @Test
    void onlyManagersReadTheLogAndNeverAcrossHotels() throws Exception {
        assign(t1, "w1@example.com");
        events.save(new WaiterEvent(otherHotelId, WaiterEventType.ASSIGNED, "x", "T9", null, "u", null, "u", null,
                Instant.now()));

        send(get(history), "w1@example.com", null).andExpect(status().isForbidden());
        send(get(history), "cook@example.com", null).andExpect(status().isForbidden());
        send(get(history), null, null).andExpect(status().isUnauthorized());
        send(get(history), "otherwaiter@example.com", null).andExpect(status().isNotFound()); // not a member here

        assertThat(types("")).containsExactly("ASSIGNED"); // the other hotel's event is not shown
    }
}