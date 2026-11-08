package com.example.qrorder.tables.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.qrorder.identity.model.User;
import com.example.qrorder.identity.repository.UserRepository;
import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tables.model.DiningTable;
import com.example.qrorder.tables.model.RequestStatus;
import com.example.qrorder.tables.model.TableAssignment;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

class WaiterRequestIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired HotelRepository hotels;
    @Autowired HotelMembershipRepository memberships;
    @Autowired DiningTableRepository tables;
    @Autowired TableAssignmentRepository assignments;
    @Autowired WaiterRequestRepository requests;
    @Autowired WaiterEventRepository events;
    @Autowired PasswordEncoder encoder;

    String hotelId;
    String otherHotelId;
    String t1;
    String t3;
    String base;

    @BeforeEach
    void seed() {
        users.deleteAll();
        memberships.deleteAll();
        hotels.deleteAll();
        tables.deleteAll();
        assignments.deleteAll();
        requests.deleteAll();
        events.deleteAll();
        hotelId = hotels.save(new Hotel("spice-garden", "Spice Garden", "x", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        otherHotelId = hotels.save(new Hotel("other-place", "Other Place", "y", null, "Asia/Kolkata", "INR",
                Instant.now())).getId();
        member(hotelId, "manager@example.com", "Manager Meera", HotelRole.MANAGER);
        member(hotelId, "w1@example.com", "Waiter One", HotelRole.WAITER);
        member(hotelId, "w2@example.com", "Waiter Two", HotelRole.WAITER);
        member(hotelId, "cook@example.com", "Cook Ravi", HotelRole.KITCHEN);
        member(hotelId, "cashier@example.com", "Cashier Chitra", HotelRole.CASHIER);
        member(otherHotelId, "otherwaiter@example.com", "Other Waiter", HotelRole.WAITER);
        t1 = table("T1");
        t3 = table("T3");
        String w1 = userId("w1@example.com");
        assignments.save(new TableAssignment(hotelId, t1, w1, w1, Instant.now())); // T3 stays unassigned
        base = "/api/v1/hotels/" + hotelId + "/waiter-requests";
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

    private String raiseUrl(String tableId) {
        return "/api/v1/hotels/" + hotelId + "/tables/" + tableId + "/waiter/requests";
    }

    /** Raises a request and returns its id (must be created). */
    private String raise(String email, String tableId, String json) throws Exception {
        MvcResult result = send(post(raiseUrl(tableId)), email, json).andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private String help(String email, String tableId) throws Exception {
        return raise(email, tableId, "{\"kind\":\"HELP\",\"note\":\"Big party\"}");
    }

    private String handOver(String email, String tableId, String toUserId) throws Exception {
        String json = toUserId == null ? "{\"kind\":\"REASSIGN\"}"
                : "{\"kind\":\"REASSIGN\",\"toUserId\":\"%s\"}".formatted(toUserId);
        return raise(email, tableId, json);
    }

    private ResultActions action(String email, String requestId, String action, String json) throws Exception {
        return send(post(base + "/" + requestId + "/" + action), email, json);
    }

    private RequestStatus statusOf(String requestId) {
        return requests.findById(requestId).orElseThrow().getStatus();
    }

    private String holderOf(String tableId) {
        return assignments.findByTableId(tableId).map(TableAssignment::getWaiterUserId).orElse(null);
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- raising ----------

    @Test
    void aWaiterRaisesAHelpRequestAndRepeatingItReturnsTheSameOne() throws Exception {
        MvcResult first = send(post(raiseUrl(t3)), "w1@example.com", "{\"kind\":\"HELP\",\"note\":\"  Big party \"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("HELP"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.tableNumber").value("T3"))
                .andExpect(jsonPath("$.requestedByName").value("Waiter One"))
                .andExpect(jsonPath("$.note").value("Big party"))
                .andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        send(post(raiseUrl(t3)), "w1@example.com", "{\"kind\":\"HELP\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void raisingChecksTheInputAndWhoMayDoIt() throws Exception {
        send(post(raiseUrl(t3)), "w1@example.com", "{\"note\":\"no kind\"}").andExpect(status().isBadRequest());
        send(post(raiseUrl(t3)), "w1@example.com", "{\"kind\":\"HELP\",\"note\":\"" + "x".repeat(201) + "\"}")
                .andExpect(status().isBadRequest());
        send(post(raiseUrl(t3)), "w1@example.com",
                "{\"kind\":\"HELP\",\"toUserId\":\"" + userId("w2@example.com") + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        send(post(raiseUrl("no-such-table")), "w1@example.com", "{\"kind\":\"HELP\"}")
                .andExpect(status().isNotFound());
        send(post(raiseUrl(t3)), "cook@example.com", "{\"kind\":\"HELP\"}").andExpect(status().isForbidden());
        send(post(raiseUrl(t3)), "cashier@example.com", "{\"kind\":\"HELP\"}").andExpect(status().isForbidden());
        send(post(raiseUrl(t3)), null, "{\"kind\":\"HELP\"}").andExpect(status().isUnauthorized());
        send(post(raiseUrl(t3)), "otherwaiter@example.com", "{\"kind\":\"HELP\"}")
                .andExpect(status().isNotFound()); // not a member here
        assertThat(requests.count()).isZero();
    }

    @Test
    void aHandOverCanOnlyBeRequestedForYourOwnTableToSomeoneWhoCanServe() throws Exception {
        // T3 has nobody, T1 is w1's
        send(post(raiseUrl(t3)), "w1@example.com", "{\"kind\":\"REASSIGN\"}").andExpect(status().isConflict());
        send(post(raiseUrl(t1)), "w2@example.com", "{\"kind\":\"REASSIGN\"}").andExpect(status().isConflict());
        send(post(raiseUrl(t1)), "w1@example.com", "{\"kind\":\"REASSIGN\",\"toUserId\":\"" + userId("cook@example.com")
                + "\"}").andExpect(status().isBadRequest());
        send(post(raiseUrl(t1)), "w1@example.com", "{\"kind\":\"REASSIGN\",\"toUserId\":\"" + userId("w1@example.com")
                + "\"}").andExpect(status().isBadRequest());
        send(post(raiseUrl(t1)), "w1@example.com", "{\"kind\":\"REASSIGN\",\"toUserId\":\"no-such-user\"}")
                .andExpect(status().isBadRequest());
        assertThat(requests.count()).isZero();

        send(post(raiseUrl(t1)), "w1@example.com", "{\"kind\":\"REASSIGN\",\"toUserId\":\""
                + userId("w2@example.com") + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetName").value("Waiter Two"));
    }

    // ---------- lists ----------

    @Test
    void managersSeeEveryOpenRequestAndWaitersOnlyHelpRequestsAndTheirOwnHandOvers() throws Exception {
        handOver("w1@example.com", t1, null);
        help("w2@example.com", t3);

        send(get(base), "manager@example.com", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("REASSIGN"))
                .andExpect(jsonPath("$[1].kind").value("HELP"));
        send(get(base), "w1@example.com", null).andExpect(jsonPath("$.length()").value(2));
        send(get(base), "w2@example.com", null)
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kind").value("HELP"));
        send(get(base), "cook@example.com", null).andExpect(status().isForbidden());
        send(get(base), null, null).andExpect(status().isUnauthorized());
    }

    @Test
    void mineShowsMyRequestsInEveryStateNewestFirst() throws Exception {
        String first = help("w1@example.com", t3);
        action("w1@example.com", first, "cancel", null).andExpect(status().isOk());
        String second = help("w1@example.com", t3);
        help("w2@example.com", t3);

        send(get(base + "/mine"), "w1@example.com", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[1].id").value(first))
                .andExpect(jsonPath("$[1].status").value("CANCELLED"));
        send(get(base + "/mine"), "cook@example.com", null).andExpect(status().isForbidden());
    }

    // ---------- help ----------

    @Test
    void aColleagueAcceptsAHelpRequestOnceAndNotTheRequesterThemself() throws Exception {
        String id = help("w1@example.com", t3);

        action("w1@example.com", id, "respond", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        action("cook@example.com", id, "respond", null).andExpect(status().isForbidden());

        action("w2@example.com", id, "respond", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.decidedByName").value("Waiter Two"));
        action("w2@example.com", id, "respond", null).andExpect(status().isOk()); // repeat: same answer
        action("manager@example.com", id, "respond", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Someone else is already helping"));

        send(get(base), "w2@example.com", null).andExpect(jsonPath("$.length()").value(0)); // no longer open
        action("w2@example.com", "no-such-request", "respond", null).andExpect(status().isNotFound());
    }

    @Test
    void twoPeopleAcceptingTheSameHelpRequestAtOnceExactlyOneWins() throws Exception {
        String manager = login("manager@example.com");
        String w2 = login("w2@example.com");

        for (int round = 0; round < 10; round++) {
            String id = help("w1@example.com", t3);
            String url = base + "/" + id + "/respond";
            List<Callable<Integer>> tasks = List.of(
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + manager))
                            .andReturn().getResponse().getStatus(),
                    () -> mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + w2))
                            .andReturn().getResponse().getStatus());

            assertThat(runTogether(tasks)).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(statusOf(id)).isEqualTo(RequestStatus.ACCEPTED);
        }
    }

    @Test
    void aHandOverRequestIsNotAnsweredLikeAHelpRequest() throws Exception {
        String id = handOver("w1@example.com", t1, null);
        action("w2@example.com", id, "respond", null).andExpect(status().isConflict());
        String help = help("w1@example.com", t3);
        action("manager@example.com", help, "approve", null).andExpect(status().isConflict());
        action("manager@example.com", help, "decline", null).andExpect(status().isConflict());
        assertThat(statusOf(id)).isEqualTo(RequestStatus.OPEN);
        assertThat(statusOf(help)).isEqualTo(RequestStatus.OPEN);
    }

    // ---------- cancel ----------

    @Test
    void theRequesterOrAManagerCancelsAndAfterwardsANewOneCanBeRaised() throws Exception {
        String id = help("w1@example.com", t3);

        action("w2@example.com", id, "cancel", null).andExpect(status().isForbidden());
        action("w1@example.com", id, "cancel", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        action("w1@example.com", id, "cancel", null).andExpect(status().isOk()); // repeat
        action("w2@example.com", id, "respond", null).andExpect(status().isConflict()); // nothing to answer

        String again = help("w1@example.com", t3);
        assertThat(again).isNotEqualTo(id);
        action("manager@example.com", again, "cancel", null).andExpect(status().isOk());

        String accepted = help("w1@example.com", t3);
        action("w2@example.com", accepted, "respond", null).andExpect(status().isOk());
        action("w1@example.com", accepted, "cancel", null).andExpect(status().isConflict()); // already answered
    }

    // ---------- hand-over ----------

    @Test
    void aManagerApprovesAHandOverAndTheTableMovesOrOverridesTheNamedColleague() throws Exception {
        String w1 = userId("w1@example.com");
        String w2 = userId("w2@example.com");
        String manager = userId("manager@example.com");

        String named = handOver("w1@example.com", t1, w2);
        action("w2@example.com", named, "approve", null).andExpect(status().isForbidden());
        assertThat(holderOf(t1)).isEqualTo(w1);

        action("manager@example.com", named, "approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.assignedToName").value("Waiter Two"))
                .andExpect(jsonPath("$.decidedByName").value("Manager Meera"));
        assertThat(holderOf(t1)).isEqualTo(w2);
        action("manager@example.com", named, "approve", null).andExpect(status().isOk()); // repeat: same answer
        assertThat(holderOf(t1)).isEqualTo(w2);

        // now w2 asks, naming w1, and the manager sends the table to themself instead
        String overridden = handOver("w2@example.com", t1, w1);
        action("manager@example.com", overridden, "approve", "{\"toUserId\":\"" + manager + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.assignedToUserId").value(manager));
        assertThat(holderOf(t1)).isEqualTo(manager);
        assertThat(assignments.findByTableId(t1).orElseThrow().getAssignedByUserId()).isEqualTo(manager);
    }

    @Test
    void anApprovalNeedsSomeoneWhoCanServeAndLeavesTheRequestOpenOtherwise() throws Exception {
        String id = handOver("w1@example.com", t1, null);

        action("manager@example.com", id, "approve", null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        action("manager@example.com", id, "approve", "{\"toUserId\":\"" + userId("cook@example.com") + "\"}")
                .andExpect(status().isBadRequest());
        action("manager@example.com", id, "approve", "{\"toUserId\":\"" + userId("w1@example.com") + "\"}")
                .andExpect(status().isBadRequest());

        assertThat(statusOf(id)).isEqualTo(RequestStatus.OPEN);
        assertThat(holderOf(t1)).isEqualTo(userId("w1@example.com"));
    }

    @Test
    void ifTheTableChangedHandsMeanwhileTheApprovalIsRefusedAndTheRequestClosed() throws Exception {
        String id = handOver("w1@example.com", t1, userId("w2@example.com"));
        // a manager reassigns the table by hand before getting to the request
        send(put("/api/v1/hotels/" + hotelId + "/tables/" + t1 + "/waiter"), "manager@example.com",
                "{\"userId\":\"" + userId("manager@example.com") + "\"}").andExpect(status().isOk());

        action("manager@example.com", id, "approve", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This table is no longer assigned to the person who asked"));

        assertThat(statusOf(id)).isEqualTo(RequestStatus.VOID);
        assertThat(holderOf(t1)).isEqualTo(userId("manager@example.com"));
    }

    @Test
    void aManagerCanDeclineAHandOverWithAReason() throws Exception {
        String id = handOver("w1@example.com", t1, null);

        action("w2@example.com", id, "decline", null).andExpect(status().isForbidden());
        action("manager@example.com", id, "decline", "{\"reason\":\"  We are short staffed  \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decisionNote").value("We are short staffed"));
        action("manager@example.com", id, "decline", null).andExpect(status().isOk()); // repeat
        action("manager@example.com", id, "approve", "{\"toUserId\":\"" + userId("w2@example.com") + "\"}")
                .andExpect(status().isConflict());

        assertThat(holderOf(t1)).isEqualTo(userId("w1@example.com"));
        send(get(base + "/mine"), "w1@example.com", null)
                .andExpect(jsonPath("$[0].status").value("DECLINED"));
    }
}