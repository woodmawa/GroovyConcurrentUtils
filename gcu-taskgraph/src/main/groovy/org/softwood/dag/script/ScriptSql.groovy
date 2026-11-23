package org.softwood.dag.script

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.shell.ShellGuard
import org.softwood.dag.task.sql.ScriptDatasource

/**
 * 1.4.1 (decision D2, level 2): what {@code allow 'sql'} gives a restricted script, bound as
 * {@code sql}.
 *
 * <p>Every call names a datasource the graph declared ({@code datasource 'orders', provider}), a
 * statement with {@code ?} placeholders and its parameters:</p>
 * <pre>
 *   def rows = sql.query('orders', 'select name from orders where qty > ?', [2])
 *   def row  = sql.queryForMap('orders', 'select * from orders where id = ?', [id])
 *   def n    = sql.queryForObject('orders', 'select count(*) from orders')
 *   sql.update('orders', 'update orders set qty = ? where id = ?', [5, id])   // writable only
 * </pre>
 * <ul>
 *   <li>A script cannot supply a URL, driver or provider, and cannot reach the provider, a
 *       connection or the list of datasources ({@link ScriptCapability}).</li>
 *   <li>One statement per call ({@link SqlStatementCheck}).</li>
 *   <li>Queries start with SELECT or WITH and contain no write word. {@code update} needs a
 *       datasource declared {@code writable: true} and takes INSERT, UPDATE, DELETE or MERGE.</li>
 *   <li>A query result over the datasource's {@code maxRows} fails the call. The rows are fetched
 *       before they are counted: the cap limits what the script gets, not what the database reads.</li>
 *   <li>Each call leaves an audit record: datasource, statement hash, rows.</li>
 *   <li>A refusal is a {@link SecurityException} and is not retried.</li>
 * </ul>
 * <p>There is no query timeout of its own: {@code scriptTimeout} abandons a long call, it does not
 * cancel it on the database.</p>
 */
class ScriptSql implements ScriptCapability {

    private static final Set<String> OFFERED = Collections.unmodifiableSet(
            new LinkedHashSet<String>(['query', 'queryForMap', 'queryForObject', 'update']))

    private final TaskContext ctx
    private final String taskId

    ScriptSql(TaskContext ctx, String taskId) {
        this.ctx = ctx
        this.taskId = taskId
    }

    @Override
    String capabilityName() { 'sql' }

    @Override
    Set<String> offeredMethods() { OFFERED }

    List<Map<String, Object>> query(String datasource, String statement, List params = []) {
        ScriptDatasource ds = named(datasource)
        SqlStatementCheck.Scanned s = SqlStatementCheck.requireRead(statement)
        List<Map<String, Object>> rows = ds.provider.query(s.statement, values(params))
        int count = rows == null ? 0 : rows.size()
        audit(ds, 'query', statement, count)
        if (count > ds.maxRows) {
            throw new SecurityException("sql: the query on '${ds.name}' returned ${count} rows, over the limit of ${ds.maxRows} rows")
        }
        return rows ?: []
    }

    Map<String, Object> queryForMap(String datasource, String statement, List params = []) {
        ScriptDatasource ds = named(datasource)
        SqlStatementCheck.Scanned s = SqlStatementCheck.requireRead(statement)
        Map<String, Object> row = ds.provider.queryForMap(s.statement, values(params))
        audit(ds, 'queryForMap', statement, row == null ? 0 : 1)
        return row
    }

    Object queryForObject(String datasource, String statement, List params = []) {
        ScriptDatasource ds = named(datasource)
        SqlStatementCheck.Scanned s = SqlStatementCheck.requireRead(statement)
        Object value = ds.provider.queryForObject(s.statement, values(params))
        audit(ds, 'queryForObject', statement, value == null ? 0 : 1)
        return value
    }

    int update(String datasource, String statement, List params = []) {
        ScriptDatasource ds = named(datasource)
        if (!ds.writable) {
            throw new SecurityException("sql: datasource '${ds.name}' is read-only: the graph must declare it writable: true")
        }
        SqlStatementCheck.Scanned s = SqlStatementCheck.requireWrite(statement)
        int changed = ds.provider.executeUpdate(s.statement, values(params))
        audit(ds, 'update', statement, changed)
        return changed
    }

    private ScriptDatasource named(String name) {
        ScriptDatasource ds = name ? ctx?.datasources?.get(name) : null
        if (ds == null) {
            throw new SecurityException("sql: '${name}' is not a datasource this graph declares")
        }
        return ds
    }

    private static List<Object> values(List params) {
        return params == null ? [] : new ArrayList<Object>(params)
    }

    private void audit(ScriptDatasource ds, String operation, String statement, int rows) {
        ctx?.audit([kind           : 'sql',
                    taskId         : taskId,
                    datasource     : ds.name,
                    operation      : operation,
                    statementSha256: ShellGuard.sha256(statement),
                    rows           : rows,
                    writable       : ds.writable] as Map<String, Object>)
    }
}
