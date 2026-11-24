package org.softwood.dag.task.office

import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFSlideLayout
import org.apache.poi.xslf.usermodel.XSLFSlideMaster
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.softwood.dag.task.docx.DocumentPlan
import org.softwood.dag.task.docx.DocxAdapter
import org.softwood.dag.task.docx.SectionSpec
import org.softwood.dag.task.pptx.PptxAdapter
import org.softwood.dag.task.pptx.PresentationPlan
import org.softwood.dag.task.xlsx.QueryPlan
import org.softwood.dag.task.xlsx.WorkbookPlan
import org.softwood.dag.task.xlsx.XlsxAdapter

import java.awt.Dimension

/**
 * The office tool contract -- GCU 2.1.0 (decision 499): ONE implementation that the MCP servers share.
 *
 * <p>Before 2.1.0 AW ({@code OfficeDocumentService}) and FS ({@code OfficeDocumentHandler}) each kept their own POI
 * code for the plain-map requests their flow nodes and tool calls send, and the copies drifted (AW received none of
 * FS's fixes; the 2026-11-23 office arc took seven restarts). This class is that contract, lifted from AW 1.32.8 (green
 * on office-live-check), with FS's binder, the adapters' heading styles and list numbering, and refusal of every
 * unknown field by name.</p>
 *
 * <p>Plain maps in, the tool-shaped results AW and FS return out. A request carrying a plan ({@code workbookPlan},
 * {@code queryPlan}, {@code documentPlan}, {@code presentationPlan}) -- typed, or the JSON-shaped map a transport
 * delivers -- is bound by {@link OfficePlanBinder} and handed to the adapters.</p>
 *
 * <p>Transport-shaped input: a JSON object's key order does not survive the trip into AW (Intro, Plan, Close arrived as
 * Close, Intro, Plan), so order lives in lists -- docx sections as {@code [{heading, body}]} or {@code [[heading,
 * body]]}, and xlsx map rows are placed by the sheet's header row, never by key order.</p>
 *
 * <pre>
 * xlsx  READ      filePath, sheet|sheetName, range|cellRange, outputFormat|readFormat (list_of_maps | list_of_lists |
 *                 map_by_key), keyColumn                                   -> {headers, data, rowCount, sheet}
 *       READ/QUERY with queryPlan|query                                    -> XlsxAdapter QUERY
 *       WRITE     filePath, sheet|sheetName, headers, rows -- the sheet is replaced, the workbook's other sheets kept
 *       CREATE    as WRITE, always a new workbook
 *       APPEND    filePath, sheet|sheetName, headers, rows -- map rows placed by the header row
 *       TRANSFORM filePath, sheet|sheetName, range|cellRange, rowTransformer (Closure: row map -> row map)
 *       GENERATE / APPEND / TRANSFORM with workbookPlan|plan (+ data)      -> XlsxAdapter
 * docx  READ           filePath                                            -> {sections:[{heading, content}], paragraphCount}
 *       GENERATE       filePath, content, templatePath -- or documentPlan|plan (+ templatePath) -> {path, sections}
 *       APPEND_SECTION filePath, content                                   -> {path, sectionsAppended}
 *       REPLACE_SECTION filePath, targetSection, content                   -> {path, replacedSection}
 *       EXTRACT_TEXT   filePath                                            -> {text, path}
 *       MERGE          as DocxAdapter
 * pptx  READ           filePath            -> {slideCount, slideSize, slides:[{slideIndex, title, content, notes}]}
 *       GENERATE       filePath, slides:[{layout, title, content, notes}], templatePath -- or presentationPlan|plan
 *       ADD_SLIDE      filePath, slides
 *       EXTRACT_TEXT   filePath            -> {text, path, slideCount}
 * </pre>
 */
@Slf4j
@CompileDynamic
final class OfficeTools {

    private static final OfficePlanBinder OPEN = new OfficePlanBinder()

    private OfficeTools() {}

    /** @param binder the caller's path rule for paths inside a request (FS: its allowed directories); default none */
    static Closure<Object> xlsx(OfficePlanBinder binder = null) {
        OfficePlanBinder b = binder ?: OPEN
        return { String action, Map config -> handleXlsx(action, config ?: [:], b) }
    }

    static Closure<Object> docx(OfficePlanBinder binder = null) {
        OfficePlanBinder b = binder ?: OPEN
        return { String action, Map config -> handleDocx(action, config ?: [:], b) }
    }

    static Closure<Object> pptx(OfficePlanBinder binder = null) {
        OfficePlanBinder b = binder ?: OPEN
        return { String action, Map config -> handlePptx(action, config ?: [:], b) }
    }

    // =========================================================================
    // XLSX
    // =========================================================================

    private static final List<String> XLSX_READ  = ['filePath', 'sheet', 'sheetName', 'range', 'cellRange', 'outputFormat', 'readFormat', 'keyColumn']
    private static final List<String> XLSX_WRITE = ['filePath', 'sheet', 'sheetName', 'headers', 'rows']
    private static final List<String> XLSX_XFORM = ['filePath', 'sheet', 'sheetName', 'range', 'cellRange', 'rowTransformer']
    private static final List<String> XLSX_PLAN  = ['filePath', 'workbookPlan', 'plan', 'data']
    private static final List<String> XLSX_QUERY = ['filePath', 'queryPlan', 'query']

    static Object handleXlsx(String action, Map c, OfficePlanBinder b) {
        String a = (action ?: '').toUpperCase()
        String who = "office.xlsx ${a}".toString()
        Object plan = c.workbookPlan ?: c.plan
        if (plan != null) {
            if (!(a in ['GENERATE', 'APPEND', 'TRANSFORM'])) {
                throw new IllegalArgumentException("${who}: a workbookPlan goes with GENERATE, APPEND or TRANSFORM".toString())
            }
            allow(who, c, XLSX_PLAN)
            if (c.data != null && !(c.data instanceof Map)) {
                throw new IllegalArgumentException("${who}: data must map each deferredRows dataKey to its rows".toString())
            }
            WorkbookPlan wp = plan instanceof WorkbookPlan ? plan as WorkbookPlan : b.workbookPlan(plan)
            return XlsxAdapter.asFn().call(a, [filePath: required(who, c), plan: wp, data: (c.data ?: [:]) as Map])
        }
        Object q = c.queryPlan ?: c.query
        if (a == 'QUERY' || (a == 'READ' && q != null)) {
            allow(who, c, XLSX_QUERY)
            QueryPlan qp = q instanceof QueryPlan ? q as QueryPlan : b.queryPlan(q)
            return XlsxAdapter.asFn().call('QUERY', [filePath: required(who, c), query: qp])
        }
        switch (a) {
            case 'READ':      allow(who, c, XLSX_READ);  return readXlsx(who, c)
            case 'WRITE':     allow(who, c, XLSX_WRITE); return writeXlsx(who, c, false)
            case 'CREATE':    allow(who, c, XLSX_WRITE); return writeXlsx(who, c, true)
            case 'APPEND':    allow(who, c, XLSX_WRITE); return appendXlsx(who, c)
            case 'TRANSFORM': allow(who, c, XLSX_XFORM); return transformXlsx(who, c)
            case 'GENERATE':  throw new IllegalArgumentException("${who}: needs a workbookPlan -- or use WRITE with sheet, headers and rows".toString())
            default: throw new IllegalArgumentException("office.xlsx: unknown action '${action}' (READ, WRITE, CREATE, APPEND, TRANSFORM, GENERATE, QUERY)".toString())
        }
    }

    private static Object readXlsx(String who, Map c) {
        String path      = required(who, c)
        String sheetName = pick(c, 'sheet', 'sheetName') as String
        String range     = pick(c, 'range', 'cellRange') as String
        String fmt       = (pick(c, 'outputFormat', 'readFormat') ?: 'list_of_maps') as String
        if (!(fmt in ['list_of_maps', 'list_of_lists', 'map_by_key'])) {
            throw new IllegalArgumentException("${who}: outputFormat '${fmt}' is not one of list_of_lists, list_of_maps, map_by_key".toString())
        }
        Workbook wb = loadFrom(path) { InputStream ins -> new XSSFWorkbook(ins) }
        try {
            Sheet sheet = sheetName ? wb.getSheet(sheetName) : wb.getSheetAt(0)
            if (!sheet) throw new IllegalArgumentException("${who}: sheet '${sheetName}' not found (sheets: ${(0..<wb.numberOfSheets).collect { wb.getSheetName(it) }})".toString())

            List<List<Object>> rawRows = extractRows(sheet, range)
            if (rawRows.isEmpty()) return [headers: [], data: [], rowCount: 0, sheet: sheet.sheetName]

            List<String> headers        = rawRows[0].collect { it?.toString() ?: '' }
            List<List<Object>> dataRows = rawRows.size() > 1 ? rawRows[1..-1] : []
            Object result
            switch (fmt) {
                case 'list_of_lists': result = rawRows; break
                case 'map_by_key':
                    String keyCol = c.keyColumn as String
                    int keyIdx    = headers.indexOf(keyCol)
                    if (keyIdx < 0) throw new IllegalArgumentException("${who}: keyColumn '${keyCol}' is not a header -- the headers are ${headers}".toString())
                    result = dataRows.collectEntries { List row ->
                        [(row.size() > keyIdx ? row[keyIdx] : null): zipRowToMap(headers, row)]
                    }
                    break
                default:
                    result = dataRows.collect { List row -> zipRowToMap(headers, row) }
            }
            return [headers: headers, data: result, rowCount: dataRows.size(), sheet: sheet.sheetName]
        } finally { wb.close() }
    }

    private static Object writeXlsx(String who, Map c, boolean forceNew) {
        String path      = required(who, c)
        String sheetName = (pick(c, 'sheet', 'sheetName') ?: 'Sheet1') as String
        List headers     = c.headers as List
        List rows        = (c.rows ?: []) as List
        if (!headers && rows && rows[0] instanceof Map) headers = (rows[0] as Map).keySet().toList()
        List<List<Object>> rowData = rowsAgainst(who, headers as List<String>, rows)

        File existing = new File(path)
        Workbook wb = (!forceNew && existing.exists()) ? loadFrom(path) { InputStream ins -> new XSSFWorkbook(ins) } : new XSSFWorkbook()
        try {
            int sheetIdx = wb.getSheetIndex(sheetName)
            if (sheetIdx >= 0) wb.removeSheetAt(sheetIdx)
            Sheet sheet = wb.createSheet(sheetName)
            if (sheetIdx >= 0) wb.setSheetOrder(sheetName, sheetIdx)
            int rowNum = 0
            if (headers) writeRow(sheet.createRow(rowNum++), headers)
            rowData.each { List<Object> r -> writeRow(sheet.createRow(rowNum++), r) }
            OfficeFiles.writeAtomically(new File(path)) { OutputStream out -> wb.write(out) }
            return [path: path, sheet: sheetName, rowsWritten: rowData.size()]
        } finally { wb.close() }
    }

    private static Object appendXlsx(String who, Map c) {
        String path      = required(who, c)
        String sheetName = (pick(c, 'sheet', 'sheetName') ?: 'Sheet1') as String
        List rows        = (c.rows ?: []) as List

        File f = new File(path)
        Workbook wb = f.exists() ? loadFrom(path) { InputStream ins -> new XSSFWorkbook(ins) } : new XSSFWorkbook()
        try {
            Sheet sheet = wb.getSheet(sheetName) ?: wb.createSheet(sheetName)
            boolean empty = sheet.physicalNumberOfRows == 0
            List<String> headers = (c.headers as List<String>) ?:
                (!empty ? extractRows(sheet, null)[0].collect { it?.toString() } : null)
            if (!headers && rows && rows[0] instanceof Map) headers = (rows[0] as Map).keySet().toList() as List<String>
            List<List<Object>> rowData = rowsAgainst(who, headers, rows)

            int rowNum = empty ? 0 : sheet.lastRowNum + 1
            if (empty && headers && rows && rows[0] instanceof Map) writeRow(sheet.createRow(rowNum++), headers)
            rowData.each { List<Object> r -> writeRow(sheet.createRow(rowNum++), r) }
            OfficeFiles.writeAtomically(f) { OutputStream out -> wb.write(out) }
            return [path: path, sheet: sheetName, rowsAppended: rowData.size()]
        } finally { wb.close() }
    }

    private static Object transformXlsx(String who, Map c) {
        Closure transformer = c.rowTransformer as Closure
        Map read = readXlsx(who, c.findAll { k, v -> k != 'rowTransformer' }) as Map
        List<Map> data = read.data as List<Map>
        if (transformer) data = data.collect { Map row -> transformer.call(row) as Map }
        return writeXlsx(who, [filePath: c.filePath, sheet: read.sheet, headers: read.headers, rows: data], false)
    }

    /**
     * Rows as cell lists. A map row is placed by {@code headers} -- the sheet's header row, or the caller's -- never by
     * the order its keys arrived in; a key that is not a header is refused by name.
     */
    private static List<List<Object>> rowsAgainst(String who, List<String> headers, List rows) {
        List<String> problems = []
        List<List<Object>> out = []
        rows.eachWithIndex { Object r, int i ->
            if (r instanceof Map) {
                List<String> unknown = (r as Map).keySet().collect { it as String }.findAll { !(it in headers) }
                if (unknown) problems << "rows[${i}]: column(s) ${unknown.collect { "'${it}'" }.join(', ')} not in the header row ${headers}".toString()
                out << headers.collect { String h -> (r as Map)[h] }
            } else if (r instanceof List) {
                out << (r as List<Object>)
            } else {
                problems << "rows[${i}]: a row is a list of cells or a map of header to cell".toString()
            }
        }
        OfficeFiles.refuse(who, problems)
        return out
    }

    private static void writeRow(Row row, List values) {
        values.eachWithIndex { Object val, int i ->
            Cell cell = row.createCell(i)
            if (val instanceof Number)       cell.setCellValue((val as Number).doubleValue())
            else if (val instanceof Boolean) cell.setCellValue((boolean) val)
            else if (val != null)            cell.setCellValue(val.toString())
        }
    }

    private static List<List<Object>> extractRows(Sheet sheet, String range) {
        int firstRow = sheet.firstRowNum
        int lastRow  = sheet.lastRowNum
        int firstCol = 0
        int lastCol  = -1
        if (range) {
            def m = (range =~ /([A-Za-z]+)(\d+):([A-Za-z]+)(\d+)/)
            if (!m.find()) throw new IllegalArgumentException("range '${range}' is not of the form A1:C10".toString())
            firstCol = colLetterToIndex(m.group(1))
            firstRow = (m.group(2) as int) - 1
            lastCol  = colLetterToIndex(m.group(3))
            lastRow  = (m.group(4) as int) - 1
        }
        if (firstRow < 0) return []
        List<List<Object>> rows = []
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r)
            if (!row) { rows << []; continue }
            int end = lastCol >= 0 ? lastCol : (row.lastCellNum - 1)
            List<Object> cells = []
            for (int col = firstCol; col <= end; col++) cells << cellValue(row.getCell(col))
            rows << cells
        }
        return rows
    }

    private static Object cellValue(Cell cell) {
        if (!cell) return null
        switch (cell.cellType) {
            case CellType.STRING:  return cell.stringCellValue
            case CellType.NUMERIC: return DateUtil.isCellDateFormatted(cell) ? cell.dateCellValue.toString() : cell.numericCellValue
            case CellType.BOOLEAN: return cell.booleanCellValue
            case CellType.FORMULA:
                return cell.cachedFormulaResultType == CellType.STRING ? cell.stringCellValue : cell.numericCellValue
            default: return null
        }
    }

    private static Map<String, Object> zipRowToMap(List<String> headers, List<Object> row) {
        Map<String, Object> m = [:]
        headers.eachWithIndex { String h, int i -> m[h] = i < row.size() ? row[i] : null }
        return m
    }

    /**
     * 'A' -> 0, 'AB' -> 27. 2.1.0: AW's copy injected over the String with a char-typed closure -- Groovy hands a String
     * one-character Strings, so every read with a cellRange threw MissingMethodException.
     */
    private static int colLetterToIndex(String col) {
        int acc = 0
        for (char ch : col.toUpperCase().toCharArray()) acc = acc * 26 + ((ch as int) - ('A' as char as int) + 1)
        return acc - 1
    }

    // =========================================================================
    // DOCX
    // =========================================================================

    static Object handleDocx(String action, Map c, OfficePlanBinder b) {
        String a = (action ?: '').toUpperCase()
        String who = "office.docx ${a}".toString()
        switch (a) {
            case 'GENERATE':
            case 'SCRIPT':
                String tpl = c.templatePath != null ? b.templatePath(c.templatePath, 'templatePath') : null
                Object raw = c.documentPlan ?: c.plan
                Map cfg = [filePath: required(who, c)]
                if (tpl) cfg.templatePath = tpl
                if (raw != null) {
                    allow(who, c, ['filePath', 'documentPlan', 'plan', 'templatePath'])
                    cfg.documentPlan = raw instanceof DocumentPlan ? raw : b.documentPlan(raw)
                    DocxAdapter.asFn().call('GENERATE', cfg)
                    return [path: cfg.filePath, sections: (cfg.documentPlan as DocumentPlan).sections.size()]
                }
                allow(who, c, ['filePath', 'content', 'templatePath'])
                List<SectionSpec> sections = sectionsOf(who, c.content)
                cfg.documentPlan = new DocumentPlan(sections: sections)
                DocxAdapter.asFn().call('GENERATE', cfg)
                return [path: cfg.filePath, sections: sections.size()]

            case 'APPEND_SECTION':
                allow(who, c, ['filePath', 'content'])
                List<SectionSpec> added = sectionsOf(who, c.content)
                DocxAdapter.asFn().call('APPEND_SECTION', [filePath: required(who, c), sections: added])
                return [path: c.filePath, sectionsAppended: added.size()]

            case 'REPLACE_SECTION':
                allow(who, c, ['filePath', 'targetSection', 'content'])
                String target = c.targetSection as String
                if (!target) throw new IllegalArgumentException("${who}: targetSection is required".toString())
                Object body = replacementBody(who, target, c.content)
                SectionSpec spec = body instanceof List ? new SectionSpec(bulletList: (body as List).collect { it?.toString() })
                                                        : new SectionSpec(text: body.toString())
                DocxAdapter.asFn().call('REPLACE_SECTION', [filePath: required(who, c), targetSection: target, section: spec])
                return [path: c.filePath, replacedSection: target]

            case 'READ':
                allow(who, c, ['filePath'])
                return readSections(required(who, c))

            case 'EXTRACT_TEXT':
                allow(who, c, ['filePath'])
                XWPFDocument doc = loadFrom(required(who, c)) { InputStream ins -> new XWPFDocument(ins) }
                try {
                    return [text: doc.paragraphs.collect { it.text }.join('\n'), path: c.filePath]
                } finally { doc.close() }

            case 'MERGE':
                return DocxAdapter.asFn().call('MERGE', c)

            default:
                throw new IllegalArgumentException("office.docx: unknown action '${action}' (READ, GENERATE, APPEND_SECTION, REPLACE_SECTION, EXTRACT_TEXT, MERGE)".toString())
        }
    }

    /** Sections, a heading and a body each, as the document reads: a list item's text carries a bullet. */
    private static Map readSections(String path) {
        XWPFDocument doc = loadFrom(path) { InputStream ins -> new XWPFDocument(ins) }
        try {
            List<Map<String, Object>> sections = []
            String heading = null
            List<String> body = []
            doc.paragraphs.each { XWPFParagraph p ->
                String text = p.text ?: ''
                if ((p.style ?: '').toLowerCase().startsWith('heading')) {
                    if (heading != null || !body.isEmpty()) sections << ([heading: heading ?: '', content: body.join('\n')] as Map<String, Object>)
                    heading = text; body = []
                } else if (text.trim()) {
                    body << (p.numID != null ? "• ${text}".toString() : text)
                }
            }
            if (heading != null || !body.isEmpty()) sections << ([heading: heading ?: '', content: body.join('\n')] as Map<String, Object>)
            return [sections: sections, paragraphCount: doc.paragraphs.size()]
        } finally { doc.close() }
    }

    /** The plain content forms as SectionSpecs: a text body is a paragraph, a list body a bullet list. */
    private static List<SectionSpec> sectionsOf(String who, Object content) {
        List<SectionSpec> out = []
        orderedSections(who, content).eachWithIndex { List<Object> s, int i ->
            Object body = s[1]
            if (body instanceof Map) throw new IllegalArgumentException("${who}: content[${i}].body is text or a list of items, not an object".toString())
            out << (body instanceof List
                ? new SectionSpec(heading: s[0] as String, bulletList: (body as List).collect { it?.toString() })
                : new SectionSpec(heading: s[0] as String, text: body?.toString()))
        }
        if (!out) throw new IllegalArgumentException("${who}: content has no sections".toString())
        return out
    }

    /** REPLACE_SECTION content: {heading: body}, the section list (the target's body is used), or the body itself. */
    private static Object replacementBody(String who, String target, Object content) {
        Object body
        if (content instanceof Map) {
            body = (content as Map)[target]
        } else if (content instanceof List && (content as List) && (content as List).every { it instanceof Map && (it as Map).heading != null }) {
            body = orderedSections(who, content).find { List<Object> s -> s[0] == target }?.getAt(1)
        } else {
            body = content
        }
        if (body == null) throw new IllegalArgumentException("${who}: content has no body for section '${target}'".toString())
        if (body instanceof Map) throw new IllegalArgumentException("${who}: the body for '${target}' is text or a list of items, not an object".toString())
        return body
    }

    /**
     * docx content as [heading, body] pairs, in order. {heading: body}, or -- when the order matters, which it does over
     * any JSON transport -- a list of {heading, body} objects or [heading, body] pairs.
     */
    private static List<List<Object>> orderedSections(String who, Object content) {
        if (content == null) return []
        if (content instanceof Map) return (content as Map).collect { Object k, Object v -> [k, v] as List<Object> }
        if (!(content instanceof List)) throw new IllegalArgumentException("${who}: content must be {heading: body} or a list of {heading, body}".toString())
        List<List<Object>> out = []
        (content as List).eachWithIndex { Object e, int i ->
            if (e instanceof Map && (e as Map).heading != null) {
                Map m = e as Map
                List<String> extra = (m.keySet().collect { it as String }) - ['heading', 'body']
                if (extra) throw new IllegalArgumentException("${who}: content[${i}]: unknown field(s) ${extra} (known: heading, body)".toString())
                out << ([m.heading, m.body] as List<Object>)
            } else if (e instanceof List && (e as List).size() == 2) {
                out << ([(e as List)[0], (e as List)[1]] as List<Object>)
            } else {
                throw new IllegalArgumentException("${who}: content[${i}]: each section is {heading, body} or [heading, body]".toString())
            }
        }
        return out
    }

    // =========================================================================
    // PPTX
    // =========================================================================

    private static final List<String> SLIDE_FIELDS = ['content', 'layout', 'notes', 'title']

    static Object handlePptx(String action, Map c, OfficePlanBinder b) {
        String a = (action ?: '').toUpperCase()
        String who = "office.pptx ${a}".toString()
        switch (a) {
            case 'GENERATE':
            case 'SCRIPT':
                Object raw = c.presentationPlan ?: c.plan
                if (raw != null) {
                    allow(who, c, ['filePath', 'presentationPlan', 'plan', 'templatePath'])
                    if (c.templatePath) throw new IllegalArgumentException("${who}: templatePath is not supported with a presentationPlan (PptxAdapter builds a new presentation)".toString())
                    PresentationPlan pp = raw instanceof PresentationPlan ? raw as PresentationPlan : b.presentationPlan(raw)
                    PptxAdapter.asFn().call('GENERATE', [filePath: required(who, c), presentationPlan: pp])
                    return [path: c.filePath, slidesWritten: pp.slides.size()]
                }
                allow(who, c, ['filePath', 'slides', 'templatePath'])
                List<Map<String, Object>> slides = slidesOf(who, c.slides)
                String tpl = c.templatePath != null ? b.templatePath(c.templatePath, 'templatePath') : null
                String path = required(who, c)
                XMLSlideShow ppt = tpl ? loadFrom(tpl) { InputStream ins -> new XMLSlideShow(ins) } : new XMLSlideShow()
                try {
                    writeSlides(who, ppt, slides)
                    OfficeFiles.writeAtomically(new File(path)) { OutputStream out -> ppt.write(out) }
                    return [path: path, slidesWritten: slides.size()]
                } finally { ppt.close() }

            case 'ADD_SLIDE':
                allow(who, c, ['filePath', 'slides'])
                List<Map<String, Object>> more = slidesOf(who, c.slides)
                String p = required(who, c)
                XMLSlideShow deck = new File(p).exists() ? loadFrom(p) { InputStream ins -> new XMLSlideShow(ins) } : new XMLSlideShow()
                try {
                    writeSlides(who, deck, more)
                    OfficeFiles.writeAtomically(new File(p)) { OutputStream out -> deck.write(out) }
                    return [path: p, slidesAdded: more.size(), totalSlides: deck.slides.size()]
                } finally { deck.close() }

            case 'READ':
                allow(who, c, ['filePath'])
                return readPptx(required(who, c))

            case 'EXTRACT_TEXT':
                allow(who, c, ['filePath'])
                XMLSlideShow ss = loadFrom(required(who, c)) { InputStream ins -> new XMLSlideShow(ins) }
                try {
                    List<String> texts = []
                    ss.slides.each { XSLFSlide slide -> slide.shapes.each { shape -> String t = shapeText(shape); if (t) texts << t } }
                    return [text: texts.join('\n'), path: c.filePath, slideCount: ss.slides.size()]
                } finally { ss.close() }

            default:
                throw new IllegalArgumentException("office.pptx: unknown action '${action}' (READ, GENERATE, ADD_SLIDE, EXTRACT_TEXT)".toString())
        }
    }

    private static List<Map<String, Object>> slidesOf(String who, Object raw) {
        if (!(raw instanceof List) || !(raw as List)) throw new IllegalArgumentException("${who}: slides must be a non-empty list of {layout, title, content, notes}".toString())
        List<String> problems = []
        (raw as List).eachWithIndex { Object s, int i ->
            if (!(s instanceof Map)) { problems << "slides[${i}]: a slide is {layout, title, content, notes}".toString(); return }
            List<String> unknown = (s as Map).keySet().collect { it as String }.findAll { !(it in SLIDE_FIELDS) }
            if (unknown) problems << "slides[${i}]: unknown field(s) ${unknown.collect { "'${it}'" }.join(', ')} (known: ${SLIDE_FIELDS.join(', ')})".toString()
        }
        OfficeFiles.refuse(who, problems)
        return raw as List<Map<String, Object>>
    }

    private static Map readPptx(String path) {
        XMLSlideShow ppt = loadFrom(path) { InputStream ins -> new XMLSlideShow(ins) }
        try {
            List<Map<String, Object>> slides = []
            ppt.slides.eachWithIndex { XSLFSlide slide, int idx ->
                String title = ''
                try { title = slide.title ?: '' } catch (Exception ignored) {}
                List<String> body = []
                slide.shapes.each { shape -> String t = shapeText(shape)?.trim(); if (t && t != title) body << t }
                String notes = ''
                try { notes = slide.notes?.placeholders?.find { it.textType == Placeholder.BODY }?.text?.trim() ?: '' } catch (Exception ignored) {}
                slides << ([slideIndex: idx, title: title, content: body.join('\n'), notes: notes] as Map<String, Object>)
            }
            Dimension size = ppt.pageSize
            return [slideCount: slides.size(), slideSize: [width: size?.width ?: 0, height: size?.height ?: 0], slides: slides]
        } finally { ppt.close() }
    }

    private static String shapeText(Object shape) {
        try { return shape.hasProperty('text') ? shape.text : null } catch (Exception ignored) { return null }
    }

    private static final Set<Placeholder> NON_BODY = [Placeholder.DATETIME, Placeholder.FOOTER, Placeholder.SLIDE_NUMBER, Placeholder.HEADER] as Set

    /**
     * One slide writer for GENERATE and ADD_SLIDE (FS 0.11.6-0.11.8, AW 1.32.6). A named layout must exist (matched
     * without case or the space/underscore difference); a title, content or notes the layout has no place for is
     * refused naming the slide; notes go into the notes slide. Nothing is written when anything is refused.
     */
    private static void writeSlides(String who, XMLSlideShow ppt, List<Map<String, Object>> slides) {
        XSLFSlideMaster master = ppt.slideMasters[0]
        List<String> known = master.slideLayouts.collect { XSLFSlideLayout l -> l.name as String }
        List<String> unknown = slides.findAll { Map s -> s.layout && !known.any { String k -> layoutKey(k) == layoutKey(s.layout as String) } }
                                     .collect { Map s -> s.layout as String }
        if (unknown) throw new IllegalArgumentException("${who}: nothing written -- unknown slide layout(s) ${unknown} -- this presentation has ${known}".toString())
        List<String> problems = []
        slides.eachWithIndex { Map<String, Object> spec, int si ->
            String layoutName = (spec.layout ?: 'TITLE_AND_CONTENT') as String
            XSLFSlideLayout layout = master.slideLayouts.find { layoutKey(it.name as String) == layoutKey(layoutName) } ?: master.slideLayouts[0]
            XSLFSlide slide = ppt.createSlide(layout)
            List<XSLFTextShape> phs = slide.placeholders.findAll { it instanceof XSLFTextShape }.collect { it as XSLFTextShape }
            XSLFTextShape titlePh = phs.find { it.textType == Placeholder.TITLE || it.textType == Placeholder.CENTERED_TITLE }
            XSLFTextShape bodyPh = phs.find { it.textType == Placeholder.BODY || it.textType == Placeholder.CONTENT } ?:
                phs.find { it != titlePh && !(it.textType in NON_BODY) }
            if (spec.title) {
                if (titlePh) titlePh.text = spec.title as String
                else problems << "slides[${si}]: layout '${layout.name}' has no title placeholder for the title".toString()
            }
            if (spec.content) {
                Object body = spec.content
                if (bodyPh) bodyPh.text = body instanceof List ? (body as List).join('\n') : body.toString()
                else problems << "slides[${si}]: layout '${layout.name}' has no body placeholder for the content -- use Title and Content".toString()
            }
            if (spec.notes) {
                XSLFTextShape notesPh = ppt.getNotesSlide(slide).placeholders.find { it.textType == Placeholder.BODY } as XSLFTextShape
                if (notesPh) notesPh.text = spec.notes as String
                else problems << "slides[${si}]: the notes slide has no body placeholder for the notes".toString()
            }
        }
        OfficeFiles.refuse(who, problems)
    }

    /** 'Title and Content' == TITLE_AND_CONTENT. */
    private static String layoutKey(String name) {
        name == null ? '' : name.trim().toUpperCase().replace(' ', '_')
    }

    // =========================================================================
    // Shared
    // =========================================================================

    /** Refuse, by name, every key the action does not read -- a misspelt field must never be dropped silently. */
    private static void allow(String who, Map c, List<String> known) {
        List<String> unknown = c.keySet().collect { it as String }.findAll { !(it in known) }
        if (unknown) {
            throw new IllegalArgumentException("${who}: unknown field(s) ${unknown.collect { "'${it}'" }.join(', ')} (known: ${known.sort(false).join(', ')})".toString())
        }
    }

    private static String required(String who, Map c) {
        String p = c.filePath as String
        if (!p) throw new IllegalArgumentException("${who}: filePath is required".toString())
        return p
    }

    private static Object pick(Map c, String... names) {
        for (String n : names) { if (c.containsKey(n) && c[n] != null) return c[n] }
        return null
    }

    /** Open from a stream that is closed once POI has read it (new XSSFWorkbook(File) kept the file open). */
    private static <T> T loadFrom(String path, Closure<T> open) {
        new File(path).withInputStream { InputStream ins -> open.call(ins) }
    }
}
