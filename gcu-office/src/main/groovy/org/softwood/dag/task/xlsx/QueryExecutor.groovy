package org.softwood.dag.task.xlsx

import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.time.LocalDate
import java.time.ZoneId

/**
 * Executes a {@link QueryPlan} against an open {@link XSSFWorkbook}.
 *
 * <p>Processing steps:</p>
 * <ol>
 *   <li>Sheet lookup by name (or sheet 0 if not specified)</li>
 *   <li>Range extraction — A1-notation or whole used range</li>
 *   <li>Header row read from the first row of the range</li>
 *   <li>Row-level predicate filtering (via {@link SpreadsheetRow} abstraction)</li>
 *   <li>Column projection by header name or letter</li>
 *   <li>Type coercions applied per-column</li>
 *   <li>Output formatted as list_of_maps | list_of_lists | map_by_key</li>
 * </ol>
 */
class QueryExecutor {

    /**
     * @return a {@code List} for list_of_maps and list_of_lists, a {@code Map} keyed by {@code keyColumn} for
     *         map_by_key. 2.0.2: declared {@code Object} -- it was {@code List}, so map_by_key always failed with a
     *         GroovyCastException.
     */
    static Object execute(QueryPlan plan, XSSFWorkbook wb) {
        XSSFSheet sheet = plan.sheetName ? wb.getSheet(plan.sheetName) : wb.getSheetAt(0)
        if (!sheet) throw new IllegalArgumentException("Sheet '${plan.sheetName}' not found in workbook")

        FormulaEvaluator evaluator = wb.creationHelper.createFormulaEvaluator()

        // ---- Determine row / column bounds ----
        int firstRow, lastRow, firstCol, lastCol
        if (plan.rangeRef) {
            CellRangeAddress addr = CellRangeAddress.valueOf(plan.rangeRef)
            firstRow = addr.firstRow;    lastRow  = addr.lastRow
            firstCol = addr.firstColumn; lastCol  = addr.lastColumn
        } else {
            firstRow = sheet.firstRowNum
            lastRow  = sheet.lastRowNum
            firstCol = 0
            XSSFRow hr = sheet.getRow(firstRow)
            lastCol  = hr ? hr.lastCellNum - 1 : 0
        }

        // ---- Headers ----
        XSSFRow headerRow = sheet.getRow(firstRow)
        List<String> headers = []
        (firstCol..lastCol).each { int c ->
            XSSFCell cell = headerRow?.getCell(c)
            headers << (cell ? cellString(cell, evaluator) : "Col${c + 1}")
        }

        // 2.0.2: map_by_key needs a key column that is a header, checked before any row is read
        if (plan.format == 'map_by_key') {
            if (!plan.keyColumn)
                throw new IllegalArgumentException("QueryPlan.keyColumn is required for format='map_by_key'")
            if (!headers.contains(plan.keyColumn))
                throw new IllegalArgumentException(
                    "QueryExecutor: keyColumn '${plan.keyColumn}' is not a header -- the headers are ${headers}")
            if (plan.columns && !plan.columns.contains(plan.keyColumn))
                throw new IllegalArgumentException(
                    "QueryExecutor: keyColumn '${plan.keyColumn}' must be one of the projected columns ${plan.columns}")
        }

        // ---- Column projection ----
        List<Integer> colIndices   // 0-based offsets from firstCol
        if (plan.columns) {
            // 2.0.1: a column that is neither a header nor a letter inside the range is refused by name.
            // Before 2.0.1 it was dropped silently, so a typo returned rows without that column.
            List<String> unknown = []
            colIndices = []
            plan.columns.each { String nameOrLetter ->
                int idx = headers.indexOf(nameOrLetter)
                if (idx < 0 && nameOrLetter ==~ /[A-Za-z]{1,3}/) {
                    // Try column letter: convert to 0-based absolute, then subtract firstCol
                    int abs = CellReference.convertColStringToIndex(nameOrLetter.toUpperCase())
                    idx = abs - firstCol
                }
                if (idx >= 0 && idx < headers.size()) colIndices << idx
                else unknown << nameOrLetter
            }
            if (unknown) {
                throw new IllegalArgumentException(
                    "QueryExecutor: unknown column(s) ${unknown} -- the headers are ${headers}; a column letter must fall inside the range")
            }
        } else {
            colIndices = (0..<headers.size()).toList()
        }

        // ---- Read data rows ----
        List result = []
        List<Integer> sheetRows = []   // 2.0.3: the 1-based spreadsheet row of each result entry, for messages
        for (int r = firstRow + 1; r <= lastRow; r++) {
            XSSFRow row = sheet.getRow(r)
            if (!row) continue

            // Apply predicate using SpreadsheetRow abstraction
            if (plan.rowPredicate) {
                def srow = new PoiSpreadsheetRow(row, headers, firstCol, evaluator)
                if (!plan.rowPredicate.call(srow)) continue
            }

            // Build output row
            Map<String, Object> rowMap = [:]
            List<Object> rowList = []
            colIndices.each { int idx ->
                String  header = headers[idx]
                XSSFCell cell  = row.getCell(firstCol + idx)
                Object  val    = cell ? readCellValue(cell, evaluator) : null
                if (val != null && plan.coercions?.containsKey(header)) {
                    val = coerce(val, plan.coercions[header])
                }
                rowMap[header] = val
                rowList << val
            }
            // 2.0.3: a row blank in every column returned is not data (formatting or an emptied row); skip it
            if (rowList.every { Object v -> v == null || (v instanceof String && (v as String).trim().isEmpty()) }) continue
            result << (plan.format == 'list_of_lists' ? rowList : rowMap)
            sheetRows << (r + 1)
        }

        // ---- Final format ----
        if (plan.format == 'map_by_key') {
            // 2.0.3: a row with no key is refused naming its spreadsheet row (it became a null map key)
            List<Integer> noKey = []
            result.eachWithIndex { Object row, int i -> if ((row as Map)[plan.keyColumn] == null) noKey << sheetRows[i] }
            if (noKey)
                throw new IllegalArgumentException(
                    "QueryExecutor: map_by_key: keyColumn '${plan.keyColumn}' is empty in row ${noKey.join(', ')}")
            return result.collectEntries { Map row -> [(row[plan.keyColumn]): row] }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Cell value reading
    // -------------------------------------------------------------------------

    static Object readCellValue(XSSFCell cell, FormulaEvaluator evaluator) {
        if (!cell) return null
        CellType type = cell.cellType == CellType.FORMULA
            ? evaluator.evaluateFormulaCell(cell)
            : cell.cellType
        switch (type) {
            case CellType.STRING:  return cell.stringCellValue
            case CellType.NUMERIC:
                return DateUtil.isCellDateFormatted(cell)
                    ? cell.localDateTimeCellValue?.toLocalDate()
                    : cell.numericCellValue
            case CellType.BOOLEAN: return cell.booleanCellValue
            case CellType.BLANK:   return null
            default:               return cell.toString()
        }
    }

    private static String cellString(XSSFCell cell, FormulaEvaluator evaluator) {
        readCellValue(cell, evaluator)?.toString() ?: ''
    }

    // -------------------------------------------------------------------------
    // Type coercion
    // -------------------------------------------------------------------------

    private static Object coerce(Object value, Class target) {
        if (target == Double   || target == double)  return toDouble(value)
        if (target == Integer  || target == int)     return toInt(value)
        if (target == Boolean  || target == boolean) return toBool(value)
        if (target == LocalDate)                     return toLocalDate(value)
        if (target == String)                        return value?.toString()
        return value
    }

    private static Double    toDouble(Object v)    { v instanceof Number ? v.toDouble()  : v?.toString()?.toDouble() }
    private static Integer   toInt(Object v)       { v instanceof Number ? v.toInteger() : v?.toString()?.toInteger() }
    private static Boolean   toBool(Object v)      { v instanceof Boolean ? v : Boolean.parseBoolean(v?.toString()) }
    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate) return v
        if (v instanceof Date)      return v.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        v ? LocalDate.parse(v.toString()) : null
    }
}

// =============================================================================
// POI-backed SpreadsheetRow / SpreadsheetCell implementations (package-private)
// =============================================================================

/**
 * POI-backed implementation of {@link SpreadsheetRow}, used only inside
 * {@link QueryExecutor} when evaluating row predicates.
 */
class PoiSpreadsheetRow implements SpreadsheetRow {
    private final XSSFRow          row
    private final List<String>     headers
    private final int              firstCol
    private final FormulaEvaluator evaluator

    PoiSpreadsheetRow(XSSFRow row, List<String> headers, int firstCol, FormulaEvaluator evaluator) {
        this.row = row; this.headers = headers
        this.firstCol = firstCol; this.evaluator = evaluator
    }

    @Override
    SpreadsheetCell getAt(String nameOrLetter) {
        int idx = headers.indexOf(nameOrLetter)
        if (idx < 0) idx = CellReference.convertColStringToIndex(nameOrLetter) - firstCol
        XSSFCell cell = (idx >= 0) ? row.getCell(firstCol + idx) : null
        return new PoiSpreadsheetCell(cell, evaluator)
    }

    @Override SpreadsheetCell cell(String nameOrLetter) { getAt(nameOrLetter) }
    @Override int             getRowIndex()              { row.rowNum + 1 }
}

/**
 * POI-backed implementation of {@link SpreadsheetCell}.
 */
class PoiSpreadsheetCell implements SpreadsheetCell {
    private final XSSFCell         cell
    private final FormulaEvaluator evaluator

    PoiSpreadsheetCell(XSSFCell cell, FormulaEvaluator evaluator) {
        this.cell = cell; this.evaluator = evaluator
    }

    @Override Object    getValue()   { QueryExecutor.readCellValue(cell, evaluator) }
    @Override String    asString()   { getValue()?.toString() ?: '' }
    @Override Double    asDouble()   { def v = getValue(); v instanceof Number ? v.toDouble()  : v?.toString()?.toDouble() }
    @Override Integer   asInt()      { def v = getValue(); v instanceof Number ? v.toInteger() : v?.toString()?.toInteger() }
    @Override Boolean   asBool()     { def v = getValue(); v instanceof Boolean ? v : Boolean.parseBoolean(v?.toString()) }
    @Override LocalDate asDate() {
        def v = getValue()
        if (v instanceof LocalDate) return v
        if (v instanceof Date)      return v.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        v ? LocalDate.parse(v.toString()) : null
    }
    @Override String getFormula() { cell?.cellType == CellType.FORMULA ? cell.cellFormula : null }
    @Override String getComment() { cell?.cellComment?.string?.string }
}
