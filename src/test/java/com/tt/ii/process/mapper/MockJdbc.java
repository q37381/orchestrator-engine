package com.tt.ii.process.mapper;

import com.tt.ii.process.config.ProcessEngineConfig;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * mapper 层的**无库夹具**: 把驱动那一层整体换成替身(Mockito 的 DataSource/Connection/
 * PreparedStatement/ResultSet), 上面跑的仍是**真实 MyBatis**(SqlSessionFactoryBean + 六份 XML +
 * typeAliases + 表名变量)。于是 SQL 文本、占位符的绑定顺序与值、resultMap 的下划线列 -> 驼峰属性、
 * useGeneratedKeys 回填都照原路走一遍——宿主环境没有 H2, 单测也不该连真库。
 *
 * <h3>证明范围(必须说清的取舍)</h3>
 *  1) 验得到: 引擎侧接线——发出去的 SQL 长什么样、#{ } 按什么顺序绑了什么值、行数据怎么映射回实体、
 *     default 语义快捷方法委托到哪条语句;
 *  2) 验不到: 库语义——唯一键冲突是否真报错、CAS 是否真命中 0 行、auto_id 顺序、列默认值,
 *     这些由 MySQL 负责, 替身只会回放用例编程给它的答案(所以本夹具不拿它们当结论)。
 * 需要真库回归时, 把 {@link Call#sql()} 记下的语句原样拿到 MySQL 上跑一遍即可。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * MockJdbc jdbc = new MockJdbc();
 * SqlSession session = jdbc.sqlSessionFactory("process_").openSession(true);
 * jdbc.rows("FROM process_inst", row("proc_inst_id", "P1", "def_version", 3));   // 按 SQL 片段编程结果
 * ProcessInstMapper m = session.getMapper(ProcessInstMapper.class);
 * ProcessInstEntity p = m.findById("P1");
 * Assert.assertEquals("P1", p.getProcInstId());
 * Assert.assertEquals(java.util.Collections.singletonList("P1"), jdbc.lastParams());
 * }</pre>
 */
public class MockJdbc {

    /** 一条被执行的语句: 归一化成单行的 SQL + 按占位符顺序绑进去的值。 */
    public static final class Call {
        private final String sql;
        private final Map<Integer, Object> binds = new TreeMap<>();
        private Cursor cursor;
        private ResultSet resultSet;

        Call(String rawSql) {
            this.sql = oneLine(rawSql);
        }

        /** 实际发出去的 SQL(空白折成单行, 与 MapperBindingTest 的口径一致). */
        public String sql() {
            return sql;
        }

        /** 按占位符出现顺序绑进去的值(null 就是 setNull 传下来的空值). */
        public List<Object> params() {
            return new ArrayList<>(binds.values());
        }

        /** 第 n 个占位符绑的值(1 基). */
        public Object param(int oneBased) {
            return params().get(oneBased - 1);
        }

        void bind(int index, Object value) {
            binds.put(index, value);
        }

        Cursor rowsOf(MockJdbc jdbc) {
            if (cursor == null) {
                cursor = new Cursor(jdbc.rowsFor(sql));
            }
            if (resultSet == null) {
                resultSet = MockJdbc.resultSet(cursor);
            }
            return cursor;
        }

        ResultSet resultSet() {
            return resultSet;
        }
    }

    /** JDBC 参数写入方法(其余 set* 是 Statement 自己的属性, 不算占位符). */
    private static final List<String> BINDS = Arrays.asList(
            "setNull", "setString", "setNString", "setInt", "setLong", "setShort", "setByte",
            "setDouble", "setFloat", "setBigDecimal", "setBoolean", "setBytes", "setDate",
            "setTime", "setTimestamp", "setObject");

    private final List<Call> calls = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> rowsByMarker = new LinkedHashMap<>();
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private int updateCount = 1;
    private long generatedKey = 1001L;

    public MockJdbc() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);   // 让事务层不去 commit/rollback
        Answer<PreparedStatement> factory = inv -> statement(inv.getArgument(0));
        when(connection.prepareStatement(anyString())).thenAnswer(factory);
        when(connection.prepareStatement(anyString(), anyInt())).thenAnswer(factory);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenAnswer(factory);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt(), anyInt())).thenAnswer(factory);
        when(connection.prepareStatement(anyString(), any(int[].class))).thenAnswer(factory);
        when(connection.prepareStatement(anyString(), any(String[].class))).thenAnswer(factory);
    }

    // ===================== 装配 =====================

    /** 被替身接管的数据源(给 SqlSessionFactoryBean / 引擎配置类用). */
    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * 在替身数据源上装出真实 MyBatis: 与 {@code ProcessEngineConfig} 同一套口径
     * (domain 包类型别名 + {@code META-INF/mapper/process/*.xml} + 表名变量),
     * 顺带证明那批 XML 在装载期就能解析、通配路径能捞全。
     */
    public SqlSessionFactory sqlSessionFactory(String tablePrefix) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setTypeAliasesPackage("com.tt.ii.process.domain");
        bean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/mapper/process/*.xml"));
        Configuration conf = new Configuration();
        conf.setVariables(ProcessEngineConfig.buildTableVariables(tablePrefix));
        bean.setConfiguration(conf);
        return bean.getObject();
    }

    // ===================== 编程替身 =====================

    /** 给 SQL 里含 {@code marker} 的那条语句回放这几行结果(未编程的语句一律 0 行). */
    @SafeVarargs
    public final MockJdbc rows(String marker, Map<String, Object>... rows) {
        rowsByMarker.put(marker, Arrays.asList(rows));
        return this;
    }

    /** UPDATE/INSERT 的受影响行数(替身只是把它原样透传给 mapper 返回值, 不代表库的判断). */
    public MockJdbc updateCount(int rows) {
        this.updateCount = rows;
        return this;
    }

    /** {@code useGeneratedKeys} 回填给实体的 auto_id 值. */
    public MockJdbc generatedKey(long id) {
        this.generatedKey = id;
        return this;
    }

    /** 一行结果: 键 = 列名(下划线, 自动转小写), 值 = 该列的 Java 值. */
    public static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]).toLowerCase(Locale.ROOT), kv[i + 1]);
        }
        return row;
    }

    // ===================== 取证 =====================

    /** 按执行顺序记录下来的全部语句. */
    public List<Call> calls() {
        return calls;
    }

    public Call last() {
        return calls.isEmpty() ? null : calls.get(calls.size() - 1);
    }

    public String lastSql() {
        Call c = last();
        return c == null ? null : c.sql();
    }

    public List<Object> lastParams() {
        Call c = last();
        return c == null ? Collections.emptyList() : c.params();
    }

    /** 只关心某条语句发了几次时的取法(按 SQL 片段找最后一条). */
    public Call lastContaining(String marker) {
        for (int i = calls.size() - 1; i >= 0; i--) {
            if (calls.get(i).sql().contains(marker)) {
                return calls.get(i);
            }
        }
        return null;
    }

    /** 清空已记录的语句(用例内要分节取证时用). */
    public MockJdbc clearCalls() {
        calls.clear();
        return this;
    }

    /** 撤掉所有已编程的结果行(之后的查询一律 0 行: 用来验"查不到"那条分支). */
    public MockJdbc clearRows() {
        rowsByMarker.clear();
        return this;
    }

    // ===================== 替身内部 =====================

    List<Map<String, Object>> rowsFor(String sql) {
        for (Map.Entry<String, List<Map<String, Object>>> e : rowsByMarker.entrySet()) {
            if (sql.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return Collections.emptyList();
    }

    private PreparedStatement statement(String rawSql) {
        Call call = new Call(rawSql);
        calls.add(call);
        return mock(PreparedStatement.class, inv -> onStatement(call, inv));
    }

    private Object onStatement(Call call, InvocationOnMock inv) throws Throwable {
        String n = inv.getMethod().getName();
        Object[] a = inv.getArguments();
        if (BINDS.contains(n)) {
            // setNull(index, type[, typeName]): 只有索引, 记一个 null 占位, 保证参数序号连续
            call.bind((Integer) a[0], "setNull".equals(n) || a.length < 2 ? null : a[1]);
            return null;
        }
        switch (n) {
            case "execute":
                return !call.rowsOf(this).rows.isEmpty();
            case "executeQuery":
                call.rowsOf(this);
                return call.resultSet();
            case "executeUpdate":
                return updateCount;
            case "getResultSet":
                call.rowsOf(this);
                return call.resultSet();
            case "getUpdateCount":
                return updateCount;
            case "getGeneratedKeys":
                return resultSet(new Cursor(Collections.singletonList(
                        row("auto_id", generatedKey))));
            default:
                return RETURNS_DEFAULTS.answer(inv);
        }
    }

    private static ResultSet resultSet(Cursor c) {
        return mock(ResultSet.class, inv -> {
            String n = inv.getMethod().getName();
            Object[] a = inv.getArguments();
            if ("next".equals(n)) {
                return c.next();
            }
            if ("wasNull".equals(n)) {
                return c.lastWasNull;
            }
            if ("getMetaData".equals(n)) {
                return metaData(c);
            }
            if ("findColumn".equals(n)) {
                return c.indexOf(String.valueOf(a[0])) + 1;
            }
            if (n.startsWith("get") && a.length >= 1
                    && (a[0] instanceof String || a[0] instanceof Integer)) {
                return coerce(n, c.read(a[0]));
            }
            return RETURNS_DEFAULTS.answer(inv);
        });
    }

    private static ResultSetMetaData metaData(Cursor c) {
        return mock(ResultSetMetaData.class, inv -> {
            String n = inv.getMethod().getName();
            int i = inv.getArguments().length > 0 ? (Integer) inv.getArgument(0) : 0;
            switch (n) {
                case "getColumnCount":
                    return c.columns.size();
                case "getColumnLabel":
                case "getColumnName":
                    return c.columns.get(i - 1);
                case "getColumnType":
                    return c.sqlType(i);
                case "getColumnClassName":
                    return c.className(i);
                case "getTableName":
                case "getSchemaName":
                case "getCatalogName":
                    return "";
                case "isNullable":
                    return ResultSetMetaData.columnNullable;
                default:
                    return RETURNS_DEFAULTS.answer(inv);
            }
        });
    }

    /** 按调用方要的类型给值(替身不做真库那种隐式转换, 只把编程进去的值摊平). */
    private static Object coerce(String method, Object v) throws SQLException {
        switch (method) {
            case "getString":
            case "getNString":
                return v == null ? null : String.valueOf(v);
            case "getInt":
                return v == null ? 0 : number(v).intValue();
            case "getLong":
                return v == null ? 0L : number(v).longValue();
            case "getShort":
                return v == null ? 0 : number(v).shortValue();
            case "getDouble":
                return v == null ? 0d : number(v).doubleValue();
            case "getFloat":
                return v == null ? 0f : number(v).floatValue();
            case "getBigDecimal":
                return v == null ? null : number(v);
            case "getBoolean":
                return v != null && (v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)));
            case "getTimestamp":
                return v instanceof LocalDateTime ? Timestamp.valueOf((LocalDateTime) v) : null;
            default:
                return v;        // getObject / getObject(col, Class) 等: 原样给
        }
    }

    private static Number number(Object v) throws SQLException {
        if (v instanceof Number) {
            return (Number) v;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new SQLException("替身列值不是数字: " + v, e);
        }
    }

    static String oneLine(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    /** 结果游标: 列名取第一行的键(已归一小写), 值按列名或 1 基下标读. */
    private static final class Cursor {
        final List<Map<String, Object>> rows;
        final List<String> columns;
        int index = -1;
        boolean lastWasNull;

        Cursor(List<Map<String, Object>> rows) {
            this.rows = rows;
            this.columns = rows.isEmpty()
                    ? Collections.<String>emptyList()
                    : new ArrayList<>(rows.get(0).keySet());
        }

        boolean next() {
            index++;
            return index < rows.size();
        }

        String name(Object key) {
            if (key instanceof Integer) {
                int i = (Integer) key;
                return i >= 1 && i <= columns.size() ? columns.get(i - 1) : null;
            }
            return String.valueOf(key).toLowerCase(Locale.ROOT);
        }

        Object read(Object key) {
            String col = name(key);
            Object v = null;
            if (col != null && index >= 0 && index < rows.size()) {
                v = rows.get(index).get(col);
            }
            lastWasNull = v == null;
            return v;
        }

        int indexOf(String column) {
            return columns.indexOf(column.toLowerCase(Locale.ROOT));
        }

        /** 该列第一个非 null 值的 JDBC 类型(全列皆空时按 VARCHAR 兜底). */
        int sqlType(int oneBased) {
            Object v = firstValue(oneBased);
            if (v instanceof Integer) {
                return java.sql.Types.INTEGER;
            }
            if (v instanceof Long) {
                return java.sql.Types.BIGINT;
            }
            if (v instanceof Boolean) {
                return java.sql.Types.BOOLEAN;
            }
            if (v instanceof LocalDateTime) {
                return java.sql.Types.TIMESTAMP;
            }
            if (v instanceof BigDecimal) {
                return java.sql.Types.DECIMAL;
            }
            return java.sql.Types.VARCHAR;
        }

        String className(int oneBased) {
            Object v = firstValue(oneBased);
            return v == null ? String.class.getName() : v.getClass().getName();
        }

        private Object firstValue(int oneBased) {
            String col = oneBased >= 1 && oneBased <= columns.size() ? columns.get(oneBased - 1) : null;
            if (col == null) {
                return null;
            }
            for (Map<String, Object> row : rows) {
                Object v = row.get(col);
                if (v != null) {
                    return v;
                }
            }
            return null;
        }
    }
}
