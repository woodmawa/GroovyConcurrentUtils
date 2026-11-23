package org.softwood.dag.task.xlsx

import java.time.LocalDate

// =============================================================================
// QUERY PLAN  (immutable intent for READ operations)
// =============================================================================

/**
 * Immutable plan describing a structured read from a spreadsheet.
 * Built by {@link QueryDsl} and passed to the injected {@code xlsxCallFn}
 * inside the config map under key {@code "query"}.
 *
 * <h3>Example config shape seen by the adapter:</h3>
 * <pre>
 *   [filePath: '/data/monthly.xlsx',
 *    action:   'READ',
 *    query:    new QueryPlan(
 *                  sheetName     : 'Summary',
 *                  rangeRef      : 'A1:F500',
 *                  columns       : ['Region','Revenue','Status'],
 *                  rowPredicate  : { row -> row['Status'].asString() in ['Active','Pending'] },
 *                  format        : 'list_of_maps',
 *                  coercions     : [Revenue: Double])]
 * </pre>
 */
class QueryPlan {
    /** Sheet to read from. */
    String             sheetName
    /** A1-notation range, e.g. {@code 'A1:F500'}. {@code null} = whole sheet. */
    String             rangeRef
    /**
     * Optional row-level filter predicate.
     * Receives a {@link SpreadsheetRow}; return {@code true} to include the row.
     */
    Closure            rowPredicate
    /** Columns to include in output (by header name or letter). Empty = all. */
    List<String>       columns   = []
    /**
     * Output format:
     * <ul>
     *   <li>{@code 'list_of_maps'} — {@code List<Map<String,Object>>} (default)</li>
     *   <li>{@code 'list_of_lists'} — {@code List<List<Object>>}</li>
     *   <li>{@code 'map_by_key'} — {@code Map<Object,Map<String,Object>>}, keyed by {@link #keyColumn}</li>
     * </ul>
     */
    String             format    = 'list_of_maps'
    /** Column name used as map key when {@code format = 'map_by_key'}. */
    String             keyColumn
    /**
     * Type coercions applied to column values after reading.
     * Key = column name; Value = target class, e.g. {@code [Revenue: Double, Date: LocalDate]}.
     */
    Map<String, Class> coercions = [:]
}

// =============================================================================
// SPREADSHEET ABSTRACTION  (POI-agnostic; implemented by the adapter)
// =============================================================================

/**
 * Row abstraction passed to {@link QueryPlan#rowPredicate} closures.
 * Implemented at runtime by the injected POI adapter.
 *
 * <p>Cells can be accessed by column letter ({@code row['A']}) or by header
 * name ({@code row['Revenue']}).</p>
 */
interface SpreadsheetRow {
    /** Access a cell by column letter or header name. */
    SpreadsheetCell getAt(String nameOrLetter)
    /** Alias for {@link #getAt}. */
    SpreadsheetCell cell(String nameOrLetter)
    /** 1-based row index within the sheet. */
    int getRowIndex()
}

/**
 * Cell abstraction returned by {@link SpreadsheetRow}.
 * Provides typed accessors so predicate/transform code stays POI-free.
 */
interface SpreadsheetCell {
    /** Raw cell value as returned by the underlying library. */
    Object  getValue()
    /** Cell value coerced to {@link String}. */
    String  asString()
    /** Cell value coerced to {@link Double}; {@code null} if blank. */
    Double  asDouble()
    /** Cell value coerced to {@link Integer}; {@code null} if blank. */
    Integer asInt()
    /** Cell value interpreted as a {@link LocalDate}; {@code null} if blank/invalid. */
    LocalDate asDate()
    /** Cell value coerced to {@link Boolean}. */
    Boolean asBool()
    /** Excel formula string, or {@code null} if the cell is not a formula cell. */
    String  getFormula()
    /** Cell comment text, or {@code null} if no comment is present. */
    String  getComment()
}

// =============================================================================
// QUERY DSL BUILDER
// =============================================================================

/**
 * DSL builder for a structured spreadsheet read query.
 *
 * <h3>Usage in XlsxTask:</h3>
 * <pre>
 *   xlsxTask('load-data') {
 *       file       '/data/monthly.xlsx'
 *       outputKey  'reportData'
 *       query {
 *           sheet    'Summary'
 *           range    'A1:F500'
 *           columns  'Region', 'Revenue', 'Status'
 *           rows     { row -> row['Status'].asString() in ['Active', 'Pending'] }
 *           format   'list_of_maps'
 *           coerce   Revenue: Double
 *       }
 *   }
 * </pre>
 *
 * <p>Calling {@code query { }} on an XlsxTask sets {@code action = READ} and
 * stores the built {@link QueryPlan} on the task.</p>
 */
class QueryDsl {

    private String             _sheetName
    private String             _rangeRef
    private Closure            _rowPredicate
    private final List<String> _columns   = []
    private String             _format    = 'list_of_maps'
    private String             _keyColumn
    private final Map<String, Class> _coercions = [:]

    // -------------------------------------------------------------------------
    // DSL methods
    // -------------------------------------------------------------------------

    /** Sheet to read from. {@code sheet 'Summary'} */
    void sheet(String name) { _sheetName = name }

    /** A1-notation range to read. {@code range 'A1:F500'} */
    void range(String ref) { _rangeRef = ref }

    /**
     * Row-level filter predicate.  The closure receives a {@link SpreadsheetRow}
     * and must return {@code true} to include the row in the result.
     * <pre>rows { row -> row['Status'].asString() == 'Active' }</pre>
     */
    void rows(Closure predicate) { _rowPredicate = predicate }

    /**
     * Columns to include in the output (by header name or letter).
     * Varargs form: {@code columns 'Region', 'Revenue', 'Status'}
     */
    void columns(String... names) { _columns.addAll(names as List) }

    /**
     * Output format.  One of:
     * <ul>
     *   <li>{@code 'list_of_maps'} (default)</li>
     *   <li>{@code 'list_of_lists'}</li>
     *   <li>{@code 'map_by_key'}</li>
     * </ul>
     */
    void format(String fmt) { _format = fmt }

    /**
     * Column to use as map key when {@code format = 'map_by_key'}.
     * {@code keyColumn 'Region'}
     */
    void keyColumn(String col) { _keyColumn = col }

    /**
     * Column-to-type coercion map.
     * <pre>coerce Revenue: Double, Date: LocalDate</pre>
     */
    void coerce(Map<String, Class> map) { _coercions.putAll(map) }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    QueryPlan build() {
        new QueryPlan(
            sheetName   : _sheetName,
            rangeRef    : _rangeRef,
            rowPredicate: _rowPredicate,
            columns     : new ArrayList<>(_columns),
            format      : _format,
            keyColumn   : _keyColumn,
            coercions   : new LinkedHashMap<>(_coercions)
        )
    }
}
