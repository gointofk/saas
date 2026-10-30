package com.dailyactivity.file.application;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.FileProperties;
import com.dailyactivity.file.domain.port.FileRepository;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;

@Service
public class FileService {
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final long UPLOAD_TOKEN_TTL_SECONDS = 24 * 60 * 60;
    private final FileRepository files;
    private final AccountService accounts;
    private final FileProperties properties;

    public FileService(FileRepository files, AccountService accounts, FileProperties properties) {
        this.files = files;
        this.accounts = accounts;
        this.properties = properties;
    }

    @Transactional
    public Map<String, Object> upload(Principal p, MultipartFile file, String purpose, Long orderId, String stage) {
        return upload(p, file, purpose, orderId, stage, null);
    }

    @Transactional
    public Map<String, Object> upload(Principal p, MultipartFile file, String purpose, Long orderId, String stage, String parcelUuid) {
        if (!List.of("coa", "payment_channel_qr", "order_fulfillment", "order_shipping_label").contains(purpose))
            throw ApiException.badRequest("INVALID_FILE_PURPOSE", "Unsupported file purpose.");
        accounts.requireMenu(p, menu(purpose));
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES)
            throw ApiException.badRequest("INVALID_FILE", "File is empty.");
        if (List.of("order_fulfillment", "order_shipping_label").contains(purpose) && (orderId == null || orderId < 1 || !List.of("packed", "shipped").contains(stage)))
            throw ApiException.badRequest("INVALID_FULFILLMENT_UPLOAD", "Order id and fulfillment stage are required.");
        if (parcelUuid != null && !parcelUuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw ApiException.badRequest("INVALID_PARCEL_UUID", "Parcel UUID is invalid.");
        if ("order_shipping_label".equals(purpose) && (parcelUuid == null || !"shipped".equals(stage)))
            throw ApiException.badRequest("INVALID_LABEL_UPLOAD", "Shipping labels require a parcel and shipping stage.");
        if (List.of("order_fulfillment", "order_shipping_label").contains(purpose) && !files.orderBelongsToMerchant(orderId, p.merchantId()))
            throw ApiException.notFound("Order not found or not available for fulfillment.");
        String uuid = UUID.randomUUID().toString();
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path temporary = null;
        Path target = null;
        try {
            String mediaType = detectMediaType(file);
            if (!List.of("coa", "order_shipping_label").contains(purpose) && MediaType.APPLICATION_PDF_VALUE.equals(mediaType))
                throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Only JPEG and PNG files are allowed for this purpose.");
            verifyImageDimensions(file, mediaType);
            String stored = uuid + extensionFor(mediaType);
            Files.createDirectories(root);
            target = root.resolve(stored).normalize();
            if (!target.startsWith(root)) throw new IllegalStateException("Invalid file path");
            temporary = root.resolve(uuid + ".upload").normalize();
            if (!temporary.startsWith(root)) throw new IllegalStateException("Invalid file path");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = digest(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            files.insert(new FileRepository.FileAsset(uuid, stored, safeName(file.getOriginalFilename()), purpose, mediaType, file.getSize(), sha256, "available", Instant.now()), p.userId());
            Path uploadedPath = target;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) deleteQuietly(uploadedPath);
                    }
                });
            }
            return Map.of("fileUuid", uuid, "uploadToken", issueUploadToken(uuid, purpose, p.userId(), orderId, stage, parcelUuid), "mediaType", mediaType, "sizeBytes", file.getSize());
        } catch (ApiException e) {
            deleteQuietly(target);
            throw e;
        } catch (Exception e) {
            deleteQuietly(target);
            throw new IllegalStateException("File upload failed", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }

    @Transactional(readOnly = true)
    public String signedUrl(String uuid, Principal p) {
        accounts.seller(p);
        var asset = availableFile(uuid);
        if (!files.belongsToMerchant(uuid, p.merchantId())) throw ApiException.notFound("File not found.");
        switch (asset.purpose()) {
            case "coa" -> accounts.requireAnyMenu(p, "products", "certificates");
            case "order_fulfillment", "order_shipping_label" -> accounts.requireMenu(p, "orders");
            case "payment_channel_qr" -> accounts.requireMenu(p, "payment_channels");
            default -> throw ApiException.notFound("File not found.");
        }
        return signedUrl(asset);
    }

    @Transactional(readOnly = true)
    public String signedOrderAttachmentUrl(String uuid, Principal p) {
        accounts.seller(p);
        var asset = availableFile(uuid);
        if (!List.of("order_fulfillment", "order_shipping_label").contains(asset.purpose())) throw ApiException.notFound("File not found.");
        if (!files.belongsToMerchant(uuid, p.merchantId())) throw ApiException.notFound("File not found.");
        return signedUrl(asset);
    }

    @Transactional(readOnly = true)
    public String customerSignedUrl(String uuid, Principal p) {
        long customerId = accounts.customer(p).id();
        if (!files.customerCanAccessFile(uuid, customerId))
            throw ApiException.notFound("File not found.");
        return signedUrl(availableFile(uuid));
    }

    private FileRepository.FileAsset availableFile(String uuid) {
        return files.find(uuid).filter(asset -> "available".equals(asset.status()))
                .orElseThrow(() -> ApiException.notFound("File not found."));
    }

    private String signedUrl(FileRepository.FileAsset asset) {
        long expires = Instant.now().plusSeconds(properties.signedUrlSeconds()).getEpochSecond();
        String signature = signContent(asset, expires);
        return "/api/v1/files/" + asset.uuid() + "/content?expires=" + expires + "&signature=" + signature;
    }

    @Transactional(readOnly = true)
    public void validateUploadToken(String uuid, String purpose, long actor, Long orderId, String stage, String token, long merchantId) {
        var asset = files.find(uuid).orElseThrow(() -> ApiException.badRequest("FILE_NOT_AVAILABLE", "File is not available."));
        if (!files.belongsToMerchant(uuid, merchantId) || !"available".equals(asset.status()) || !asset.purpose().equals(purpose) || !validUploadToken(uuid, purpose, actor, orderId, stage, null, token))
            throw ApiException.badRequest("INVALID_UPLOAD_TOKEN", "File upload token is invalid for this operation.");
        requireUploadedContent(asset);
    }

    @Transactional(readOnly = true)
    public void validateParcelUploadToken(String uuid, String purpose, long actor, Long orderId, String stage, String parcelUuid, String token, long merchantId) {
        var asset = files.find(uuid).orElseThrow(() -> ApiException.badRequest("FILE_NOT_AVAILABLE", "File is not available."));
        if (!files.belongsToMerchant(uuid, merchantId) || !"available".equals(asset.status()) || !asset.purpose().equals(purpose)
                || !validUploadToken(uuid, purpose, actor, orderId, stage, parcelUuid, token))
            throw ApiException.badRequest("INVALID_UPLOAD_TOKEN", "File upload token is invalid for this parcel and stage.");
        requireUploadedContent(asset);
    }

    private void requireUploadedContent(FileRepository.FileAsset asset) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path path = root.resolve(asset.storedName()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path))
            throw ApiException.badRequest("FILE_NOT_AVAILABLE", "Uploaded file content is not available.");
    }

    @Transactional(readOnly = true)
    public Map<String, String> attachmentMetadata(String uuid) {
        return files.find(uuid).map(a -> Map.of("purpose", a.purpose(), "mediaType", a.mediaType())).orElse(Map.of());
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> content(String uuid, long expires, String signature) {
        var a = files.find(uuid).orElseThrow(() -> ApiException.notFound("File not found."));
        if (expires < Instant.now().getEpochSecond() || !"available".equals(a.status()) || !constantTime(signContent(a, expires), signature))
            throw ApiException.unauthorized("Signed file URL is invalid or expired.");
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path path = root.resolve(a.storedName()).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path))
            throw ApiException.notFound("File content not found.");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(a.mediaType())).header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + a.originalName().replace("\"", "") + "\"").header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox").body(new FileSystemResource(path));
    }

    private String menu(String purpose) {
        return "payment_channel_qr".equals(purpose) ? "payment_channels" : List.of("order_fulfillment", "order_shipping_label").contains(purpose) ? "orders" : "certificates";
    }

    private String sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.signingSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String signContent(FileRepository.FileAsset asset, long expires) {
        return sign(asset.uuid() + "\n" + asset.storedName() + "\n" + asset.purpose() + "\nGET\n/api/v1/files/" + asset.uuid() + "/content\n" + expires);
    }

    private String issueUploadToken(String uuid, String purpose, long actor, Long orderId, String stage, String parcelUuid) {
        long expires = Instant.now().plusSeconds(UPLOAD_TOKEN_TTL_SECONDS).getEpochSecond();
        return expires + "." + signUpload(uuid, purpose, actor, orderId, stage, parcelUuid, expires);
    }

    private boolean validUploadToken(String uuid, String purpose, long actor, Long orderId, String stage, String parcelUuid, String token) {
        if (token == null) return false;
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) return false;
        try {
            long expires = Long.parseLong(parts[0]);
            return expires >= Instant.now().getEpochSecond() && constantTime(signUpload(uuid, purpose, actor, orderId, stage, parcelUuid, expires), parts[1]);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String signUpload(String uuid, String purpose, long actor, Long orderId, String stage, String parcelUuid, long expires) {
        return sign(uuid + "\n" + purpose + "\n" + actor + "\n" + (orderId == null ? 0 : orderId) + "\n" + (stage == null ? "" : stage) + (parcelUuid == null ? "" : "\nparcel:" + parcelUuid) + "\n" + expires);
    }

    private boolean constantTime(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    private String digest(Path p) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private String detectMediaType(MultipartFile file) throws Exception {
        try (InputStream input = file.getInputStream()) {
            byte[] head = input.readNBytes(16);
            if (head.length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') return MediaType.APPLICATION_PDF_VALUE;
            if (head.length >= 8 && (head[0] & 0xff) == 0x89 && head[1] == 0x50 && head[2] == 0x4e && head[3] == 0x47 && head[4] == 0x0d && head[5] == 0x0a && head[6] == 0x1a && head[7] == 0x0a) return MediaType.IMAGE_PNG_VALUE;
            if (head.length >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8 && (head[2] & 0xff) == 0xff) return MediaType.IMAGE_JPEG_VALUE;
        }
        throw ApiException.badRequest("UNSUPPORTED_FILE_TYPE", "Only PDF, PNG and JPEG files are allowed.");
    }

    private String extensionFor(String mediaType) {
        return MediaType.APPLICATION_PDF_VALUE.equals(mediaType) ? ".pdf" : MediaType.IMAGE_PNG_VALUE.equals(mediaType) ? ".png" : ".jpg";
    }

    private void verifyImageDimensions(MultipartFile file, String mediaType) throws Exception {
        if (MediaType.APPLICATION_PDF_VALUE.equals(mediaType)) return;
        try (InputStream input = file.getInputStream()) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || (long) image.getWidth() * image.getHeight() > 40_000_000L)
                throw ApiException.badRequest("INVALID_IMAGE", "Image dimensions are invalid.");
        }
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "upload";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
