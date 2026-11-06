package com.example.qrorder.dining.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStatusTest {

    @Test
    void openCanRequestTheBillOrClose() {
        assertThat(SessionStatus.OPEN.canTransitionTo(SessionStatus.BILL_REQUESTED)).isTrue();
        assertThat(SessionStatus.OPEN.canTransitionTo(SessionStatus.CLOSED)).isTrue();
    }

    @Test
    void aRequestedBillCanBeReopenedOrClosed() {
        assertThat(SessionStatus.BILL_REQUESTED.canTransitionTo(SessionStatus.OPEN)).isTrue();
        assertThat(SessionStatus.BILL_REQUESTED.canTransitionTo(SessionStatus.CLOSED)).isTrue();
    }

    @Test
    void closedIsTerminalAndNothingMovesToItself() {
        for (SessionStatus next : SessionStatus.values()) {
            assertThat(SessionStatus.CLOSED.canTransitionTo(next)).as("CLOSED -> " + next).isFalse();
        }
        for (SessionStatus status : SessionStatus.values()) {
            assertThat(status.canTransitionTo(status)).as(status + " -> itself").isFalse();
        }
    }

    @Test
    void tableStateIsDerivedFromTheSession() {
        assertThat(TableState.of(null)).isEqualTo(TableState.FREE);
        assertThat(TableState.of(SessionStatus.OPEN)).isEqualTo(TableState.OCCUPIED);
        assertThat(TableState.of(SessionStatus.BILL_REQUESTED)).isEqualTo(TableState.BILL_REQUESTED);
        assertThat(TableState.of(SessionStatus.CLOSED)).isEqualTo(TableState.FREE);
    }
}