package com.textbin.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Service
public class QrCodeService {

    /**
     * Generates a high-quality PNG QR code byte array for the specified text.
     *
     * @param text   the URL or payload to encode
     * @param width  image width in pixels
     * @param height image height in pixels
     * @return raw PNG byte array
     * @throws IOException if rendering fails
     */
    public byte[] generateQrCodePng(String text, int width, int height) throws IOException {
        try {
            int w = Math.max(100, Math.min(width, 1000));
            int h = Math.max(100, Math.min(height, 1000));

            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, w, h, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new IOException("Failed to generate QR code: " + e.getMessage(), e);
        }
    }
}
