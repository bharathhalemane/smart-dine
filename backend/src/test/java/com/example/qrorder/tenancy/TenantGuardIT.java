package com.example.qrorder.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.qrorder.support.AbstractIntegrationTest;
import com.example.qrorder.tenancy.context.CurrentTenant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Scans every route in the application. A route containing {hotelId} MUST resolve its tenant through
 * @CurrentTenant (the membership check), and @CurrentTenant is only valid on a {hotelId} route.
 * New modules are covered automatically: forgetting the tenant gate fails the build.
 */
class TenantGuardIT extends AbstractIntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private static boolean isHotelScoped(RequestMappingInfo info) {
        Set<String> patterns = info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
        return patterns.stream().anyMatch(p -> p.contains("{hotelId}"));
    }

    private static boolean usesTenantGate(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .anyMatch(p -> p.hasParameterAnnotation(CurrentTenant.class));
    }

    @Test
    void everyHotelScopedRouteUsesTheTenantGate() {
        List<String> unguarded = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (isHotelScoped(info) && !usesTenantGate(method)) {
                unguarded.add(info + " -> " + method);
            }
        });

        assertThat(unguarded).as("hotel-scoped routes without @CurrentTenant").isEmpty();
    }

    @Test
    void theTenantGateIsOnlyUsedOnHotelScopedRoutes() {
        List<String> misplaced = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (usesTenantGate(method) && !isHotelScoped(info)) {
                misplaced.add(info + " -> " + method);
            }
        });

        assertThat(misplaced).as("@CurrentTenant on routes without {hotelId}").isEmpty();
    }
}