package com.dailyactivity.file;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.config.FileProperties;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.file.domain.port.FileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("包裹上传签名及面单")
class ParcelUploadTokenTest {
    @TempDir Path root;
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    @DisplayName("已发布 COA 可按固定 URL 公开读取并允许 CDN 长期缓存")
    void publishedCoaUsesFixedPublicUrlAndImmutableCache() throws java.io.IOException {
        var repository = mock(FileRepository.class);
        String uuid = UUID.randomUUID().toString();
        byte[] content = "%PDF-1.7 public coa".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var asset = new FileRepository.FileAsset(uuid, uuid + ".pdf", "coa.pdf", "coa", "application/pdf",
                content.length, "a".repeat(64), "available", Instant.now());
        when(repository.findPublicCoa(uuid)).thenReturn(Optional.of(asset));
        Files.write(root.resolve(asset.storedName()), content);

        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60,
                "test-only-public-coa-signing-key", "https://cdn.example.test/api/v1/public/coas/"));
        var response = files.publicCoaContent(uuid);

        assertThat(files.publicCoaUrl(uuid)).isEqualTo("https://cdn.example.test/api/v1/public/coas/" + uuid);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("public, max-age=31536000, immutable");
        assertThat(response.getHeaders().getETag()).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(response.getBody()).isNotNull().satisfies(resource -> assertThat(resource.exists()).isTrue());
    }

    @Test
    @DisplayName("未发布或非 COA 文件不能经公开 COA 路径读取")
    void unpublishedCoaIsNotFound() {
        var repository = mock(FileRepository.class);
        String uuid = UUID.randomUUID().toString();
        when(repository.findPublicCoa(uuid)).thenReturn(Optional.empty());
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60,
                "test-only-public-coa-signing-key", ""));

        assertThat(files.publicCoaUrl(uuid)).isEqualTo("/api/v1/public/coas/" + uuid);
        assertThatThrownBy(() -> files.publicCoaContent(uuid)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("公开 COA 仅返回允许的文件类型")
    void publicCoaRejectsUnsupportedMediaType() {
        var repository = mock(FileRepository.class);
        String uuid = UUID.randomUUID().toString();
        var asset = new FileRepository.FileAsset(uuid, uuid + ".gif", "coa.gif", "coa", "image/gif",
                1, "b".repeat(64), "available", Instant.now());
        when(repository.findPublicCoa(uuid)).thenReturn(Optional.of(asset));
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60,
                "test-only-public-coa-signing-key", ""));

        assertThatThrownBy(() -> files.publicCoaContent(uuid)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("真实签名不能跨订单、包裹、成员、阶段或绕过包裹绑定")
    void uploadTokenCannotChangeScope() throws java.io.IOException {
        var repository = mock(FileRepository.class);
        when(repository.belongsToMerchant(anyString(), eq(1L))).thenReturn(true);
        when(repository.orderBelongsToMerchant(1L, 1L)).thenReturn(true);
        Map<String, FileRepository.FileAsset> assets = new HashMap<>();
        doAnswer(call -> { FileRepository.FileAsset asset = call.getArgument(0); assets.put(asset.uuid(), asset); return null; }).when(repository).insert(any(), anyLong());
        when(repository.find(anyString())).thenAnswer(call -> Optional.ofNullable(assets.get(call.getArgument(0))));
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60, "test-only-parcel-signing-key", ""));
        String parcel = UUID.randomUUID().toString();
        var upload = files.upload(new Principal(3, "seller", 0, 1L), new MockMultipartFile("file", "photo.png", "image/png", PNG), "order_fulfillment", 1L, "packed", parcel);
        String uuid = (String) upload.get("fileUuid"), token = (String) upload.get("uploadToken");
        assertThatCode(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", parcel, token, 1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", parcel, token, 2L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 2L, "packed", parcel, token, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", UUID.randomUUID().toString(), token, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 4, 1L, "packed", parcel, token, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "shipped", parcel, token, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", token, 1L)).isInstanceOf(ApiException.class);
        java.nio.file.Files.delete(root.resolve(assets.get(uuid).storedName()));
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", parcel, token, 1L))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FILE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("面单支持 PDF 并限定发货阶段和包裹，普通履约凭证仍拒绝 PDF")
    void shippingLabelsAcceptPdf() {
        var repository = mock(FileRepository.class);
        when(repository.orderBelongsToMerchant(1L, 1L)).thenReturn(true);
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60, "test-only-parcel-signing-key", ""));
        var pdf = new MockMultipartFile("file", "label.pdf", "application/pdf", "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String parcel = UUID.randomUUID().toString();
        assertThat(files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_shipping_label", 1L, "shipped", parcel)).containsEntry("mediaType", "application/pdf");
        assertThatThrownBy(() -> files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_shipping_label", 1L, "packed", parcel)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_fulfillment", 1L, "shipped", parcel)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNSUPPORTED_FILE_TYPE");
    }
}
