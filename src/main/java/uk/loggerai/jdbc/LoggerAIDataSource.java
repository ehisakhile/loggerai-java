package uk.loggerai.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;
import javax.sql.DataSource;
import uk.loggerai.Hooks;

/**
 * Times every query made through a DataSource, grouped as "mysql SELECT",
 * "postgres UPDATE"... Spring Boot wraps the app's DataSource on its own;
 * elsewhere: {@code dataSource = LoggerAIDataSource.wrap(dataSource)}.
 * JPA and Hibernate go through the DataSource, so they're covered too.
 */
public class LoggerAIDataSource implements DataSource {
    private final DataSource delegate;
    private volatile String target;

    public LoggerAIDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public static DataSource wrap(DataSource ds) {
        return ds == null || ds instanceof LoggerAIDataSource ? ds : new LoggerAIDataSource(ds);
    }

    public DataSource getDelegate() {
        return delegate;
    }

    /** mysql, postgres, sqlserver... from the JDBC URL. */
    static String targetOf(String url) {
        if (url == null) return "database";
        String u = url.toLowerCase();
        if (u.startsWith("jdbc:mysql") || u.startsWith("jdbc:mariadb")) return "mysql";
        if (u.startsWith("jdbc:postgresql")) return "postgres";
        if (u.startsWith("jdbc:sqlserver")) return "sqlserver";
        if (u.startsWith("jdbc:oracle")) return "oracle";
        if (u.startsWith("jdbc:sqlite")) return "sqlite";
        if (u.startsWith("jdbc:h2")) return "h2";
        String[] p = u.split(":");
        return p.length > 1 ? p[1] : "database";
    }

    private Connection wrap(Connection c) {
        if (c == null) return null;
        if (target == null) {
            try {
                target = targetOf(c.getMetaData().getURL());
            } catch (Exception e) {
                target = "database";
            }
        }
        return (Connection) Proxy.newProxyInstance(LoggerAIDataSource.class.getClassLoader(), new Class<?>[] {Connection.class},
                new ConnectionHandler(c, target));
    }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }
    @Override public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        if (iface.isInstance(delegate)) return iface.cast(delegate);
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || iface.isInstance(delegate) || delegate.isWrapperFor(iface);
    }

    private static Object call(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Statements made from the connection are timed; everything else passes through. */
    private record ConnectionHandler(Connection conn, String target) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();
            if (name.equals("unwrap") && args[0] instanceof Class<?> c && c.isInstance(conn)) return conn;
            if (name.equals("isWrapperFor") && args[0] instanceof Class<?> c && c.isInstance(conn)) return true;
            Object r = call(conn, m, args);
            if (r instanceof Statement s && (name.equals("createStatement") || name.startsWith("prepare"))) {
                String sql = args != null && args.length > 0 && args[0] instanceof String q ? q : null;
                Class<?> type = s instanceof CallableStatement ? CallableStatement.class : s instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(LoggerAIDataSource.class.getClassLoader(), new Class<?>[] {type}, new StatementHandler(s, target, sql));
            }
            return r;
        }
    }

    private record StatementHandler(Statement stmt, String target, String sql) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
            String name = m.getName();
            if (name.equals("unwrap") && args[0] instanceof Class<?> c && c.isInstance(stmt)) return stmt;
            if (!name.startsWith("execute")) return call(stmt, m, args);
            String q = args != null && args.length > 0 && args[0] instanceof String s ? s : sql;
            long t = System.nanoTime();
            boolean ok = false;
            try {
                Object r = call(stmt, m, args);
                ok = true;
                return r;
            } finally {
                Hooks.dependency(target, name.equals("executeBatch") ? "BATCH" : Hooks.sqlVerb(q), (System.nanoTime() - t) / 1e6, ok);
            }
        }
    }
}
