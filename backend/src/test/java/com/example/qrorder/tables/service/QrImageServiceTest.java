package com.example.qrorder.tables.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class QrImageServiceTest {

    private final QrImageService service = new QrImageService();

    private static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    @Test
    void theImageDecodesBackToTheExactUrl() throws Exception {
        String url = "http://localhost:5173/t/Zk3_x-9QpLmN0aBcDeFgHiJkLmNoPqRsTuVwXyZ0123";

        assertThat(decode(service.png(url, 480))).isEqualTo(url);
    }

    @Test
    void producesAPngAtTheRequestedSize() throws Exception {
        byte[] png = service.png("https://example.com/t/abc", 300);

        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image.getWidth()).isGreaterThanOrEqualTo(300);
        assertThat(image.getWidth()).isEqualTo(image.getHeight());
    }

    @Test
    void dataUriHasTheRightPrefixAndBlankContentIsRejected() {
        assertThat(service.pngDataUri("https://example.com/t/abc")).startsWith("data:image/png;base64,");
        assertThatThrownBy(() -> service.png("  ", 300)).isInstanceOf(IllegalArgumentException.class);
    }
}