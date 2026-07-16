package com.heapdump.analyzer.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * QR 코드 생성 유틸리티 (zxing).
 * OTP Seed 등록 페이지에서 otpauth:// URI 를 서버사이드 PNG data URI 로 렌더링.
 * (별도 GET 엔드포인트 없이 페이지 인라인 — seed 노출 면 최소화)
 */
public final class QrCodeUtil {

    private QrCodeUtil() {
    }

    /** 내용을 QR PNG 로 인코딩해 data:image/png;base64,... 문자열로 반환 */
    public static String toPngDataUri(String content, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("QR 코드 생성 실패", e);
        }
    }
}
