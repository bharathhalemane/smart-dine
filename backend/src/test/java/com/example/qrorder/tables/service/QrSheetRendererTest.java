package com.example.qrorder.tables.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QrSheetRendererTest {

    @Test
    void rendersOneCardPerTable() {
        String html = QrSheetRenderer.render("Spice Garden", List.of(
                new QrSheetRenderer.Card("T1", "Terrace", "data:image/png;base64,AAAA"),
                new QrSheetRenderer.Card("T2", null, "data:image/png;base64,BBBB")));

        assertThat(html).contains("<title>Spice Garden: table QR codes</title>");
        assertThat(html.split("class=\"card\"", -1)).hasSize(3); // 2 cards
        assertThat(html).contains("Table T1").contains("Terrace").contains("Table T2");
    }

    @Test
    void escapesEveryDynamicValue() {
        String html = QrSheetRenderer.render("<script>alert('h')</script>", List.of(
                new QrSheetRenderer.Card("T<1>", "\"><img src=x onerror=alert(1)>", "data:image/png;base64,AAAA")));

        assertThat(html).doesNotContain("<script>alert").doesNotContain("<img src=x").doesNotContain("T<1>");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;img src=x");
    }
}