package com.dailyactivity.file.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.FileProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class MerchantLogoService {
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final String URL_PREFIX = "/api/v1/files/merchant-logos/";
    private final AccountService accounts;
    private final FileProperties properties;

    public MerchantLogoService(AccountService accounts, FileProperties properties) {
        this.accounts = accounts;
        this.properties = properties;
    }

    public Map<String, String> upload(Principal principal, MultipartFile file) {
        accounts.requirePlatformAdmin(principal);
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES)
            throw ApiException.badRequest("INVALID_MERCHANT_LOGO", "Logo must be a non-empty image no larger than 5 MiB.");

        String mediaType;
        try {
            mediaType = detectMediaType(file);
            try (InputStream input = file.getInputStream()) {
                BufferedImage image = ImageIO.read(input);
                if (image == null || (long) image.getWidth() * image.getHeight() > 40_000_000L)
                    throw ApiException.badRequest("INVALID_MERCHANT_LOGO", "Logo image dimensions are invalid.");
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_MERCHANT_LOGO", "Logo image could not be read.");
        }

        String uuid = UUID.randomUUID().toString();
        String extension = MediaType.IMAGE_PNG_VALUE.equals(mediaType) ? ".png" : ".jpg";
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path directory = root.resolve("merchant-logos").normalize();
        Path temporary = directory.resolve(uuid + ".upload").normalize();
        Path target = directory.resolve(uuid + extension).normalize();
        if (!directory.startsWith(root) || !temporary.startsWith(directory) || !target.startsWith(directory))
            throw new IllegalStateException("Invalid merchant logo path");

        try {
            Files.createDirectories(directory);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Map.of("logoUrl", URL_PREFIX + uuid + "/content");
        } catch (Exception e) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw new IllegalStateException("Merchant logo upload failed", e);
        }
    }

    public ResponseEntity<Resource> content(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ApiException.notFound("Logo not found.");
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path directory = root.resolve("merchant-logos").normalize();
        for (var type : new String[][]{{".png", MediaType.IMAGE_PNG_VALUE}, {".jpg", MediaType.IMAGE_JPEG_VALUE}}) {
            Path path = directory.resolve(uuid + type[0]).normalize();
            if (path.startsWith(directory) && Files.isRegularFile(path)) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(type[1]))
                        .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic())
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"merchant-logo" + type[0] + "\"")
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "sandbox")
                        .body(new FileSystemResource(path));
            }
        }
        throw ApiException.notFound("Logo not found.");
    }

    private String detectMediaType(MultipartFile file) throws Exception {
        try (InputStream input = file.getInputStream()) {
            byte[] head = input.readNBytes(8);
            if (head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 0x50 && head[2] == 0x4e && head[3] == 0x47
                    && head[4] == 0x0d && head[5] == 0x0a && head[6] == 0x1a && head[7] == 0x0a)
                return MediaType.IMAGE_PNG_VALUE;
            if (head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff)
                return MediaType.IMAGE_JPEG_VALUE;
        }
        throw ApiException.badRequest("UNSUPPORTED_MERCHANT_LOGO", "Only JPEG and PNG logos are allowed.");
    }

    private void deleteQuietly(Path path) {
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }
}
