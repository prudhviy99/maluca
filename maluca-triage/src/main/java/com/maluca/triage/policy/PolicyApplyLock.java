package com.maluca.triage.policy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/** Cluster-wide, connection-pinned transaction lock for the external policy mutation. */
@Component
public class PolicyApplyLock {

    private static final long LOCK_ID = 0x4d414c554341504cL;

    private final DataSource dataSource;

    public PolicyApplyLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> T execute(Supplier<T> operation) {
        Connection connection = null;
        boolean transactionStarted = false;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            transactionStarted = true;
            if (!tryLock(connection)) {
                throw new IllegalStateException("another policy apply is already in progress");
            }
            return operation.get();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot acquire the policy apply lock", e);
        } finally {
            if (transactionStarted) {
                endLockTransaction(connection);
            }
            close(connection);
        }
    }

    private static boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, LOCK_ID);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static void endLockTransaction(Connection connection) {
        try {
            // This connection performs no data writes; rollback is the most
            // explicit way to release the transaction-scoped advisory lock.
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException ignored) {
                // The pool will discard a connection that failed transaction cleanup.
            }
        }
    }

    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The operation's outcome is authoritative. A pool return failure
            // must not turn a successfully verified mutation into an apparent
            // apply failure.
        }
    }
}
