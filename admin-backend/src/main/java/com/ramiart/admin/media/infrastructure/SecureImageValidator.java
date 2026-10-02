package com.ramiart.admin.media.infrastructure;

import com.ramiart.admin.media.application.ImageValidator;
import com.ramiart.admin.media.application.MediaException;
import com.ramiart.admin.media.application.MediaModels.ValidatedImage;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public final class SecureImageValidator implements ImageValidator {
    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final int MAX_DIMENSION = 8_000;
    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    @Override
    public ValidatedImage validate(byte[] input, String rawName, String contentType) {
        long started = System.nanoTime();
        if (input == null || input.length == 0) throw new MediaException("MEDIA_FILE_REQUIRED");
        if (input.length > MAX_BYTES) throw new MediaException("MEDIA_FILE_TOO_LARGE");
        String fileName = safeFileName(rawName);
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        if (!TYPES.contains(mime) || !extensionMatches(fileName, mime) || !signatureMatches(input, mime)) {
            throw new MediaException("MEDIA_TYPE_NOT_SUPPORTED");
        }
        if (containsMalwareMarker(input)) throw new MediaException("MEDIA_SECURITY_REJECTED");

        try {
            ValidatedImage image = "image/webp".equals(mime)
                    ? validateWebp(input, fileName)
                    : decodeAndSanitize(input, fileName, mime);
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(30)) {
                throw new MediaException("MEDIA_SCAN_TIMEOUT");
            }
            return image;
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new MediaException("MEDIA_FILE_CORRUPTED", exception);
        }
    }

    private ValidatedImage decodeAndSanitize(byte[] input, String fileName, String mime) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(input));
        if (decoded == null) throw new MediaException("MEDIA_FILE_CORRUPTED");
        int orientation = "image/jpeg".equals(mime) ? jpegOrientation(input) : 1;
        BufferedImage oriented = orient(decoded, orientation);
        validateDimensions(oriented.getWidth(), oriented.getHeight());
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        String format = "image/png".equals(mime) ? "png" : "jpeg";
        BufferedImage writable = oriented;
        if ("jpeg".equals(format) && oriented.getType() != BufferedImage.TYPE_INT_RGB) {
            writable = new BufferedImage(oriented.getWidth(), oriented.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = writable.createGraphics();
            graphics.drawImage(oriented, 0, 0, null);
            graphics.dispose();
        }
        if (!ImageIO.write(writable, format, output)) throw new MediaException("MEDIA_FILE_CORRUPTED");
        byte[] sanitized = output.toByteArray();
        return new ValidatedImage(sanitized, fileName, mime, writable.getWidth(), writable.getHeight(), sha256(sanitized));
    }

    private ValidatedImage validateWebp(byte[] input, String fileName) throws IOException {
        long declared = Integer.toUnsignedLong(littleInt(input, 4)) + 8L;
        if (declared != input.length || input.length < 20) throw new MediaException("MEDIA_FILE_CORRUPTED");
        ByteArrayOutputStream chunks = new ByteArrayOutputStream(input.length);
        int width = -1;
        int height = -1;
        boolean imageChunk = false;
        int offset = 12;
        while (offset + 8 <= input.length) {
            String kind = new String(input, offset, 4, StandardCharsets.US_ASCII);
            int length = littleInt(input, offset + 4);
            if (length < 0 || offset + 8L + length + (length & 1) > input.length) {
                throw new MediaException("MEDIA_FILE_CORRUPTED");
            }
            if ("ANIM".equals(kind) || "ANMF".equals(kind)) throw new MediaException("MEDIA_TYPE_NOT_SUPPORTED");
            if ("VP8X".equals(kind) && length >= 10) {
                input[offset + 8] = (byte) (input[offset + 8] & ~0x2C); // remove ICC, EXIF and XMP flags
                width = read24(input, offset + 12) + 1;
                height = read24(input, offset + 15) + 1;
            } else if ("VP8 ".equals(kind) && length >= 10) {
                int frame = offset + 8;
                if ((input[frame + 3] & 0xff) != 0x9d || (input[frame + 4] & 0xff) != 0x01
                        || (input[frame + 5] & 0xff) != 0x2a) throw new MediaException("MEDIA_FILE_CORRUPTED");
                width = littleShort(input, frame + 6) & 0x3fff;
                height = littleShort(input, frame + 8) & 0x3fff;
                imageChunk = true;
            } else if ("VP8L".equals(kind) && length >= 5) {
                int frame = offset + 8;
                if ((input[frame] & 0xff) != 0x2f) throw new MediaException("MEDIA_FILE_CORRUPTED");
                int bits = littleInt(input, frame + 1);
                width = (bits & 0x3fff) + 1;
                height = ((bits >>> 14) & 0x3fff) + 1;
                imageChunk = true;
            }
            if (!Set.of("EXIF", "XMP ", "ICCP").contains(kind)) {
                chunks.write(input, offset, 8 + length + (length & 1));
            }
            offset += 8 + length + (length & 1);
        }
        if (offset != input.length || !imageChunk) throw new MediaException("MEDIA_FILE_CORRUPTED");
        validateDimensions(width, height);
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream sanitized = new ByteArrayOutputStream(body.length + 12);
        sanitized.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLittleInt(sanitized, body.length + 4);
        sanitized.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        sanitized.write(body);
        byte[] result = sanitized.toByteArray();
        return new ValidatedImage(result, fileName, "image/webp", width, height, sha256(result));
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        int width = source.getWidth();
        int height = source.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        BufferedImage target = new BufferedImage(swap ? height : width, swap ? width : height,
                source.getType() == 0 ? BufferedImage.TYPE_INT_ARGB : source.getType());
        AffineTransform transform = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
        Graphics2D graphics = target.createGraphics();
        graphics.drawImage(source, transform, null);
        graphics.dispose();
        return target;
    }

    private static int jpegOrientation(byte[] bytes) {
        int offset = 2;
        while (offset + 4 < bytes.length && (bytes[offset] & 0xff) == 0xff) {
            int marker = bytes[offset + 1] & 0xff;
            if (marker == 0xda || marker == 0xd9) break;
            int length = ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
            if (length < 2 || offset + 2 + length > bytes.length) break;
            if (marker == 0xe1 && length >= 16 && asciiEquals(bytes, offset + 4, "Exif\0\0")) {
                int tiff = offset + 10;
                boolean little = bytes[tiff] == 'I' && bytes[tiff + 1] == 'I';
                ByteOrder order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
                int ifd = tiff + buffer.getInt(tiff + 4);
                if (ifd + 2 > bytes.length) return 1;
                int count = Short.toUnsignedInt(buffer.getShort(ifd));
                for (int index = 0; index < count; index++) {
                    int entry = ifd + 2 + index * 12;
                    if (entry + 12 > bytes.length) return 1;
                    if (Short.toUnsignedInt(buffer.getShort(entry)) == 0x0112) {
                        return Short.toUnsignedInt(buffer.getShort(entry + 8));
                    }
                }
            }
            offset += 2 + length;
        }
        return 1;
    }

    private static String safeFileName(String value) {
        if (value == null) throw new MediaException("MEDIA_FILE_REQUIRED");
        String name = value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.length() > 255) throw new MediaException("MEDIA_TYPE_NOT_SUPPORTED");
        return name;
    }

    private static boolean extensionMatches(String name, String mime) {
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (mime) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            case "image/webp" -> lower.endsWith(".webp");
            default -> false;
        };
    }

    private static boolean signatureMatches(byte[] bytes, String mime) {
        return switch (mime) {
            case "image/jpeg" -> bytes.length >= 3 && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8 && asciiEquals(bytes, 1, "PNG")
                    && (bytes[0] & 0xff) == 0x89 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
            case "image/webp" -> bytes.length >= 12 && asciiEquals(bytes, 0, "RIFF") && asciiEquals(bytes, 8, "WEBP");
            default -> false;
        };
    }

    private static boolean containsMalwareMarker(byte[] bytes) {
        String probe = new String(bytes, StandardCharsets.ISO_8859_1);
        return probe.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")
                || probe.toLowerCase(Locale.ROOT).contains("<script");
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new MediaException("MEDIA_DIMENSION_INVALID");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        byte[] value = expected.getBytes(StandardCharsets.ISO_8859_1);
        if (offset < 0 || offset + value.length > bytes.length) return false;
        for (int index = 0; index < value.length; index++) if (bytes[offset + index] != value[index]) return false;
        return true;
    }

    private static int littleInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) throw new MediaException("MEDIA_FILE_CORRUPTED");
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int littleShort(byte[] bytes, int offset) {
        return littleInt(new byte[]{bytes[offset], bytes[offset + 1], 0, 0}, 0);
    }

    private static int read24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16);
    }

    private static void writeLittleInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}

