package com.example.qrorder.billing.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UpiLinksTest {

    @Test
    void buildsTheStandardLinkWithTheAmountInRupees() {
        assertThat(UpiLinks.build("shop@okbank", "Spice Garden", "INR", 115_503, "Table T1"))
                .isEqualTo("upi://pay?pa=shop@okbank&pn=Spice%20Garden&am=1155.03&cu=INR&tn=Table%20T1");
    }

    @Test
    void formatsPaiseAsTwoDecimals() {
        assertThat(UpiLinks.rupees(5)).isEqualTo("0.05");
        assertThat(UpiLinks.rupees(100)).isEqualTo("1.00");
        assertThat(UpiLinks.rupees(27_000)).isEqualTo("270.00");
        assertThat(UpiLinks.rupees(99)).isEqualTo("0.99");
        assertThat(UpiLinks.rupees(1_000_001)).isEqualTo("10000.01");
    }

    @Test
    void escapesCharactersThatWouldBreakTheLink() {
        String link = UpiLinks.build("shop@okbank", "Tom & Jerry's Café", "INR", 100, "Table #4");

        assertThat(link).contains("pn=Tom%20%26%20Jerry%27s%20Caf%C3%A9");
        assertThat(link).contains("tn=Table%20%234");
        assertThat(link).doesNotContain(" ");
    }

    @Test
    void aZeroAmountIsLeftOutSoThePayerTypesIt() {
        assertThat(UpiLinks.build("shop@okbank", "Spice Garden", "INR", 0, "Table T1"))
                .isEqualTo("upi://pay?pa=shop@okbank&pn=Spice%20Garden&cu=INR&tn=Table%20T1");
    }

    @Test
    void noLinkWithoutDetailsOrForOtherCurrencies() {
        assertThat(UpiLinks.build("shop@okbank", "Spice Garden", "USD", 1000, "x")).isNull();
        assertThat(UpiLinks.build(null, "Spice Garden", "INR", 1000, "x")).isNull();
        assertThat(UpiLinks.build("shop@okbank", " ", "INR", 1000, "x")).isNull();
    }

    @Test
    void aMissingNoteIsSimplyOmitted() {
        assertThat(UpiLinks.build("shop@okbank", "Spice Garden", "INR", 1000, null))
                .isEqualTo("upi://pay?pa=shop@okbank&pn=Spice%20Garden&am=10.00&cu=INR");
    }
}