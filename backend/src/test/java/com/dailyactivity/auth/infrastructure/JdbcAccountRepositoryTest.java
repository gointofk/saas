package com.dailyactivity.auth.infrastructure;

import org.junit.jupiter.api.Test;
import com.dailyactivity.common.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JdbcAccountRepositoryTest {
    @ParameterizedTest
    @CsvSource({"customer,`user`,11", "seller,seller,11", "platform,admin,0"})
    void sameIdIsLookedUpOnlyInTheRequestedAccountTable(String type,String table,long merchantId) throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);
        when(rows.getLong("id")).thenReturn(7L);
        when(rows.getLong("merchant_id")).thenReturn(merchantId);
        when(rows.getBoolean("active")).thenReturn(true);
        var repository=new JdbcAccountRepository(new NamedParameterJdbcTemplate(source));

        var account=repository.findActiveById(type,7).orElseThrow();

        assertThat(account.id()).isEqualTo(7);
        assertThat(account.userType()).isEqualTo(type);
        assertThat(account.merchantId()).isEqualTo(merchantId);
        var sql=ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).contains("FROM "+table+" WHERE deleted=0 AND id=? AND active=1").doesNotContain("UNION","user_type");
        verify(statement).setObject(1,7L);
    }

    @ParameterizedTest
    @CsvSource({"customer,`user`", "seller,seller", "platform,admin"})
    void logoutVersionUpdateCannotRevokeTheSameIdInAnotherTable(String type,String table) throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        var repository=new JdbcAccountRepository(new NamedParameterJdbcTemplate(source));

        assertThat(repository.incrementAuthVersion(type,7,3)).isTrue();
        var sql=ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue()).startsWith("UPDATE "+table+" SET auth_version=auth_version+1");
        verify(statement).setObject(1,7L);
        verify(statement).setObject(2,3L);
    }
    @Test
    void unknownAccountTypeNeverBecomesASqlIdentifier() {
        var source=mock(DataSource.class);
        var repository=new JdbcAccountRepository(new NamedParameterJdbcTemplate(source));
        assertThatThrownBy(()->repository.findActiveById("seller WHERE 1=1",7)).isInstanceOf(ApiException.class);
        verifyNoInteractions(source);
    }

}
