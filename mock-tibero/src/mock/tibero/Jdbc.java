package mock.tibero;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** JDBC facade: H2 objects behind dynamic proxies, with Tibero routing, metadata and type names. */
final class Jdbc {
    private Jdbc() {}

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(Jdbc.class.getClassLoader(),new Class<?>[]{type},handler);
    }
    static Object call(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch(InvocationTargetException e) { throw e.getCause(); }
    }

    // ---- Connection ----
    static Connection connection(Session s,String url) {
        var holder=new Connection[1];
        holder[0]=proxy(Connection.class,(p,m,a) -> {
            switch(m.getName()) {
                case "createStatement": return statement(s,holder[0],(Statement)call(s.h2,m,a));
                case "prepareStatement": return prepared(s,holder[0],(String)a[0],m,a);
                case "prepareCall": return callable(s,holder[0],(String)a[0]);
                case "getMetaData": return metadata(s,holder[0],url);
                case "nativeSQL": return a[0];
                // Tibero 6's TbConnection predates JDBC 4.1: no getSchema()/setSchema(), getCatalog() returns null.
                case "getSchema": throw new AbstractMethodError("com.tmax.tibero.jdbc.driver.TbConnection.getSchema()");
                case "setSchema": throw new AbstractMethodError("com.tmax.tibero.jdbc.driver.TbConnection.setSchema(String)");
                case "getCatalog": return null;
                case "setCatalog": return null;
                case "isWrapperFor": return ((Class<?>)a[0]).isInstance(p);
                case "unwrap": if(((Class<?>)a[0]).isInstance(p)) return p; break;
                case "toString": return "MockTiberoConnection[" + s.user + "]";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p==a[0];
                default:
            }
            try { return call(s.h2,m,a); }
            catch(SQLException e) { throw Session.translate(e,null); }
        });
        return holder[0];
    }

    // ---- Statement / PreparedStatement over H2 ----
    private static final class Pending { ResultSet rs; long count=-1; SQLWarning warning; boolean mock; }
    static Statement statement(Session s,Connection connection,Statement h2) {
        var state=new Pending();
        var holder=new Statement[1];
        holder[0]=proxy(Statement.class,(p,m,a) -> {
            String name=m.getName();
            if(a!=null && a.length>0 && a[0] instanceof String sql && (name.startsWith("execute") || name.equals("addBatch"))) {
                if(name.equals("addBatch")) { h2.addBatch(s.toH2(sql)); return null; }
                s.server.log("SQL  " + sql.replaceAll("\\s+"," "));
                if(Session.classify(sql)!=Session.Kind.SQL) {
                    var outcome=mock(s,sql,Map.of(),state);
                    return switch(name) {
                        case "executeQuery" -> throw new SQLException("[mock] statement does not return a result set","07000",-90000);
                        case "executeUpdate" -> (int)outcome.count();
                        case "executeLargeUpdate" -> outcome.count();
                        default -> false;
                    };
                }
                state.mock=false;
                try {
                    var args=a.clone(); args[0]=s.toH2(sql);
                    Object result=call(h2,m,args);
                    s.afterH2(sql);
                    return result instanceof ResultSet rs ? resultSet(rs,holder[0]) : result;
                } catch(SQLException e) { s.server.log("  -> " + e.getMessage()); throw Session.translate(e,sql); }
            }
            return common(state,h2,holder[0],connection,p,m,a);
        });
        return holder[0];
    }
    private static Session.Outcome mock(Session s,String sql,Map<Integer,Object> binds,Pending state) throws SQLException {
        try {
            var outcome=s.executeMock(sql,new HashMap<>(binds));
            state.mock=true; state.rs=null; state.count=outcome.count(); state.warning=outcome.warning();
            return outcome;
        } catch(SQLException e) { s.server.log("  -> " + e.getMessage()); throw e; }
    }
    private static Object common(Pending state,Statement h2,Statement self,Connection connection,Object p,Method m,Object[] a) throws Throwable {
        switch(m.getName()) {
            case "getConnection": return connection;
            case "getResultSet": if(state.mock) return null; return resultSet((ResultSet)call(h2,m,a),self);
            case "getUpdateCount": if(state.mock) { int c=(int)state.count; state.count=-1; return c; } break;
            case "getLargeUpdateCount": if(state.mock) { long c=state.count; state.count=-1; return c; } break;
            case "getMoreResults": if(state.mock) { state.count=-1; return false; } break;
            case "getWarnings": if(state.mock) return state.warning; break;
            case "clearWarnings": state.warning=null; break;
            case "getGeneratedKeys": return resultSet((ResultSet)call(h2,m,a),self);
            case "executeQuery": return resultSet((ResultSet)call(h2,m,a),self);
            case "isWrapperFor": return ((Class<?>)a[0]).isInstance(p);
            case "unwrap": if(((Class<?>)a[0]).isInstance(p)) return p; break;
            case "toString": return "MockTiberoStatement";
            case "hashCode": return System.identityHashCode(p);
            case "equals": return p==a[0];
            default:
        }
        try { return call(h2,m,a); }
        catch(SQLException e) { throw Session.translate(e,null); }
    }
    static Object prepared(Session s,Connection connection,String sql,Method m,Object[] a) throws Throwable {
        s.server.log("PREP " + sql.replaceAll("\\s+"," "));
        if(Session.classify(sql)!=Session.Kind.SQL) return callable(s,connection,sql);
        var args=a.clone(); args[0]=s.toH2(sql);
        PreparedStatement h2;
        try { h2=(PreparedStatement)call(s.h2,m,args); }
        catch(SQLException e) { throw Session.translate(e,sql); }
        var state=new Pending();
        var holder=new PreparedStatement[1];
        holder[0]=proxy(PreparedStatement.class,(p,method,x) -> {
            String name=method.getName();
            if(name.startsWith("execute") && (x==null || x.length==0)) {
                state.mock=false;
                try { Object r=call(h2,method,x); s.afterH2(sql); return r instanceof ResultSet rs ? resultSet(rs,holder[0]) : r; }
                catch(SQLException e) { throw Session.translate(e,sql); }
            }
            return common(state,h2,holder[0],connection,p,method,x);
        });
        return holder[0];
    }

    // ---- CallableStatement answered by the PL/SQL interpreter ----
    static CallableStatement callable(Session s,Connection connection,String sql) {
        s.server.log("CALL " + sql.replaceAll("\\s+"," "));
        var binds=new HashMap<Integer,Object>();
        var outs=new HashMap<Integer,Object>();
        var state=new Pending();
        var lastNull=new boolean[1];
        var closed=new boolean[1];
        var holder=new CallableStatement[1];
        holder[0]=proxy(CallableStatement.class,(p,m,a) -> {
            String name=m.getName();
            if(name.startsWith("set") && a!=null && a.length>=2 && a[0] instanceof Integer i) {
                binds.put(i,name.equals("setNull") ? null : Interpreter.normalize(a[1] instanceof java.util.Date d && !(a[1] instanceof Timestamp) ? new Timestamp(d.getTime()) : a[1]));
                return null;
            }
            if(name.startsWith("get") && a!=null && a.length>=1 && a[0] instanceof Integer i) {
                Object v=outs.get(i); lastNull[0]=v==null;
                return convert(v,m.getReturnType());
            }
            switch(name) {
                case "registerOutParameter": binds.putIfAbsent((Integer)a[0],null); return null;
                case "execute","executeUpdate","executeLargeUpdate","executeQuery": {
                    var outcome=mock(s,a!=null && a.length>0 && a[0] instanceof String other ? other : sql,binds,state);
                    outs.clear(); outs.putAll(outcome.outs());
                    if(name.equals("executeQuery")) throw new SQLException("[mock] PL/SQL call does not return a result set","07000",-90000);
                    return name.equals("execute") ? false : name.equals("executeLargeUpdate") ? outcome.count() : (Object)(int)outcome.count();
                }
                case "wasNull": return lastNull[0];
                case "clearParameters": binds.clear(); return null;
                case "getConnection": return connection;
                case "getResultSet": return null;
                case "getUpdateCount": { int c=(int)state.count; state.count=-1; return c; }
                case "getLargeUpdateCount": { long c=state.count; state.count=-1; return c; }
                case "getMoreResults": state.count=-1; return false;
                case "getWarnings": return state.warning;
                case "clearWarnings": state.warning=null; return null;
                case "close": closed[0]=true; return null;
                case "isClosed": return closed[0];
                case "getParameterMetaData": throw new SQLFeatureNotSupportedException("[mock] parameter metadata");
                case "getMetaData": return null;
                case "isWrapperFor": return ((Class<?>)a[0]).isInstance(p);
                case "unwrap": return p;
                case "toString": return "MockTiberoCall[" + sql + "]";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p==a[0];
                default: return defaultValue(m.getReturnType());
            }
        });
        return holder[0];
    }
    static Object convert(Object v,Class<?> type) {
        if(type==Object.class) return v;
        if(v==null) return defaultValue(type);
        if(type==String.class) return Interpreter.display(v);
        if(type==int.class || type==Integer.class) return Interpreter.number(v).intValue();
        if(type==long.class || type==Long.class) return Interpreter.number(v).longValue();
        if(type==double.class || type==Double.class) return Interpreter.number(v).doubleValue();
        if(type==float.class || type==Float.class) return Interpreter.number(v).floatValue();
        if(type==short.class || type==Short.class) return Interpreter.number(v).shortValue();
        if(type==byte.class || type==Byte.class) return Interpreter.number(v).byteValue();
        if(type==BigDecimal.class) return Interpreter.number(v);
        if(type==boolean.class || type==Boolean.class) return v instanceof Boolean b ? b : Interpreter.number(v).signum()!=0;
        if(type==Timestamp.class) return v instanceof Timestamp t ? t : null;
        if(type==java.sql.Date.class) return v instanceof Timestamp t ? new java.sql.Date(t.getTime()) : null;
        return v;
    }
    static Object defaultValue(Class<?> type) {
        if(!type.isPrimitive()) return null;
        if(type==boolean.class) return false;
        if(type==void.class) return null;
        if(type==long.class) return 0L;
        if(type==double.class) return 0d;
        if(type==float.class) return 0f;
        return type==int.class ? 0 : type==short.class ? (short)0 : (byte)0;
    }

    // ---- ResultSet with Tibero type names ----
    static ResultSet resultSet(ResultSet rs,Statement statement) {
        if(rs==null) return null;
        return proxy(ResultSet.class,(p,m,a) -> switch(m.getName()) {
            case "getStatement" -> statement;
            case "getMetaData" -> metaData((ResultSetMetaData)call(rs,m,a));
            case "isWrapperFor" -> ((Class<?>)a[0]).isInstance(p) || rs.isWrapperFor((Class<?>)a[0]);
            case "unwrap" -> ((Class<?>)a[0]).isInstance(p) ? p : rs.unwrap((Class<?>)a[0]);
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p==a[0];
            default -> call(rs,m,a);
        });
    }
    static ResultSetMetaData metaData(ResultSetMetaData md) {
        return proxy(ResultSetMetaData.class,(p,m,a) -> {
            if(m.getName().equals("getColumnTypeName")) {
                int i=(Integer)a[0];
                String h2=md.getColumnTypeName(i);
                return switch(h2) {
                    case "CHARACTER VARYING" -> "VARCHAR2";
                    case "CHARACTER" -> "CHAR";
                    case "NUMERIC","DECFLOAT","INTEGER","BIGINT","SMALLINT","TINYINT" -> "NUMBER";
                    case "TIMESTAMP" -> md.getScale(i)==0 ? "DATE" : "TIMESTAMP";
                    case "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMP WITH TIME ZONE";
                    case "CHARACTER LARGE OBJECT" -> "CLOB";
                    case "BINARY LARGE OBJECT" -> "BLOB";
                    case "BINARY VARYING","BINARY" -> "RAW";
                    case "DOUBLE PRECISION" -> "BINARY_DOUBLE";
                    case "REAL" -> "BINARY_FLOAT";
                    default -> h2;
                };
            }
            if(m.getName().equals("getColumnType")) {
                int t=md.getColumnType((Integer)a[0]);
                return t==Types.BIGINT || t==Types.INTEGER || t==Types.SMALLINT || t==Types.TINYINT || t==Types.DECIMAL ? Types.NUMERIC : t;
            }
            return call(md,m,a);
        });
    }

    // ---- DatabaseMetaData: the real Tibero 6 driver SQL, run against the mock dictionary ----
    static DatabaseMetaData metadata(Session s,Connection connection,String url) throws SQLException {
        var h2=s.h2.getMetaData();
        var holder=new DatabaseMetaData[1];
        holder[0]=proxy(DatabaseMetaData.class,(p,m,a) -> {
            String name=m.getName();
            s.server.log("META " + name + (a==null ? "()" : Arrays.toString(a)));
            try {
                Object answer=DriverSql.answer(s,name,a);
                if(answer!=DriverSql.DELEGATE) return answer instanceof ResultSet rs ? resultSet(rs,null) : answer;
            } catch(SQLException e) { s.server.log("  -> " + e.getMessage()); throw e; }
            return switch(name) {
                case "getConnection" -> connection;
                case "getURL" -> url;
                case "getUserName" -> s.user;
                case "isWrapperFor" -> ((Class<?>)a[0]).isInstance(p);
                case "unwrap" -> p;
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p==a[0];
                case "toString" -> "MockTiberoMetaData";
                default -> {
                    Object r=call(h2,m,a);
                    yield r instanceof ResultSet rs ? resultSet(rs,null) : r;
                }
            };
        });
        return holder[0];
    }
}
