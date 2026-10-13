package xizhang.rc_xizhang_coding.lock;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** A connection-scoped MySQL lock; no lock columns and no open database transaction. */
@Component
public class MysqlTaskLock {
    private final DataSource dataSource;

    public MysqlTaskLock(DataSource dataSource) { this.dataSource = dataSource; }

    public interface Lease extends AutoCloseable {
        void checkOwned() throws SQLException;
        @Override void close() throws SQLException;
    }

    public Lease tryAcquire(String taskId) throws SQLException {
        Connection connection = dataSource.getConnection();
        String name = "notification:" + taskId;
        try {
            int result = query(connection, "SELECT GET_LOCK(?, 0)", name);
            if (result != 1) {
                connection.close();
                if (result == -1) throw new SQLException("GET_LOCK returned NULL");
                return null;
            }
        } catch (SQLException e) {
            // A failed query may still have acquired the lock on the server.
            abortAndClose(connection);
            throw e;
        }
        return new Lease() {
            @Override public void checkOwned() throws SQLException {
                if (query(connection, "SELECT IS_USED_LOCK(?) = CONNECTION_ID()", name) != 1) {
                    throw new SQLException("Task lock lost");
                }
            }

            @Override public void close() throws SQLException {
                try {
                    if (query(connection, "SELECT RELEASE_LOCK(?)", name) != 1) {
                        throw new SQLException("Task lock release failed");
                    }
                } catch (SQLException e) {
                    // Never put a session with a potentially held lock back into the pool.
                    abortAndClose(connection);
                    throw e;
                }
                connection.close();
            }
        };
    }

    private static int query(Connection connection, String sql, String name) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("No lock result");
                int value = result.getInt(1);
                return result.wasNull() ? -1 : value;
            }
        }
    }

    private static void abortAndClose(Connection connection) throws SQLException {
        try { connection.abort(Runnable::run); }
        finally { connection.close(); }
    }
}
