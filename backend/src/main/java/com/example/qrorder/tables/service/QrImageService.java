package com.example.qrorder.tables.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QrImageService {

    static final int DEFAULT_SIZE_PX = 480;

    public byte[] png(String content, int sizePx) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("QR content must not be blank");
        }
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, // survives scuffs on a printed sticker
                    EncodeHintType.MARGIN, 2)); // quiet zone, in modules
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("Could not render QR code", e);
        }
    }

    public String pngDataUri(String content) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png(content, DEFAULT_SIZE_PX));
    }
}