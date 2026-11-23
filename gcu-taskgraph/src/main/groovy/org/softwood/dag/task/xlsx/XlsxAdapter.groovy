package org.softwood.dag.task.xlsx

import groovy.util.logging.Slf4j
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.ComparisonOperator
import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.DataValidationConstraint
import org.apache.poi.ss.usermodel.IconMultiStateFormatting
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.FontUnderline
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.ss.util.CellReference
import org.apache.poi.ss.util.RegionUtil
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule
import org.apache.poi.xssf.usermodel.XSSFDataValidationHelper
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.softwood.dag.task.office.OfficeFiles
import org.softwood.dag.task.xlsx.model.LinkSpec
import org.softwood.dag.task.xlsx.model.RichRun

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * POI-backed adapter for Excel (.xlsx) operations.
 *
 * <p>Injected into {@link org.softwood.dag.task.XlsxTask} via
 * {@code task.xlsxCallFn = XlsxAdapter.asFn()}.  The task class remains
 * POI-free; all Apache POI imports live here.</p>
 *
 * <h3>Supported actions:</h3>
 * <ul>
 *   <li>GENERATE  — build a workbook from a {@link WorkbookPlan} and write to disk</li>
 *   <li>READ      — open an existing workbook; return rows as List&lt;Map&gt; per sheet</li>
 *   <li>QUERY     — structured read via {@link QueryPlan} / {@link QueryExecutor}</li>
 *   <li>APPEND    — open an existing workbook and add sheets from a {@link WorkbookPlan}</li>
 *   <li>TRANSFORM — replace named sheets in an existing workbook from a {@link WorkbookPlan}</li>
 * </ul>
 *
 * <p>GCU 2.0.1: every plan field is either applied or refused by name before any file is opened
 * ({@link #checkPlan}); nothing is silently dropped. Sheet {@code deferredRows} take their data from
 * {@code config.data} (a map of dataKey to rows). Files are written atomically ({@link OfficeFiles}).</p>
 */
@Slf4j
class XlsxAdapter {

    /**
     * Return the dispatch closure to inject into an {@link org.softwood.dag.task.XlsxTask}.
     * Signature: {@code { String action, Map config -> Object }}.
     */
    static Closure<Object> asFn() {
        return { String action, Map config ->
            switch (action?.toUpperCase()) {
                case 'GENERATE':  return generate(config)
                case 'READ':      return read(config)
                case 'QUERY':     return query(config)
                case 'APPEND':    return append(config)
                case 'TRANSFORM': return transform(config)
                default: throw new IllegalArgumentException("XlsxAdapter: unknown action '${action}'")
            }
        }
    }

    // =========================================================================
    // Action handlers
    // =========================================================================

    private static Object generate(Map config) {
        WorkbookPlan plan     = config.plan as WorkbookPlan
        String       filePath = config.filePath as String
        if (!plan)     throw new IllegalArgumentException('XlsxAdapter GENERATE: config.plan (WorkbookPlan) is required')
        if (!filePath) throw new IllegalArgumentException('XlsxAdapter GENERATE: config.filePath is required')
        checkPlan(plan, config, 'GENERATE')

        File         file = new File(filePath)
        XSSFWorkbook wb   = openOrCreate(plan, file)
        try {
            plan.sheets.each { SheetPlan sp -> writeSheet(wb, sp, plan.styles ?: [:], dataOf(config)) }
            plan.namedRanges?.each { String name, String ref ->
                def nr = wb.createName()
                nr.nameName       = name
                nr.refersToFormula = ref
            }
            saveWorkbook(wb, plan, file)
        } finally {
            wb.close()
        }
        log.info 'XlsxAdapter GENERATE: wrote {}', filePath
        return [filePath: filePath, sheets: plan.sheets.size()]
    }

    private static Object read(Map config) {
        String filePath = config.filePath as String
        if (!filePath) throw new IllegalArgumentException('XlsxAdapter READ: config.filePath is required')

        Map<String, List> result = [:]
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XSSFWorkbook wb = new XSSFWorkbook(fis)
            try {
                FormulaEvaluator evaluator = wb.creationHelper.createFormulaEvaluator()
                String targetSheet = config.sheetName as String
                List<XSSFSheet> sheets = targetSheet
                    ? [wb.getSheet(targetSheet)].findAll { it != null }
                    : (0..<wb.numberOfSheets).collect { wb.getSheetAt(it) }
                sheets.each { XSSFSheet sh ->
                    result[sh.sheetName] = readSheet(sh, config, evaluator)
                }
            } finally {
                wb.close()
            }
        }
        return result
    }

    private static Object query(Map config) {
        String    filePath = config.filePath as String
        QueryPlan plan     = config.query as QueryPlan
        if (!filePath) throw new IllegalArgumentException('XlsxAdapter QUERY: config.filePath is required')
        if (!plan)     throw new IllegalArgumentException('XlsxAdapter QUERY: config.query (QueryPlan) is required')

        Object result   // 2.0.2: a Map for map_by_key (this was List, so map_by_key failed)
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XSSFWorkbook wb = new XSSFWorkbook(fis)
            try {
                result = QueryExecutor.execute(plan, wb)
            } finally {
                wb.close()
            }
        }
        return result
    }

    private static Object append(Map config) {
        WorkbookPlan plan     = config.plan as WorkbookPlan
        String       filePath = config.filePath as String
        if (!plan)     throw new IllegalArgumentException('XlsxAdapter APPEND: config.plan (WorkbookPlan) is required')
        if (!filePath) throw new IllegalArgumentException('XlsxAdapter APPEND: config.filePath is required')
        checkPlan(plan, config, 'APPEND')

        File         file = new File(filePath)
        XSSFWorkbook wb   = file.exists() ? openExisting(file) : new XSSFWorkbook()
        try {
            plan.sheets.each { SheetPlan sp -> writeSheet(wb, sp, plan.styles ?: [:], dataOf(config)) }
            saveWorkbook(wb, plan, file)
        } finally {
            wb.close()
        }
        return [filePath: filePath, appended: plan.sheets.size()]
    }

    private static Object transform(Map config) {
        WorkbookPlan plan     = config.plan as WorkbookPlan
        String       filePath = config.filePath as String
        if (!plan)     throw new IllegalArgumentException('XlsxAdapter TRANSFORM: config.plan (WorkbookPlan) is required')
        if (!filePath) throw new IllegalArgumentException('XlsxAdapter TRANSFORM: config.filePath is required')
        checkPlan(plan, config, 'TRANSFORM')

        File         file = new File(filePath)
        XSSFWorkbook wb   = file.exists() ? openExisting(file) : new XSSFWorkbook()
        try {
            plan.sheets.each { SheetPlan sp ->
                int idx = wb.getSheetIndex(sp.name)
                if (idx >= 0) wb.removeSheetAt(idx)
                writeSheet(wb, sp, plan.styles ?: [:], dataOf(config))
            }
            saveWorkbook(wb, plan, file)
        } finally {
            wb.close()
        }
        return [filePath: filePath, transformed: plan.sheets.size()]
    }

    // =========================================================================
    // Workbook open helpers
    // =========================================================================

    private static XSSFWorkbook openOrCreate(WorkbookPlan plan, File file) {
        if (plan.templatePath) {
            XSSFWorkbook wb
            new FileInputStream(new File(plan.templatePath)).withCloseable { fis ->
                wb = new XSSFWorkbook(fis)
            }
            return wb
        }
        boolean createIfMissing = plan.options?.getOrDefault('createIfMissing', true) as boolean
        return (file.exists() && !createIfMissing) ? openExisting(file) : new XSSFWorkbook()
    }

    private static XSSFWorkbook openExisting(File file) {
        XSSFWorkbook wb
        new FileInputStream(file).withCloseable { fis -> wb = new XSSFWorkbook(fis) }
        return wb
    }

    // =========================================================================
    // Sheet writing
    // =========================================================================

    private static void writeSheet(XSSFWorkbook wb, SheetPlan plan, Map<String, StyleSpec> styles, Map data) {
        XSSFSheet sheet = wb.getSheet(plan.name) ?: wb.createSheet(plan.name)
        // One XSSFCellStyle per named style: POI caps a workbook at 64000 styles, and one per cell reached it.
        Map<String, XSSFCellStyle> cache = [:]

        if (plan.defaultRowHeight > 0)   sheet.defaultRowHeightInPoints = plan.defaultRowHeight
        if (plan.defaultColumnWidth > 0) sheet.defaultColumnWidth       = plan.defaultColumnWidth
        if (plan.tabColor)               sheet.setTabColor(hexColor(plan.tabColor))

        // 2.0.1: the sheet default style reaches every cell that has no style of its own.
        XSSFCellStyle defaultCs = plan.defaultStyle ? styleFor(wb, cache, plan.defaultStyle, styles) : null

        // Column specs
        plan.columns?.each { ColumnSpec col ->
            if (!col.ref) return
            String[] parts = col.ref.split(':')
            int startC = CellReference.convertColStringToIndex(parts[0])
            int endC   = parts.length > 1 ? CellReference.convertColStringToIndex(parts[1]) : startC
            XSSFCellStyle colCs = col.styleRef ? styleFor(wb, cache, col.styleRef, styles) : null
            (startC..endC).each { int c ->
                if (col.width > 0) sheet.setColumnWidth(c, (int)(col.width * 256))
                if (col.hidden)    sheet.setColumnHidden(c, true)
                if (col.autoSize)  sheet.autoSizeColumn(c)
                if (colCs)         sheet.setDefaultColumnStyle(c, colCs)
            }
            for (int level = 0; level < col.outlineLevel; level++) sheet.groupColumn(startC, endC)
        }

        // Static rows
        int nextRow = 1
        plan.rows?.each { RowSpec rowSpec ->
            int rowIdx = rowSpec.rowIndex != null ? rowSpec.rowIndex - 1 : nextRow - 1
            XSSFRow row = sheet.getRow(rowIdx) ?: sheet.createRow(rowIdx)
            if (rowSpec.height > 0)  row.heightInPoints = rowSpec.height
            if (rowSpec.hidden)      row.zeroHeight     = true
            // 2.0.1: a row style used to be set only as the row's own style, which POI does not give to the cells
            // it writes, so no written cell ever showed it. It is now each cell's style unless the cell names one.
            XSSFCellStyle rowCs = rowSpec.styleRef ? styleFor(wb, cache, rowSpec.styleRef, styles) : null
            if (rowCs) row.rowStyle = rowCs
            int autoCol = 0
            rowSpec.cells?.each { CellSpec cs ->
                int ci = cs.colIndex != null ? cs.colIndex - 1
                    : cs.ref             ? (new CellReference(cs.ref)).col as int
                    : autoCol
                writeCell(wb, sheet, row, cs, ci, styles, cache, rowCs ?: defaultCs)
                autoCol = ci + 1
            }
            nextRow = rowIdx + 2
        }

        // 2.0.1: deferred rows, from config.data (checkPlan has already refused a key with no data).
        plan.deferredRows?.each { DataRowsSpec d ->
            List rowsData = (data[d.dataKey] ?: []) as List
            XSSFCellStyle dcs = d.styleRef ? styleFor(wb, cache, d.styleRef, styles) : defaultCs
            // startAt null: the row after the sheet's last row (XlsxTask APPEND uses this)
            int r0 = d.startAt != null ? d.startAt - 1 : (sheet.physicalNumberOfRows == 0 ? 0 : sheet.lastRowNum + 1)
            rowsData.eachWithIndex { Object rv, int i ->
                XSSFRow row = sheet.getRow(r0 + i) ?: sheet.createRow(r0 + i)
                List vals = rv instanceof Map ? (rv as Map).values().toList() : rv instanceof List ? (rv as List) : [rv]
                vals.eachWithIndex { Object v, int c ->
                    XSSFCell cell = row.getCell(c) ?: row.createCell(c)
                    setCellValue(cell, v)
                    if (dcs) cell.cellStyle = dcs
                }
            }
        }

        // Range operations
        if (plan.ranges) {
            Map<String, String> allNamedRanges = [:]
            plan.namedRanges?.each { k, v -> allNamedRanges[k] = v }
            applyRanges(wb, sheet, plan.ranges, styles, plan.name, allNamedRanges, cache)
        }

        // Merges
        plan.merges?.each { MergeSpec m ->
            if (m.ref) sheet.addMergedRegion(CellRangeAddress.valueOf(m.ref))
        }

        // Row groups: an IntRange (the DSL), a two-element list or an "a:b" string (JSON). 1-based, inclusive.
        plan.rowGroups?.each { GroupSpec g ->
            int[] b = rowGroupBounds(g.range)
            sheet.groupRow(b[0] - 1, b[1] - 1)
            if (g.collapsed) sheet.setRowGroupCollapsed(b[0] - 1, true)
        }

        // Column groups
        plan.columnGroups?.each { GroupSpec g ->
            String[] parts = (g.range as String).split(':')
            int startC = CellReference.convertColStringToIndex(parts[0])
            int endC   = parts.length > 1 ? CellReference.convertColStringToIndex(parts[1]) : startC
            sheet.groupColumn(startC, endC)
            if (g.collapsed) sheet.setColumnGroupCollapsed(startC, true)
        }

        // Freeze pane
        if (plan.freezeRef) {
            CellReference ref = new CellReference(plan.freezeRef)
            sheet.createFreezePane(ref.col as int, ref.row as int)
        }

        // Auto-filter
        if (plan.autoFilterRef) sheet.setAutoFilter(CellRangeAddress.valueOf(plan.autoFilterRef))

        // Sheet protection
        if (plan.password) sheet.protectSheet(plan.password)
        else if (plan.locked) sheet.protectSheet('')

        // Page setup
        Map ps = plan.pageSettings ?: [:]
        if (ps.orientation == 'landscape') sheet.printSetup.landscape = true
        if (ps.fitWidth) {
            sheet.printSetup.fitWidth  = (short)(ps.fitWidth  as int)
            sheet.printSetup.fitHeight = (short)((ps.fitHeight ?: 0) as int)
            sheet.fitToPage = true
        }

        // Sheet-scope named ranges
        plan.namedRanges?.each { String name, String ref ->
            def nr = wb.createName()
            nr.nameName        = name
            nr.sheetIndex      = wb.getSheetIndex(plan.name)
            nr.refersToFormula = "'${plan.name}'!${ref}"
        }
    }

    // -------------------------------------------------------------------------
    // Cell writing
    // -------------------------------------------------------------------------

    private static void writeCell(XSSFWorkbook wb, XSSFSheet sheet, XSSFRow row,
                                   CellSpec spec, int colIdx, Map<String, StyleSpec> styles,
                                   Map<String, XSSFCellStyle> cache, XSSFCellStyle fallbackCs) {
        XSSFCell cell = row.getCell(colIdx) ?: row.createCell(colIdx)

        // Value / formula / rich text
        if (spec.formula) {
            cell.setCellFormula(spec.formula)
        } else if (spec.richText) {
            cell.setCellValue(buildRichText(spec.richText as List<RichRun>, wb))
        } else if (spec.value != null) {
            setCellValue(cell, spec.value)
        }

        // Style: the cell's own, else the row's (or the sheet default) -- 2.0.1
        if (spec.styleRef) {
            cell.cellStyle = styleFor(wb, cache, spec.styleRef, styles)
        } else if (fallbackCs) {
            cell.cellStyle = fallbackCs
        }

        // Comment
        if (spec.comment) addComment(wb, sheet, cell, spec.comment.text, spec.comment.author)

        // Hyperlink
        if (spec.link) {
            HyperlinkType htype
            switch (spec.link.type) {
                case LinkSpec.LinkType.EMAIL:    htype = HyperlinkType.EMAIL;    break
                case LinkSpec.LinkType.FILE:     htype = HyperlinkType.FILE;     break
                case LinkSpec.LinkType.INTERNAL: htype = HyperlinkType.DOCUMENT; break
                default:                         htype = HyperlinkType.URL
            }
            def link = wb.creationHelper.createHyperlink(htype)
            link.address = spec.link.target
            cell.setHyperlink(link)
            if (spec.link.display && !spec.richText) cell.setCellValue(spec.link.display)
        }

        // 2.0.1: image anchored at this cell (checkPlan has already refused a missing file)
        if (spec.imagePath) embedImage(wb, sheet, row.rowNum, colIdx, spec.imagePath, spec.imageType)

        // Rowspan / colspan → merged region
        if (spec.rowspan > 1 || spec.colspan > 1) {
            int r1 = row.rowNum, c1 = colIdx
            sheet.addMergedRegion(new CellRangeAddress(r1, r1 + spec.rowspan - 1, c1, c1 + spec.colspan - 1))
        }

        // Named cell
        if (spec.cellName) {
            def nr = wb.createName()
            nr.nameName        = spec.cellName
            nr.refersToFormula = "'${sheet.sheetName}'!${new CellReference(row.rowNum, colIdx).formatAsString()}"
        }
    }

    // -------------------------------------------------------------------------
    // Range operations
    // -------------------------------------------------------------------------

    private static void applyRanges(XSSFWorkbook wb, XSSFSheet sheet,
                                     List<RangeSpec> ranges, Map<String, StyleSpec> styles,
                                     String sheetName, Map<String, String> namedRanges,
                                     Map<String, XSSFCellStyle> cache) {
        ranges.each { RangeSpec r ->
            if (!r.ref) return
            CellRangeAddress addr = CellRangeAddress.valueOf(r.ref)

            // Resolve style once for the whole range
            XSSFCellStyle cs = r.inlineStyle
                ? buildCellStyle(wb, StyleResolver.resolve(r.inlineStyle, styles))
                : (r.styleRef ? styleFor(wb, cache, r.styleRef, styles) : null)

            // 2.0.1: zebraStripe -- every second row of the range (the 2nd, 4th, ...) gets a light grey fill
            // over the range's own style. Before 2.0.1 the flag was read nowhere.
            XSSFCellStyle zebra = null
            if (r.zebraStripe) {
                zebra = wb.createCellStyle()
                if (cs) zebra.cloneStyleFrom(cs)
                zebra.setFillForegroundColor(hexColor(ZEBRA_FILL))
                zebra.setFillPattern(FillPatternType.SOLID_FOREGROUND)
            }

            for (int rn = addr.firstRow; rn <= addr.lastRow; rn++) {
                XSSFRow row = sheet.getRow(rn) ?: sheet.createRow(rn)
                boolean striped = zebra != null && ((rn - addr.firstRow) % 2 == 1)
                for (int cn = addr.firstColumn; cn <= addr.lastColumn; cn++) {
                    XSSFCell cell = row.getCell(cn) ?: row.createCell(cn)

                    if (striped)  cell.cellStyle = zebra
                    else if (cs)  cell.cellStyle = cs

                    // Write values (row-major List<List>)
                    if (r.values) {
                        int ri = rn - addr.firstRow
                        int ci = cn - addr.firstColumn
                        if (ri < r.values.size()) {
                            def rowVals = r.values[ri]
                            if (rowVals instanceof List && ci < (rowVals as List).size()) {
                                setCellValue(cell, (rowVals as List)[ci])
                            }
                        }
                    }

                    // Per-cell formula
                    if (r.formula) {
                        cell.setCellFormula(FormulaResolver.resolve(r.formula, namedRanges, sheetName))
                    }

                    // Column formula (row-varying)
                    if (r.columnFormula) {
                        String f = FormulaResolver.resolveColumnFormula(r.columnFormula, rn + 1)
                        cell.setCellFormula(FormulaResolver.resolve(f, namedRanges, sheetName))
                    }
                }
            }

            // Perimeter border
            if (r.borders) {
                BorderStyle bs = parseBorderStyle(r.borders)
                RegionUtil.setBorderTop(bs, addr, sheet)
                RegionUtil.setBorderBottom(bs, addr, sheet)
                RegionUtil.setBorderLeft(bs, addr, sheet)
                RegionUtil.setBorderRight(bs, addr, sheet)
            }

            // 2.0.1: a range comment lands on the range's top-left cell
            if (r.comment) {
                XSSFRow first = sheet.getRow(addr.firstRow) ?: sheet.createRow(addr.firstRow)
                XSSFCell c0 = first.getCell(addr.firstColumn) ?: first.createCell(addr.firstColumn)
                addComment(wb, sheet, c0, r.comment, null)
            }

            // 2.0.1: data validation and conditional formatting (both were declared and never applied)
            if (r.dataValidation)    applyValidation(sheet, addr, r.dataValidation)
            if (r.conditionalFormat) applyConditionalFormat(wb, sheet, addr, r.conditionalFormat, styles)
        }
    }

    // =========================================================================
    // 2.0.1: plan check -- every field is applied, or refused here by name before any file is opened
    // =========================================================================

    private static final String ZEBRA_FILL = '#F2F2F2'
    private static final Set<String> SUPPORTED_OPTIONS = ['createIfMissing', 'evaluateFormulas'] as Set
    private static final Set<String> VALIDATION_TYPES  = ['list', 'number', 'integer', 'decimal', 'textLength', 'date', 'formula'] as Set
    private static final Set<String> CF_TYPES          = ['cellValue', 'formula', 'colorScale', 'dataBar', 'iconSet'] as Set
    private static final Set<String> IMAGE_TYPES       = ['PNG', 'JPEG', 'JPG', 'EMF', 'WMF'] as Set
    private static final Set<String> ERROR_STYLES      = ['stop', 'warning', 'information'] as Set
    private static final Map<String, Integer> DV_OPERATORS = [
        between           : DataValidationConstraint.OperatorType.BETWEEN,
        notBetween        : DataValidationConstraint.OperatorType.NOT_BETWEEN,
        equal             : DataValidationConstraint.OperatorType.EQUAL,
        notEqual          : DataValidationConstraint.OperatorType.NOT_EQUAL,
        greaterThan       : DataValidationConstraint.OperatorType.GREATER_THAN,
        lessThan          : DataValidationConstraint.OperatorType.LESS_THAN,
        greaterThanOrEqual: DataValidationConstraint.OperatorType.GREATER_OR_EQUAL,
        lessThanOrEqual   : DataValidationConstraint.OperatorType.LESS_OR_EQUAL
    ]
    private static final Map<String, Byte> CF_OPERATORS = [
        between           : ComparisonOperator.BETWEEN,
        notBetween        : ComparisonOperator.NOT_BETWEEN,
        equal             : ComparisonOperator.EQUAL,
        notEqual          : ComparisonOperator.NOT_EQUAL,
        greaterThan       : ComparisonOperator.GT,
        lessThan          : ComparisonOperator.LT,
        greaterThanOrEqual: ComparisonOperator.GE,
        lessThanOrEqual   : ComparisonOperator.LE
    ]

    /**
     * Refuse a plan that names something this adapter cannot apply, listing every problem at once. Runs before
     * any file is opened, so a refused plan writes nothing. Package-visible so a caller can check a plan early.
     */
    static void checkPlan(WorkbookPlan plan, Map config, String action) {
        List<String> problems = []
        Map<String, StyleSpec> styles = plan.styles ?: [:]
        Map data = dataOf(config)
        Closure styleRef = { String where, String ref ->
            if (ref && !styles.containsKey(ref)) {
                problems << "${where}.styleRef '${ref}' is not in styles (known: ${styles.keySet().sort()})".toString()
            }
        }
        plan.options?.each { Object k, Object v ->
            String key = k as String
            // XlsxWorkbookDsl always writes these two at their defaults: a default is a no-op, so only the value
            // the adapter cannot honour is refused (POI XSSF always uses shared strings; cell styles have no names).
            if (key == 'preserveExistingStyles') {
                if (Boolean.parseBoolean(String.valueOf(v))) problems << 'options.preserveExistingStyles=true is not supported: XSSF cell styles have no names to preserve'
            } else if (key == 'sharedStrings') {
                if (!Boolean.parseBoolean(String.valueOf(v))) problems << 'options.sharedStrings=false is not supported: XSSF always writes shared strings'
            } else if (!SUPPORTED_OPTIONS.contains(key)) {
                problems << "options.${key} is not supported (supported: ${SUPPORTED_OPTIONS.sort()}, preserveExistingStyles=false, sharedStrings=true)".toString()
            }
        }
        if (plan.templatePath && !new File(plan.templatePath).isFile()) problems << "templatePath not found: ${plan.templatePath}".toString()
        plan.sheets.eachWithIndex { SheetPlan sp, int i ->
            String s = "sheets[${i}]"
            if (!sp.name) problems << "${s}.name is required".toString()
            if (sp.defaultStyle && !styles.containsKey(sp.defaultStyle)) {
                problems << "${s}.defaultStyle '${sp.defaultStyle}' is not in styles (known: ${styles.keySet().sort()})".toString()
            }
            sp.columns?.eachWithIndex { ColumnSpec c, int j -> styleRef("${s}.columns[${j}]".toString(), c.styleRef) }
            sp.rows?.eachWithIndex { RowSpec r, int j ->
                styleRef("${s}.rows[${j}]".toString(), r.styleRef)
                r.cells?.eachWithIndex { CellSpec c, int k ->
                    String w = "${s}.rows[${j}].cells[${k}]"
                    styleRef(w, c.styleRef)
                    if (c.imagePath) {
                        if (!new File(c.imagePath).isFile()) problems << "${w}.imagePath: image not found: ${c.imagePath}".toString()
                        if (!IMAGE_TYPES.contains((c.imageType ?: 'PNG').toUpperCase())) {
                            problems << "${w}.imageType '${c.imageType}' is not supported (${IMAGE_TYPES.sort()})".toString()
                        }
                    }
                }
            }
            sp.deferredRows?.eachWithIndex { DataRowsSpec d, int j ->
                String w = "${s}.deferredRows[${j}]"
                if (!d.dataKey)                          problems << "${w}.dataKey is required".toString()
                else if (!data.containsKey(d.dataKey))   problems << "${w}: no data for dataKey '${d.dataKey}' -- pass it in config.data".toString()
                else if (!(data[d.dataKey] instanceof List)) problems << "${w}: config.data['${d.dataKey}'] must be a list of rows".toString()
                styleRef(w, d.styleRef)
            }
            sp.ranges?.eachWithIndex { RangeSpec r, int j ->
                String w = "${s}.ranges[${j}]"
                styleRef(w, r.styleRef)
                DataValidationSpec v = r.dataValidation
                if (v) {
                    if (!VALIDATION_TYPES.contains(v.type)) {
                        problems << "${w}.dataValidation.type '${v.type}' is not supported (${VALIDATION_TYPES.sort()})".toString()
                    } else if (v.type == 'list') {
                        if (!v.list) problems << "${w}.dataValidation.list is required for type list".toString()
                    } else if (v.type == 'formula') {
                        if (!v.formula1) problems << "${w}.dataValidation.formula1 is required for type formula".toString()
                    } else {
                        if (v.operator && !DV_OPERATORS.containsKey(v.operator)) {
                            problems << "${w}.dataValidation.operator '${v.operator}' is not supported (${DV_OPERATORS.keySet().sort()})".toString()
                        }
                        if (!v.formula1) problems << "${w}.dataValidation.formula1 is required for type ${v.type}".toString()
                    }
                    if (!ERROR_STYLES.contains(v.errorStyle)) {
                        problems << "${w}.dataValidation.errorStyle '${v.errorStyle}' is not supported (${ERROR_STYLES.sort()})".toString()
                    }
                }
                ConditionalFormatSpec cf = r.conditionalFormat
                if (cf) {
                    if (!CF_TYPES.contains(cf.type)) {
                        problems << "${w}.conditionalFormat.type '${cf.type}' is not supported (${CF_TYPES.sort()})".toString()
                    } else if (cf.type == 'cellValue') {
                        if (!CF_OPERATORS.containsKey(cf.operator)) {
                            problems << "${w}.conditionalFormat.operator '${cf.operator}' is not supported (${CF_OPERATORS.keySet().sort()})".toString()
                        }
                        if (cf.value1 == null) problems << "${w}.conditionalFormat.value1 is required for type cellValue".toString()
                    } else if (cf.type == 'formula' && !cf.formula) {
                        problems << "${w}.conditionalFormat.formula is required for type formula".toString()
                    }
                    styleRef("${w}.conditionalFormat".toString(), cf.styleRef)
                }
            }
            sp.rowGroups?.eachWithIndex { GroupSpec g, int j ->
                try { rowGroupBounds(g.range) }
                catch (IllegalArgumentException e) { problems << "${s}.rowGroups[${j}].range ${e.message}".toString() }
            }
            sp.columnGroups?.eachWithIndex { GroupSpec g, int j ->
                if (!(g.range instanceof CharSequence)) problems << "${s}.columnGroups[${j}].range must be a column range such as 'C:F'".toString()
            }
        }
        OfficeFiles.refuse("XlsxAdapter ${action}".toString(), problems)
    }

    private static Map dataOf(Map config) {
        (config?.data ?: [:]) as Map
    }

    private static void saveWorkbook(XSSFWorkbook wb, WorkbookPlan plan, File file) {
        if (Boolean.parseBoolean(String.valueOf(plan.options?.get('evaluateFormulas')))) {
            XSSFFormulaEvaluator.evaluateAllFormulaCells(wb)
        }
        OfficeFiles.writeAtomically(file) { OutputStream out -> wb.write(out) }
    }

    private static XSSFCellStyle styleFor(XSSFWorkbook wb, Map<String, XSSFCellStyle> cache, String name,
                                          Map<String, StyleSpec> styles) {
        if (!styles.containsKey(name)) throw new IllegalArgumentException("XlsxAdapter: style '${name}' is not in styles".toString())
        XSSFCellStyle cs = cache[name]
        if (cs == null) {
            cs = buildCellStyle(wb, StyleResolver.resolve(styles[name], styles))
            cache[name] = cs
        }
        return cs
    }

    /** 1-based inclusive row bounds from an IntRange, a two-element list or a "from:to" / "from..to" string. */
    private static int[] rowGroupBounds(Object range) {
        if (range instanceof IntRange) {
            IntRange r = (IntRange) range
            return [r.from as int, r.to as int] as int[]
        }
        if (range instanceof List && ((List) range).size() == 2) {
            List l = (List) range
            return [l[0] as int, l[1] as int] as int[]
        }
        if (range instanceof CharSequence) {
            String[] p = range.toString().split(/:|\.\./)
            if (p.length == 2) return [Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())] as int[]
        }
        throw new IllegalArgumentException("must be an IntRange, a two-element list [from, to] or 'from:to' (1-based rows), got ${range}".toString())
    }

    private static void addComment(XSSFWorkbook wb, XSSFSheet sheet, XSSFCell cell, String text, String author) {
        def drawing = sheet.createDrawingPatriarch()
        def anchor  = wb.creationHelper.createClientAnchor()
        anchor.col1 = (short) cell.columnIndex
        anchor.row1 = cell.rowIndex
        anchor.col2 = (short)(cell.columnIndex + 2)
        anchor.row2 = cell.rowIndex + 3
        def comment = drawing.createCellComment(anchor)
        comment.string = wb.creationHelper.createRichTextString(text ?: '')
        if (author) comment.author = author
        cell.cellComment = comment
    }

    private static void embedImage(XSSFWorkbook wb, XSSFSheet sheet, int rowIdx, int colIdx, String path, String type) {
        File f = new File(path)
        if (!f.isFile()) throw new IllegalArgumentException("XlsxAdapter: image not found: ${path}".toString())
        int pictureType
        switch ((type ?: 'PNG').toUpperCase()) {
            case 'JPEG':
            case 'JPG': pictureType = Workbook.PICTURE_TYPE_JPEG; break
            case 'EMF': pictureType = Workbook.PICTURE_TYPE_EMF;  break
            case 'WMF': pictureType = Workbook.PICTURE_TYPE_WMF;  break
            default:    pictureType = Workbook.PICTURE_TYPE_PNG
        }
        int idx = wb.addPicture(f.bytes, pictureType)
        def drawing = sheet.createDrawingPatriarch()
        def anchor  = wb.creationHelper.createClientAnchor()
        anchor.col1 = colIdx
        anchor.row1 = rowIdx
        anchor.col2 = colIdx + 1
        anchor.row2 = rowIdx + 1
        drawing.createPicture(anchor, idx)
    }

    private static void applyValidation(XSSFSheet sheet, CellRangeAddress addr, DataValidationSpec v) {
        XSSFDataValidationHelper h = new XSSFDataValidationHelper(sheet)
        int op = DV_OPERATORS[v.operator ?: 'between']
        DataValidationConstraint c
        switch (v.type) {
            case 'list':       c = h.createExplicitListConstraint(v.list as String[]); break
            case 'number':
            case 'integer':    c = h.createIntegerConstraint(op, v.formula1, v.formula2); break
            case 'decimal':    c = h.createDecimalConstraint(op, v.formula1, v.formula2); break
            case 'textLength': c = h.createTextLengthConstraint(op, v.formula1, v.formula2); break
            case 'date':       c = h.createDateConstraint(op, v.formula1, v.formula2, null); break
            default:           c = h.createCustomConstraint(v.formula1)
        }
        DataValidation dv = h.createValidation(c, new CellRangeAddressList(addr.firstRow, addr.lastRow, addr.firstColumn, addr.lastColumn))
        dv.showErrorBox = v.showErrorMessage
        dv.errorStyle = [stop: DataValidation.ErrorStyle.STOP, warning: DataValidation.ErrorStyle.WARNING,
                         information: DataValidation.ErrorStyle.INFO][v.errorStyle] as int
        if (v.errorTitle || v.errorMessage) dv.createErrorBox(v.errorTitle ?: '', v.errorMessage ?: '')
        if (v.showInputMessage || v.inputTitle || v.inputMessage) {
            dv.createPromptBox(v.inputTitle ?: '', v.inputMessage ?: '')
            dv.showPromptBox = true
        }
        // XSSF quirk: suppressDropDownArrow=true is what SHOWS the in-cell dropdown for a list
        if (v.type == 'list') dv.suppressDropDownArrow = true
        sheet.addValidationData(dv)
    }

    private static void applyConditionalFormat(XSSFWorkbook wb, XSSFSheet sheet, CellRangeAddress addr,
                                               ConditionalFormatSpec cf, Map<String, StyleSpec> styles) {
        def scf = sheet.sheetConditionalFormatting
        StyleSpec style = cf.styleRef ? StyleResolver.resolve(styles[cf.styleRef], styles) : null
        XSSFConditionalFormattingRule rule
        switch (cf.type) {
            case 'cellValue':
                rule = scf.createConditionalFormattingRule(CF_OPERATORS[cf.operator] as byte, cfValue(cf.value1), cfValue(cf.value2))
                break
            case 'formula':
                rule = scf.createConditionalFormattingRule(cf.formula)
                break
            case 'colorScale':
                // three points (minimum, 50th percentile, maximum): red, yellow, green
                rule = scf.createConditionalFormattingColorScaleRule()
                rule.colorScaleFormatting.colors = [hexColor('#F8696B'), hexColor('#FFEB84'), hexColor('#63BE7B')] as org.apache.poi.ss.usermodel.Color[]
                break
            case 'dataBar':
                rule = scf.createConditionalFormattingRule(hexColor(style?.fill?.foreground ?: '#638EC6'))
                break
            default: // iconSet
                rule = scf.createConditionalFormattingRule(IconMultiStateFormatting.IconSet.GYR_3_TRAFFIC_LIGHTS)
        }
        if (style && (cf.type == 'cellValue' || cf.type == 'formula')) {
            FontSpec f = style.font
            if (f && (f.bold || f.italic || f.underline || f.color)) {
                def ff = rule.createFontFormatting()
                ff.setFontStyle(f.italic, f.bold)
                if (f.color)     ff.fontColor     = hexColor(f.color)
                if (f.underline) ff.underlineType = org.apache.poi.ss.usermodel.Font.U_SINGLE
            }
            if (style.fill?.foreground) {
                def pf = rule.createPatternFormatting()
                pf.fillBackgroundColor = hexColor(style.fill.foreground)
                pf.fillPattern         = org.apache.poi.ss.usermodel.PatternFormatting.SOLID_FOREGROUND
            }
        }
        scf.addConditionalFormatting([addr] as CellRangeAddress[], rule)
    }

    /** A cellValue operand as an Excel formula: numbers as they are, '=...' as the formula, other text quoted. */
    private static String cfValue(Object v) {
        if (v == null) return null
        if (v instanceof Number) return v.toString()
        String s = v.toString()
        if (s.startsWith('=')) return s.substring(1)
        if (s.isNumber())      return s
        return '"' + s.replace('"', '""') + '"'
    }

    // =========================================================================
    // Style building
    // =========================================================================

    private static XSSFCellStyle buildCellStyle(XSSFWorkbook wb, StyleSpec spec) {
        XSSFCellStyle cs = wb.createCellStyle()
        if (!spec) return cs

        // Font
        FontSpec fs = spec.font
        if (fs) {
            XSSFFont font = wb.createFont()
            if (fs.bold)          font.bold              = true
            if (fs.italic)        font.italic            = true
            if (fs.underline)     font.setUnderline(FontUnderline.SINGLE)
            if (fs.strikethrough) font.strikeout         = true
            if (fs.size > 0)      font.fontHeightInPoints = (short) fs.size
            if (fs.color)         font.setColor(hexColor(fs.color))
            if (fs.name)          font.fontName          = fs.name
            cs.setFont(font)
        }

        // Fill
        FillSpec fill = spec.fill
        if (fill?.foreground) {
            cs.setFillForegroundColor(hexColor(fill.foreground))
            cs.setFillPattern(parseFillPattern(fill.pattern ?: 'solid'))
        }
        if (fill?.background) {
            cs.setFillBackgroundColor(hexColor(fill.background))
        }

        // Borders
        BorderSpec border = spec.border
        if (border) {
            XSSFColor bc = border.color ? hexColor(border.color) : null
            applyBorderSide(cs, 'top',    border.top    ?: border.all, bc)
            applyBorderSide(cs, 'bottom', border.bottom ?: border.all, bc)
            applyBorderSide(cs, 'left',   border.left   ?: border.all, bc)
            applyBorderSide(cs, 'right',  border.right  ?: border.all, bc)
        }

        // Alignment
        AlignmentSpec align = spec.alignment
        if (align) {
            if (align.horizontal) cs.setAlignment(parseHAlign(align.horizontal))
            if (align.vertical)   cs.setVerticalAlignment(parseVAlign(align.vertical))
            if (align.wrapText)   cs.setWrapText(true)
            if (align.indent > 0) cs.setIndention((short) align.indent)
            if (align.rotation)   cs.setRotation((short) align.rotation)
        }

        // Data format
        if (spec.dataFormat) cs.dataFormat = wb.createDataFormat().getFormat(spec.dataFormat)

        if (spec.locked) cs.locked = true
        if (spec.hidden) cs.hidden = true

        return cs
    }

    private static void applyBorderSide(XSSFCellStyle cs, String side, String styleStr, XSSFColor color) {
        if (!styleStr) return
        BorderStyle bs = parseBorderStyle(styleStr)
        switch (side) {
            case 'top':
                cs.setBorderTop(bs)
                if (color) cs.setTopBorderColor(color)
                break
            case 'bottom':
                cs.setBorderBottom(bs)
                if (color) cs.setBottomBorderColor(color)
                break
            case 'left':
                cs.setBorderLeft(bs)
                if (color) cs.setLeftBorderColor(color)
                break
            case 'right':
                cs.setBorderRight(bs)
                if (color) cs.setRightBorderColor(color)
                break
        }
    }

    // =========================================================================
    // Sheet reading
    // =========================================================================

    private static List readSheet(XSSFSheet sheet, Map config, FormulaEvaluator evaluator) {
        String rangeRef  = config.cellRange  as String
        String readFmt   = (config.readFormat ?: 'list_of_maps') as String
        String keyCol    = config.keyColumn as String

        int firstRow, lastRow, firstCol, lastCol
        if (rangeRef) {
            CellRangeAddress addr = CellRangeAddress.valueOf(rangeRef)
            firstRow = addr.firstRow;    lastRow  = addr.lastRow
            firstCol = addr.firstColumn; lastCol  = addr.lastColumn
        } else {
            firstRow = sheet.firstRowNum; lastRow = sheet.lastRowNum
            firstCol = 0
            XSSFRow hr = sheet.getRow(firstRow)
            lastCol = hr ? hr.lastCellNum - 1 : 0
        }

        XSSFRow headerRow = sheet.getRow(firstRow)
        List<String> headers = []
        for (int c = firstCol; c <= lastCol; c++) {
            XSSFCell cell = headerRow?.getCell(c)
            headers << (cell ? QueryExecutor.readCellValue(cell, evaluator)?.toString() ?: "Col${c+1}" : "Col${c+1}")
        }

        List rows = []
        for (int r = firstRow + 1; r <= lastRow; r++) {
            XSSFRow row = sheet.getRow(r)
            if (!row) continue
            def rowData = readFmt == 'list_of_lists' ? [] : [:]
            headers.eachWithIndex { String header, int i ->
                XSSFCell cell = row.getCell(firstCol + i)
                Object val    = cell ? QueryExecutor.readCellValue(cell, evaluator) : null
                if (readFmt == 'list_of_lists') (rowData as List) << val
                else (rowData as Map)[header] = val
            }
            rows << rowData
        }

        if (readFmt == 'map_by_key' && keyCol) {
            return rows.collectEntries { Map row -> [(row[keyCol]): row] }
        }
        return rows
    }

    // =========================================================================
    // Helper utilities
    // =========================================================================

    private static XSSFRichTextString buildRichText(List<RichRun> runs, XSSFWorkbook wb) {
        XSSFRichTextString rts = new XSSFRichTextString()
        runs.each { RichRun run ->
            if (!run?.text) return
            XSSFFont font = wb.createFont()
            if (run.bold)        font.bold              = true
            if (run.italic)      font.italic            = true
            if (run.underline)   font.setUnderline(FontUnderline.SINGLE)
            if (run.color)       font.setColor(hexColor(run.color))
            if (run.fontName)    font.fontName          = run.fontName
            if (run.fontSize > 0) font.fontHeightInPoints = (short) run.fontSize
            rts.append(run.text, font)
        }
        return rts
    }

    private static void setCellValue(XSSFCell cell, Object value) {
        if      (value instanceof Boolean)       cell.setCellValue((boolean) value)
        else if (value instanceof Number)        cell.setCellValue(((Number) value).doubleValue())
        else if (value instanceof LocalDate)     cell.setCellValue((LocalDate) value)
        else if (value instanceof LocalDateTime) cell.setCellValue((LocalDateTime) value)
        else if (value instanceof Date)          cell.setCellValue((Date) value)
        else if (value instanceof Calendar)      cell.setCellValue((Calendar) value)
        else                                     cell.setCellValue(value?.toString() ?: '')
    }

    private static XSSFColor hexColor(String hex) {
        if (!hex) return null
        String h = hex.startsWith('#') ? hex.substring(1) : hex
        int r = Integer.parseInt(h.substring(0, 2), 16)
        int g = Integer.parseInt(h.substring(2, 4), 16)
        int b = Integer.parseInt(h.substring(4, 6), 16)
        return new XSSFColor(new byte[]{ (byte) r, (byte) g, (byte) b }, null)
    }

    private static BorderStyle parseBorderStyle(String s) {
        switch (s?.toLowerCase()) {
            case 'thin':    return BorderStyle.THIN
            case 'medium':  return BorderStyle.MEDIUM
            case 'thick':   return BorderStyle.THICK
            case 'dashed':  return BorderStyle.DASHED
            case 'dotted':  return BorderStyle.DOTTED
            case 'double':  return BorderStyle.DOUBLE
            default:        return BorderStyle.THIN
        }
    }

    private static HorizontalAlignment parseHAlign(String s) {
        switch (s?.toLowerCase()) {
            case 'left':             return HorizontalAlignment.LEFT
            case 'center':           return HorizontalAlignment.CENTER
            case 'right':            return HorizontalAlignment.RIGHT
            case 'fill':             return HorizontalAlignment.FILL
            case 'justify':          return HorizontalAlignment.JUSTIFY
            case 'centercontinuous': return HorizontalAlignment.CENTER_SELECTION
            default:                 return HorizontalAlignment.GENERAL
        }
    }

    private static VerticalAlignment parseVAlign(String s) {
        switch (s?.toLowerCase()) {
            case 'top':         return VerticalAlignment.TOP
            case 'middle':
            case 'center':      return VerticalAlignment.CENTER
            case 'bottom':      return VerticalAlignment.BOTTOM
            case 'distributed': return VerticalAlignment.DISTRIBUTED
            default:            return VerticalAlignment.BOTTOM
        }
    }

    private static FillPatternType parseFillPattern(String s) {
        switch (s?.toLowerCase()) {
            case 'solid':    return FillPatternType.SOLID_FOREGROUND
            case 'finedots': return FillPatternType.FINE_DOTS
            case 'altbars':  return FillPatternType.ALT_BARS
            case 'none':     return FillPatternType.NO_FILL
            default:         return FillPatternType.SOLID_FOREGROUND
        }
    }
}