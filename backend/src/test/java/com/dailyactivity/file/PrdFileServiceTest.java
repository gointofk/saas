package com.dailyactivity.file;

import com.dailyactivity.common.ApiException;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.file.application.FileService;
import com.dailyactivity.order.application.OrderParcelService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.support.PrdDatabaseTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "app.file.root=target/prd-file-tests")
@DisplayName("PRD C-07 文件上传与签名访问")
class PrdFileServiceTest extends PrdDatabaseTest {
    private static final byte[] ONE_PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired
    private FileService files;

    @AfterEach
    void removeUploadedFiles() throws Exception {
        Path root = Path.of("target/prd-file-tests");
        if (Files.exists(root)) {
            try (Stream<Path> paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Test cleanup must not hide the assertion result.
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("二维码上传返回文件 UUID 和上传令牌，并可通过签名地址读取")
    void uploadAndSignedContentRoundTrip() throws Exception {
        Map<String, Object> uploaded = files.upload(seller(),
                new MockMultipartFile("file", "qr.png", "image/png", ONE_PIXEL_PNG),
                "payment_channel_qr", null, null);

        String uuid = (String) uploaded.get("fileUuid");
        String signed = files.signedUrl(uuid, seller());
        URI uri = URI.create("http://localhost" + signed);
        String[] query = uri.getQuery().split("&");
        long expires = Long.parseLong(query[0].substring("expires=".length()));
        String signature = query[1].substring("signature=".length());
        ResponseEntity<Resource> content = files.content(uuid, expires, signature);

        assertThat(uploaded).containsKeys("fileUuid", "uploadToken", "mediaType", "sizeBytes");
        assertThat(uploaded.get("mediaType")).isEqualTo("image/png");
        assertThat(content.getStatusCode().value()).isEqualTo(200);
        assertThat(content.getBody()).isNotNull().satisfies(resource -> assertThat(resource.exists()).isTrue());
    }

    @Test
    @DisplayName("支付二维码不接受 PDF 文件")
    void qrUploadRejectsPdf() {
        assertThatThrownBy(() -> files.upload(seller(),
                new MockMultipartFile("file", "qr.pdf", "application/pdf", "%PDF-1.7".getBytes()),
                "payment_channel_qr", null, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("UNSUPPORTED_FILE_TYPE");
    }

    @Test
    @DisplayName("上传用途必须是系统支持的用途")
    void uploadRejectsUnknownPurpose() {
        assertThatThrownBy(() -> files.upload(seller(),
                new MockMultipartFile("file", "unknown.png", "image/png", ONE_PIXEL_PNG),
                "unknown", null, null))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_FILE_PURPOSE");
    }

    @Test
    @DisplayName("履约文件上传必须绑定订单和 packed/shipped 阶段")
    void fulfillmentUploadRequiresOrderAndStage() {
        assertThatThrownBy(() -> files.upload(seller(),
                new MockMultipartFile("file", "proof.png", "image/png", ONE_PIXEL_PNG),
                "order_fulfillment", null, "received"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("INVALID_FULFILLMENT_UPLOAD");
    }

    @Test
    @DisplayName("真实包裹照片及 PDF 面单完成关联后仅本人客户可访问")
    @SuppressWarnings("unchecked")
    void parcelPhotosAndLabelsAreVisibleOnlyToOrderOwner() {
        var order = createOrder("parcel-files", 1);
        long id = orderId(order);
        long item = ((Number) ((List<Map<String, Object>>) order.get("items")).get(0).get("orderItemId")).longValue();
        String parcelUuid = UUID.randomUUID().toString();
        var packPhoto = files.upload(seller(), new MockMultipartFile("file", "pack.png", "image/png", ONE_PIXEL_PNG), "order_fulfillment", id, "packed", parcelUuid);
        var packed = orders.packParcels(seller(), id, new OrderParcelService.PackRequest(List.of(
                new OrderParcelService.PackParcel(parcelUuid, List.of(new OrderParcelService.ParcelItem(item, 1)), List.of(reference(packPhoto)), null)),
                (Instant) order.get("updatedAt")));
        long parcelId = ((Number) ((List<Map<String, Object>>) packed.get("parcels")).get(0).get("id")).longValue();
        var labelImage = files.upload(seller(), new MockMultipartFile("file", "label.png", "image/png", ONE_PIXEL_PNG), "order_shipping_label", id, "shipped", parcelUuid);
        var label = files.upload(seller(), new MockMultipartFile("file", "label.pdf", "application/pdf", "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), "order_shipping_label", id, "shipped", parcelUuid);
        String labelUuid = (String) label.get("fileUuid");
        assertThatThrownBy(() -> files.customerSignedUrl(labelUuid, customer())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> orders.shipParcels(seller(), id, new OrderParcelService.ShipRequest(List.of(
                new OrderParcelService.ShipParcel(parcelId, "TRACK-FILES", "Carrier", List.of(reference(packPhoto)), null)),
                (Instant) packed.get("updatedAt"))))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("INVALID_UPLOAD_TOKEN");

        orders.shipParcels(seller(), id, new OrderParcelService.ShipRequest(List.of(new OrderParcelService.ShipParcel(
                parcelId, "TRACK-FILES", "Carrier", List.of(reference(labelImage), reference(label)), "Public shipment note")),
                (Instant) packed.get("updatedAt")));

        var detail = orders.detail(customer(), id);
        var parcel = ((List<Map<String, Object>>) detail.get("parcels")).get(0);
        var events = (List<Map<String, Object>>) parcel.get("events");
        var shipment = events.stream().filter(event -> "shipped".equals(event.get("toStatus"))).findFirst().orElseThrow();
        assertThat((List<String>) shipment.get("files")).isEmpty();
        var attachments = (List<Map<String, String>>) shipment.get("labels");
        assertThat(attachments).hasSize(2);
        assertThat(attachments.get(0)).containsEntry("fileUuid", (String) labelImage.get("fileUuid")).containsEntry("mediaType", "image/png").containsKey("url");
        assertThat(attachments.get(1)).containsEntry("fileUuid", labelUuid).containsEntry("mediaType", "application/pdf").containsKey("url");
        assertThat(shipment).containsEntry("note", "Public shipment note").doesNotContainKey("actorName");
        var listed = orders.adminOrders(seller(), null, null, "TRACK-FILES", null, null, 1).items().get(0);
        assertThat(listed).doesNotContainKey("previewImages");
        assertThat(files.customerSignedUrl(labelUuid, customer())).contains("/files/" + labelUuid + "/content?");
        assertThatThrownBy(() -> files.customerSignedUrl(labelUuid, new Principal(data.referrerId(), "customer", 0, data.seller().merchantId())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    }

    private OrderService.FileReference reference(Map<String, Object> upload) {
        return new OrderService.FileReference((String) upload.get("fileUuid"), (String) upload.get("uploadToken"));
    }
}
