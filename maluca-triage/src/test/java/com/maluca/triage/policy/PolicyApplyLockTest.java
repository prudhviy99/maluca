package com.maluca.triage.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class PolicyApplyLockTest {

    @Test
    void pinsOneConnectionAcrossOperationAndUnlock() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet acquired = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(acquired.next()).thenReturn(true);
        when(acquired.getBoolean(1)).thenReturn(true);

        String result = new PolicyApplyLock(dataSource).execute(() -> "applied");

        assertThat(result).isEqualTo("applied");
        var order = inOrder(connection, acquire);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement("SELECT pg_try_advisory_xact_lock(?)");
        order.verify(acquire).executeQuery();
        order.verify(connection).rollback();
    }

    @Test
    void competingApplyFailsBeforeRunningOperation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = mock(PreparedStatement.class);
        ResultSet acquired = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(acquire);
        when(acquire.executeQuery()).thenReturn(acquired);
        when(acquired.next()).thenReturn(true);
        when(acquired.getBoolean(1)).thenReturn(false);

        assertThatThrownBy(() -> new PolicyApplyLock(dataSource).execute(
                () -> { throw new AssertionError("must not execute"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }
}
