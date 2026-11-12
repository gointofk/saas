package com.dailyactivity.order;

import com.dailyactivity.auth.application.AccountService;
import com.dailyactivity.auth.domain.Actor;
import com.dailyactivity.auth.domain.Principal;
import com.dailyactivity.catalog.domain.port.QuantityPriceRepository;
import com.dailyactivity.common.ApiException;
import com.dailyactivity.order.application.OrderParcelService;
import com.dailyactivity.order.application.OrderService;
import com.dailyactivity.order.domain.port.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("订单打回审核服务规则")
class OrderReturnToAuditServiceTest {
    private static final Instant VERSION = Instant.parse("2026-11-12T08:00:00Z");
    private static final Principal SELLER = new Principal(3, "seller", 0, 1L);
    private static final Actor ACTOR = new Actor("seller", 3);

    private OrderRepository repository;
    private OrderParcelService parcels;
    private OrderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderRepository.class);
        parcels = mock(OrderParcelService.class);
        service = new OrderService(mock(AccountService.class), null, null, null, repository,
                null, null, null, null, null, null, new ObjectMapper(), parcels,
                mock(QuantityPriceRepository.class));
    }

    @Test
    @DisplayName("待处理且未打包、未结佣的已审核订单回到审核队列并记录事件")
    void returnsEligibleOrderToAudit() {
        when(repository.findForUpdate(1L, 1, null)).thenReturn(Optional.of(row("pending", "approved")));
        when(repository.findAuditOrder(1L, 1)).thenReturn(Optional.of(row("pending", "pending")));

        var returned = service.returnToAudit(SELLER, 1, new OrderService.ReturnToAudit(VERSION));

        assertThat(returned).containsEntry("auditStatus", "pending").containsEntry("status", "pending");
        verify(repository).reopenAudit(1, ACTOR);
        verify(repository).addEvent(eq(1L), anyString(), eq("audit_returned"),
                eq("approved"), eq("pending"), isNull(), isNull(), isNull(), eq(ACTOR));
    }

    @Test
    @DisplayName("非待处理订单拒绝打回")
    void rejectsPackedOrder() {
        when(repository.findForUpdate(1L, 1, null)).thenReturn(Optional.of(row("packed", "approved")));

        assertThatThrownBy(() -> service.returnToAudit(SELLER, 1, new OrderService.ReturnToAudit(VERSION)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_NOT_RETURNABLE");
        verify(repository, never()).reopenAudit(anyLong(), any(Actor.class));
    }

    @Test
    @DisplayName("已创建包裹的订单拒绝打回")
    void rejectsStartedParcels() {
        when(repository.findForUpdate(1L, 1, null)).thenReturn(Optional.of(row("pending", "approved")));
        when(parcels.hasParcels(1)).thenReturn(true);

        assertThatThrownBy(() -> service.returnToAudit(SELLER, 1, new OrderService.ReturnToAudit(VERSION)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_PARCELS_STARTED");
        verify(repository, never()).reopenAudit(anyLong(), any(Actor.class));
    }

    @Test
    @DisplayName("已结算佣金的订单拒绝打回")
    void rejectsSettledCommission() {
        when(repository.findForUpdate(1L, 1, null)).thenReturn(Optional.of(row("pending", "approved")));
        when(repository.hasSettledCommission(1)).thenReturn(true);

        assertThatThrownBy(() -> service.returnToAudit(SELLER, 1, new OrderService.ReturnToAudit(VERSION)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("ORDER_COMMISSION_SETTLED");
        verify(repository, never()).reopenAudit(anyLong(), any(Actor.class));
    }

    private OrderRepository.OrderRow row(String orderStatus, String auditStatus) {
        return new OrderRepository.OrderRow(1, "TST-20261112-001", 2, "TST", "Test customer",
                "customer@example.test", orderStatus, "unpaid", "{}", "Recipient", "City", null, null,
                new BigDecimal("100.00"), new BigDecimal("0.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                null, null, null, VERSION, null, VERSION, null, auditStatus, null, null, null, null,
                BigDecimal.ZERO, false);
    }
}
