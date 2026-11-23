package org.softwood.dag.task.xlsx

import groovy.util.logging.Slf4j
import org.apache.poi.common.usermodel.HyperlinkType
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.FontUnderline
import org.apache.poi.ss.usermodel.FormulaEvaluator
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.ss.util.CellReference
import org.apache.poi.ss.util.RegionUtil
import org.apache.poi.xssf.usermodel.XSSFCell
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFRichTextString
import org.apache.poi.xssf.usermodel.XSSFRow
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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

        File         file = new File(filePath)
        XSSFWorkbook wb   = openOrCreate(plan, file)
        try {
            plan.sheets.each { SheetPlan sp -> writeSheet(wb, sp, plan.styles ?: [:]) }
            plan.namedRanges?.each { String name, String ref ->
                def nr = wb.createName()
                nr.nameName       = name
                nr.refersToFormula = ref
            }
            file.parentFile?.mkdirs()
            new FileOutputStream(file).withCloseable { out -> wb.write(out) }
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

        List result
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

        File         file = new File(filePath)
        XSSFWorkbook wb   = file.exists() ? openExisting(file) : new XSSFWorkbook()
        try {
            plan.sheets.each { SheetPlan sp -> writeSheet(wb, sp, plan.styles ?: [:]) }
            new FileOutputStream(file).withCloseable { out -> wb.write(out) }
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

        File         file = new File(filePath)
        XSSFWorkbook wb   = file.exists() ? openExisting(file) : new XSSFWorkbook()
        try {
            plan.sheets.each { SheetPlan sp ->
                int idx = wb.getSheetIndex(sp.name)
                if (idx >= 0) wb.removeSheetAt(idx)
                writeSheet(wb, sp, plan.styles ?: [:])
            }
            new FileOutputStream(file).withCloseable { out -> wb.write(out) }
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

    private static void writeSheet(XSSFWorkbook wb, SheetPlan plan, Map<String, StyleSpec> styles) {
        XSSFSheet sheet = wb.getSheet(plan.name) ?: wb.createSheet(plan.name)

        if (plan.defaultRowHeight > 0)   sheet.defaultRowHeightInPoints = plan.defaultRowHeight
        if (plan.defaultColumnWidth > 0) sheet.defaultColumnWidth       = plan.defaultColumnWidth
        if (plan.tabColor)               sheet.setTabColor(hexColor(plan.tabColor))

        // Column specs
        plan.columns?.each { ColumnSpec col ->
            if (!col.ref) return
            String[] parts = col.ref.split(':')
            int startC = CellReference.convertColStringToIndex(parts[0])
            int endC   = parts.length > 1 ? CellReference.convertColStringToIndex(parts[1]) : startC
            (startC..endC).each { int c ->
                if (col.width > 0) sheet.setColumnWidth(c, (int)(col.width * 256))
                if (col.hidden)    sheet.setColumnHidden(c, true)
                if (col.autoSize)  sheet.autoSizeColumn(c)
            }
        }

        // Static rows
        int nextRow = 1
        plan.rows?.each { RowSpec rowSpec ->
            int rowIdx = rowSpec.rowIndex != null ? rowSpec.rowIndex - 1 : nextRow - 1
            XSSFRow row = sheet.createRow(rowIdx)
            if (rowSpec.height > 0)  row.heightInPoints = rowSpec.height
            if (rowSpec.hidden)      row.zeroHeight     = true
            if (rowSpec.styleRef && styles[rowSpec.styleRef]) {
                row.rowStyle = buildCellStyle(wb, StyleResolver.resolve(styles[rowSpec.styleRef], styles))
            }
            int autoCol = 0
            rowSpec.cells?.each { CellSpec cs ->
                int ci = cs.colIndex != null ? cs.colIndex - 1
                    : cs.ref             ? (new CellReference(cs.ref)).col as int
                    : autoCol
                writeCell(wb, sheet, row, cs, ci, styles)
                autoCol = ci + 1
            }
            nextRow = rowIdx + 2
        }

        // Range operations
        if (plan.ranges) {
            Map<String, String> allNamedRanges = [:]
            plan.namedRanges?.each { k, v -> allNamedRanges[k] = v }
            applyRanges(wb, sheet, plan.ranges, styles, plan.name, allNamedRanges)
        }

        // Merges
        plan.merges?.each { MergeSpec m ->
            if (m.ref) sheet.addMergedRegion(CellRangeAddress.valueOf(m.ref))
        }

        // Row groups
        plan.rowGroups?.each { GroupSpec g ->
            if (g.range instanceof IntRange) {
                IntRange r = (IntRange) g.range
                sheet.groupRow(r.from - 1, r.to - 1)
                if (g.collapsed) sheet.setRowGroupCollapsed(r.from - 1, true)
            }
        }

        // Column groups
        plan.columnGroups?.each { GroupSpec g ->
            if (g.range instanceof String) {
                String[] parts = ((String) g.range).split(':')
                int startC = CellReference.convertColStringToIndex(parts[0])
                int endC   = parts.length > 1 ? CellReference.convertColStringToIndex(parts[1]) : startC
                sheet.groupColumn(startC, endC)
                if (g.collapsed) sheet.setColumnGroupCollapsed(startC, true)
            }
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
                                   CellSpec spec, int colIdx, Map<String, StyleSpec> styles) {
        XSSFCell cell = row.createCell(colIdx)

        // Value / formula / rich text
        if (spec.formula) {
            cell.setCellFormula(spec.formula)
        } else if (spec.richText) {
            cell.setCellValue(buildRichText(spec.richText as List<RichRun>, wb))
        } else if (spec.value != null) {
            setCellValue(cell, spec.value)
        }

        // Style
        if (spec.styleRef && styles[spec.styleRef]) {
            cell.cellStyle = buildCellStyle(wb, StyleResolver.resolve(styles[spec.styleRef], styles))
        }

        // Comment
        if (spec.comment) {
            def drawing = sheet.createDrawingPatriarch()
            def anchor  = wb.creationHelper.createClientAnchor()
            anchor.col1 = (short) cell.columnIndex
            anchor.row1 = row.rowNum
            anchor.col2 = (short)(cell.columnIndex + 2)
            anchor.row2 = row.rowNum + 3
            def comment = drawing.createCellComment(anchor)
            comment.string = wb.creationHelper.createRichTextString(spec.comment.text ?: '')
            if (spec.comment.author) comment.author = spec.comment.author
            cell.cellComment = comment
        }

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
                                     String sheetName, Map<String, String> namedRanges) {
        ranges.each { RangeSpec r ->
            if (!r.ref) return
            CellRangeAddress addr = CellRangeAddress.valueOf(r.ref)

            // Resolve style once for the whole range
            StyleSpec resolvedStyle = r.inlineStyle
                ? StyleResolver.resolve(r.inlineStyle, styles)
                : (r.styleRef && styles[r.styleRef] ? StyleResolver.resolve(styles[r.styleRef], styles) : null)
            XSSFCellStyle cs = resolvedStyle ? buildCellStyle(wb, resolvedStyle) : null

            for (int rn = addr.firstRow; rn <= addr.lastRow; rn++) {
                XSSFRow row = sheet.getRow(rn) ?: sheet.createRow(rn)
                for (int cn = addr.firstColumn; cn <= addr.lastColumn; cn++) {
                    XSSFCell cell = row.getCell(cn) ?: row.createCell(cn)

                    if (cs) {
                        // Zebra: alternate rows get a slightly modified style (same style for now)
                        cell.cellStyle = cs
                    }

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
        }
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
