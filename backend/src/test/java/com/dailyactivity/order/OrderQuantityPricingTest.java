package com.dailyactivity.order;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.catalog.domain.QuantityPricing.Band;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.identity.domain.port.CustomerRepository;
import com.dailyactivity.inventory.domain.port.InventoryRepository;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.application.OrderParcelService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderQuantityPricingTest {
    private static final Instant VERSION = Instant.parse("2026-10-28T00:00:00Z");
    private static final Principal SELLER = new Principal(3, "seller", 0, 1L);
    private OrderRepository repository;
    private InventoryRepository inventory;
    private QuantityPriceRepository prices;
    private OrderService service;
    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        inventory = mock(InventoryRepository.class);
        prices = mock(QuantityPriceRepository.class);
        var customers = mock(CustomerRepository.class);
        var row = new OrderRepository.OrderRow(1, "TEST-QTY", 2, "TST", "Customer", "test@example.test", "pending", "unpaid", "{}",
                "Recipient", "City", null, null, new BigDecimal("425"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("425"),
                null, null, null, VERSION, null, VERSION, null, "pending", null, null, null, null, BigDecimal.ZERO, false);
        when(repository.findAuditOrderForUpdate(1, 1)).thenReturn(Optional.of(row));
        when(repository.findAuditOrder(1, 1)).thenReturn(Optional.of(row));
        when(repository.items(1)).thenReturn(List.of(new OrderRepository.ItemRow(11, 9, "SKU", "Product", "regular", 5, new BigDecimal("85"), new BigDecimal("425"))));
        when(repository.receivedAmount(1)).thenReturn(BigDecimal.ZERO);
        when(customers.find(1, 2)).thenReturn(Optional.of(new CustomerRepository.Customer(2, "TST", "Customer", "test@example.test", "regular", "V6", true,
                null, null, 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, null, VERSION)));
        service = new OrderService(mock(AccountService.class), null, null, null, repository, customers, null, inventory,
                null, null, null, new ObjectMapper(), mock(OrderParcelService.class), prices);
    }
    @Test
    void reducingQuantityUsesSavedScheduleAndDoesNotReadTodaysProductPrices() {
        when(repository.quantityPrices(11)).thenReturn(List.of(new Band(1, 4, new BigDecimal("95")), new Band(5, null, new BigDecimal("85"))));
        edit(4, new BigDecimal("85"), null, null); // old clients send the previous unit price
        verify(repository).updateItem(11, 4, new BigDecimal("95"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).updateOrderAmounts(1, new BigDecimal("380"), BigDecimal.ZERO, new BigDecimal("380"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository, never()).markItemManualPrice(anyLong(), any(com.dailyactivity.auth.domain.Actor.class));
        verifyNoInteractions(prices);
    }
    @Test
    void missingSnapshotPreservesLegacyOrManualPrice() {
        when(repository.quantityPrices(11)).thenReturn(List.of());
        edit(4, null, null, null);
        verify(repository).updateItem(11, 4, new BigDecimal("85"), new com.dailyactivity.auth.domain.Actor("seller",3));
    }
    @Test
    void explicitManualPriceCanRetainPreviousPriceAcrossAQuantityBoundary() {
        when(repository.quantityPrices(11)).thenReturn(List.of(new Band(1, 4, new BigDecimal("95")), new Band(5, null, new BigDecimal("85"))));
        edit(4, new BigDecimal("85"), true, "Agreed fixed price");
        verify(repository).updateItem(11, 4, new BigDecimal("85"), new com.dailyactivity.auth.domain.Actor("seller",3));
        verify(repository).markItemManualPrice(11, new com.dailyactivity.auth.domain.Actor("seller",3));
    }
    @Test
    void confirmedReceiptLocksQuantityChangesBeforeInventoryOrAmountWrites() {
        when(repository.receivedAmount(1)).thenReturn(BigDecimal.ONE);
        assertThatThrownBy(() -> edit(4, null, null, null)).extracting("code").isEqualTo("ORDER_AMOUNT_LOCKED");
        verifyNoInteractions(inventory);
        verify(repository, never()).updateItem(anyLong(), anyInt(), any(), any(com.dailyactivity.auth.domain.Actor.class));
    }
    private void edit(int quantity, BigDecimal price, Boolean manual, String reason) {
        service.updateAudit(SELLER, 1, new OrderService.AuditUpdate(List.of(new OrderService.AuditEditItem(9, BigDecimal.valueOf(quantity), price, manual)), reason, VERSION));
    }
}
