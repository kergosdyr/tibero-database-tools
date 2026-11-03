package mock.tibero;

import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * In-process mock of a Tibero 6 server for IDE plugin testing. Accepts {@code jdbc:tibero:thin:@host:port:db} URLs,
 * users DEMO and HR with any password. Needs H2 and the real tibero6-jdbc.jar (for its metadata SQL) on the same classpath.
 * Optional property {@code mockLog} (or system property {@code mock.tibero.log}) appends every statement to a file.
 */
public final class MockTiberoDriver implements Driver {
    static {
        try { DriverManager.registerDriver(new MockTiberoDriver()); }
        catch(SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @Override public Connection connect(String url,Properties info) throws SQLException {
        if(!acceptsURL(url)) return null;
        var server=Server.get();
        String log=info.getProperty("mockLog",System.getProperty("mock.tibero.log"));
        if(log!=null && !log.isBlank()) server.log=java.nio.file.Path.of(log);
        var session=server.open(info.getProperty("user"));
        server.log("CONNECT " + session.user + " " + url);
        return Jdbc.connection(session,url);
    }
    @Override public boolean acceptsURL(String url) { return url!=null && url.startsWith("jdbc:tibero:"); }
    @Override public DriverPropertyInfo[] getPropertyInfo(String url,Properties info) { return new DriverPropertyInfo[0]; }
    @Override public int getMajorVersion() { return 6; }
    @Override public int getMinorVersion() { return 0; }
    @Override public boolean jdbcCompliant() { return false; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
}
