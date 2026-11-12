package com.example.qrorder.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import com.example.qrorder.support.AbstractIntegrationTest;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureObservability
@TestPropertySource(properties = "management.endpoints.web.exposure.include=health,info,prometheus")
class BusinessMetricsIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MongoTemplate mongo;

    @Test
    void theMetricsEndpointShowsTheBusinessGaugesAndNeedsNoLogin() throws Exception {
        mongo.getCollection("diningSessions").insertOne(new Document("active", true)
                .append("tableId", UUID.randomUUID().toString()).append("hotelId", "h-metrics"));

        String body = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Matcher open = Pattern.compile("(?m)^qrorder_dining_sessions_open(\\{[^}]*})? ([0-9.eE+-]+)$").matcher(body);
        assertThat(open.find()).as("open sessions gauge present").isTrue();
        assertThat(Double.parseDouble(open.group(2))).isGreaterThanOrEqualTo(1.0);
        assertThat(body).contains("qrorder_orders_awaiting_payment");
        assertThat(body).contains("jvm_memory_used_bytes");
    }

    @Test
    void healthStaysPublicAndReportsUp() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    }
}