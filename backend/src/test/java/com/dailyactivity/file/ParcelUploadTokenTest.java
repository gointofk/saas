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

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("包裹上传签名及面单")
class ParcelUploadTokenTest {
    @TempDir Path root;
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    @DisplayName("真实签名不能跨订单、包裹、成员、阶段或绕过包裹绑定")
    void uploadTokenCannotChangeScope() throws java.io.IOException {
        var repository = mock(FileRepository.class);
        when(repository.orderBelongsToMerchant(1L, 1L)).thenReturn(true);
        Map<String, FileRepository.FileAsset> assets = new HashMap<>();
        doAnswer(call -> { FileRepository.FileAsset asset = call.getArgument(0); assets.put(asset.uuid(), asset); return null; }).when(repository).insert(any(), anyLong());
        when(repository.find(anyString())).thenAnswer(call -> Optional.ofNullable(assets.get(call.getArgument(0))));
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60, "test-only-parcel-signing-key"));
        String parcel = UUID.randomUUID().toString();
        var upload = files.upload(new Principal(3, "seller", 0, 1L), new MockMultipartFile("file", "photo.png", "image/png", PNG), "order_fulfillment", 1L, "packed", parcel);
        String uuid = (String) upload.get("fileUuid"), token = (String) upload.get("uploadToken");
        assertThatCode(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", parcel, token)).doesNotThrowAnyException();
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 2L, "packed", parcel, token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", UUID.randomUUID().toString(), token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 4, 1L, "packed", parcel, token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "shipped", parcel, token)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.validateUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", token)).isInstanceOf(ApiException.class);
        java.nio.file.Files.delete(root.resolve(assets.get(uuid).storedName()));
        assertThatThrownBy(() -> files.validateParcelUploadToken(uuid, "order_fulfillment", 3, 1L, "packed", parcel, token))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("FILE_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("面单支持 PDF 并限定发货阶段和包裹，普通履约凭证仍拒绝 PDF")
    void shippingLabelsAcceptPdf() {
        var repository = mock(FileRepository.class);
        when(repository.orderBelongsToMerchant(1L, 1L)).thenReturn(true);
        var files = new FileService(repository, mock(AccountService.class), new FileProperties(root.toString(), 60, "test-only-parcel-signing-key"));
        var pdf = new MockMultipartFile("file", "label.pdf", "application/pdf", "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String parcel = UUID.randomUUID().toString();
        assertThat(files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_shipping_label", 1L, "shipped", parcel)).containsEntry("mediaType", "application/pdf");
        assertThatThrownBy(() -> files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_shipping_label", 1L, "packed", parcel)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> files.upload(new Principal(3, "seller", 0, 1L), pdf, "order_fulfillment", 1L, "shipped", parcel)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("UNSUPPORTED_FILE_TYPE");
    }
}
