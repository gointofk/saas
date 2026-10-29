package com.dailyactivity.order;

import com.dailyactivity.order.infrastructure.JdbcOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JdbcOrderRepositoryTest {
    private static final long ORDER_ID = 41;

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement statement;
    @Mock private ResultSet rows;
    private JdbcOrderRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        repository = new JdbcOrderRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    @ParameterizedTest(name = "{0}: merchant={1}, customer={2}")
    @CsvSource(value = {
            "find,17,29", "find,83,29",
            "find,17,NULL", "find,83,NULL",
            "findForUpdate,17,29", "findForUpdate,83,29",
            "findForUpdate,17,NULL", "findForUpdate,83,NULL",
            "findAuditOrder,17,NULL", "findAuditOrder,83,NULL",
            "findAuditOrderForUpdate,17,NULL", "findAuditOrderForUpdate,83,NULL"
    }, nullValues = "NULL")
    void orderLookupBindsMerchantAndResourceScope(String lookup, long merchantId, Long customerId) throws Exception {
        // Exercise the real repository and Spring parameter binding; only JDBC I/O is mocked.
        var result = switch (lookup) {
            case "find" -> repository.find(merchantId, ORDER_ID, customerId);
            case "findForUpdate" -> repository.findForUpdate(merchantId, ORDER_ID, customerId);
            case "findAuditOrder" -> repository.findAuditOrder(merchantId, ORDER_ID);
            case "findAuditOrderForUpdate" -> repository.findAuditOrderForUpdate(merchantId, ORDER_ID);
            default -> throw new IllegalArgumentException(lookup);
        };

        assertThat(result).isEmpty();
        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("o.merchant_id=?", "o.id=?");
        verify(statement).setObject(1, merchantId);
        verify(statement).setObject(2, ORDER_ID);
        if (customerId != null) {
            assertThat(sql.getValue()).contains("o.customer_id=?");
            verify(statement).setObject(3, customerId);
        }
        if (lookup.endsWith("ForUpdate")) assertThat(sql.getValue()).endsWith("FOR UPDATE");
        verify(statement).executeQuery();
    }
}
