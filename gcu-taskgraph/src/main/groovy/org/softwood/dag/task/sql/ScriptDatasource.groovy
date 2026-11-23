package org.softwood.dag.task.sql

import groovy.transform.CompileStatic

/**
 * 1.4.1 (decision D2, {@code allow 'sql'}): a datasource the flow author declares by name in the
 * graph ({@code datasource 'orders', provider}), for restricted scripts to use through {@code sql}.
 *
 * <p>Read-only unless declared {@code writable: true}. Results over {@code maxRows} (10,000 by
 * default) fail the call. The flow author owns the provider: the graph does not close it.</p>
 */
@CompileStatic
final class ScriptDatasource {

    static final int DEFAULT_MAX_ROWS = 10_000

    final String name
    final SqlProvider provider
    final boolean writable
    final int maxRows

    ScriptDatasource(String name, SqlProvider provider, boolean writable = false, int maxRows = DEFAULT_MAX_ROWS) {
        if (!name?.trim()) throw new IllegalArgumentException('datasource: a name is required')
        if (provider == null) throw new IllegalArgumentException("datasource '${name}': an SqlProvider is required".toString())
        if (maxRows < 1) throw new IllegalArgumentException("datasource '${name}': maxRows must be at least 1".toString())
        this.name = name.trim()
        this.provider = provider
        this.writable = writable
        this.maxRows = maxRows
    }
}
