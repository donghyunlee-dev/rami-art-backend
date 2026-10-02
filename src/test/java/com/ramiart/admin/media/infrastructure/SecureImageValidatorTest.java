package com.ramiart.admin.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ramiart.admin.media.application.MediaException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SecureImageValidatorTest {
    private final SecureImageValidator validator = new SecureImageValidator();

    @Test
    void decodesAndReencodesPngWithoutMetadata() throws Exception {
        BufferedImage image = new BufferedImage(24, 12, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 4, 0xff336699);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);

        var result = validator.validate(bytes.toByteArray(), "folder\\artwork.png", "image/png");

        assertThat(result.fileName()).isEqualTo("artwork.png");
        assertThat(result.width()).isEqualTo(24);
        assertThat(result.height()).isEqualTo(12);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(result.bytes()))).isNotNull();
    }

    @Test
    void rejectsMismatchedTypeOversizeAndSecurityMarker() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
        assertCode(() -> validator.validate(png.toByteArray(), "art.jpg", "image/jpeg"),
                "MEDIA_TYPE_NOT_SUPPORTED");
        assertCode(() -> validator.validate(new byte[SecureImageValidator.MAX_BYTES + 1], "art.png", "image/png"),
                "MEDIA_FILE_TOO_LARGE");
        byte[] malware = png.toByteArray();
        byte[] marker = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] combined = java.util.Arrays.copyOf(malware, malware.length + marker.length);
        System.arraycopy(marker, 0, combined, malware.length, marker.length);
        assertCode(() -> validator.validate(combined, "art.png", "image/png"), "MEDIA_SECURITY_REJECTED");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
        assertThatThrownBy(callable).isInstanceOf(MediaException.class).extracting("code").isEqualTo(code);
    }
}
