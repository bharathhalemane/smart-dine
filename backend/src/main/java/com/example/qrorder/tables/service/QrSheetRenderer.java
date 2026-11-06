package com.example.qrorder.tables.service;

import java.util.List;
import org.springframework.web.util.HtmlUtils;

/** Builds the printable page. Every dynamic value is HTML-escaped. */
final class QrSheetRenderer {

    record Card(String tableNumber, String section, String imageDataUri) {}

    private QrSheetRenderer() {}

    static String render(String hotelName, List<Card> cards) {
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="robots" content="noindex">
                <title>%s: table QR codes</title>
                <style>
                  @page { size: A4; margin: 12mm; }
                  body { font-family: Arial, Helvetica, sans-serif; margin: 0; color: #111; }
                  h2 { margin: 0 0 8mm; font-size: 14pt; }
                  .grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8mm; }
                  .card { border: 1px solid #bbb; border-radius: 4mm; padding: 6mm; text-align: center;
                          break-inside: avoid; page-break-inside: avoid; }
                  .card h1 { margin: 0; font-size: 26pt; }
                  .card .section { margin: 1mm 0 0; font-size: 11pt; color: #555; }
                  .card img { width: 62mm; height: 62mm; margin: 4mm 0; }
                  .card .hint { margin: 0; font-size: 12pt; }
                </style>
                </head>
                <body>
                <h2>%s</h2>
                <div class="grid">
                """.formatted(HtmlUtils.htmlEscape(hotelName), HtmlUtils.htmlEscape(hotelName)));

        for (Card card : cards) {
            String number = HtmlUtils.htmlEscape(card.tableNumber());
            html.append("<section class=\"card\">\n<h1>Table ").append(number).append("</h1>\n");
            if (card.section() != null) {
                html.append("<p class=\"section\">").append(HtmlUtils.htmlEscape(card.section())).append("</p>\n");
            }
            html.append("<img alt=\"QR code for table ").append(number).append("\" src=\"")
                    .append(HtmlUtils.htmlEscape(card.imageDataUri())).append("\">\n")
                    .append("<p class=\"hint\">Scan to see the menu and order</p>\n</section>\n");
        }
        return html.append("</div>\n</body>\n</html>\n").toString();
    }
}