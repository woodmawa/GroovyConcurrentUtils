package org.softwood.dag.task.docx

import groovy.util.logging.Slf4j
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.apache.poi.xwpf.usermodel.XWPFTableRow
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation
import org.softwood.dag.task.xlsx.StyleResolver
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.model.RichRun

import java.math.BigInteger

/**
 * POI-backed adapter for Word (.docx) operations.
 *
 * <p>Injected into {@link org.softwood.dag.task.DocxTask} via
 * {@code task.docxCallFn = DocxAdapter.asFn()}.  The task class remains
 * POI-free; all Apache POI XWPF imports live here.</p>
 *
 * <h3>Supported actions:</h3>
 * <ul>
 *   <li>GENERATE     — build a document from a {@link DocumentPlan} and write to disk</li>
 *   <li>SCRIPT       — alias for GENERATE when document() DSL closure was used</li>
 *   <li>READ         — open document; return metadata + per-paragraph text map</li>
 *   <li>EXTRACT_TEXT — open document; return all body text as a single String</li>
 *   <li>MERGE        — concatenate multiple .docx files into one output file</li>
 * </ul>
 *
 * <p>Reuses {@link StyleResolver} from the xlsx package — no duplication.</p>
 */
@Slf4j
class DocxAdapter {

    /**
     * Return the dispatch closure to inject into a
     * {@link org.softwood.dag.task.DocxTask}.
     * Signature: {@code { String action, Map config -> Object }}.
     */
    static Closure<Object> asFn() {
        return { String action, Map config ->
            switch (action?.toUpperCase()) {
                case 'GENERATE':
                case 'SCRIPT':       return generate(config)
                case 'READ':         return read(config)
                case 'EXTRACT_TEXT': return extractText(config)
                case 'MERGE':        return merge(config)
                default: throw new IllegalArgumentException("DocxAdapter: unknown action '${action}'")
            }
        }
    }

    // =========================================================================
    // Action handlers
    // =========================================================================

    private static Object generate(Map config) {
        DocumentPlan plan     = (config.documentPlan ?: config.plan) as DocumentPlan
        String       filePath = config.filePath as String
        if (!plan)     throw new IllegalArgumentException('DocxAdapter GENERATE: config.documentPlan (DocumentPlan) is required')
        if (!filePath) throw new IllegalArgumentException('DocxAdapter GENERATE: config.filePath is required')

        XWPFDocument doc = config.templatePath
            ? openTemplate(config.templatePath as String)
            : new XWPFDocument()
        try {
            applyDocumentPlan(doc, plan)
            File file = new File(filePath)
            file.parentFile?.mkdirs()
            new FileOutputStream(file).withCloseable { out -> doc.write(out) }
        } finally {
            doc.close()
        }
        log.info 'DocxAdapter GENERATE: wrote {}', filePath
        return [filePath: filePath, sections: plan.sections.size()]
    }

    private static Object read(Map config) {
        String filePath = config.filePath as String
        if (!filePath) throw new IllegalArgumentException('DocxAdapter READ: config.filePath is required')

        Map result = [paragraphs: [], tables: [], metadata: [:]]
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XWPFDocument doc = new XWPFDocument(fis)
            try {
                // Core properties
                def cp = doc.properties?.coreProperties
                if (cp) {
                    result.metadata = [
                        title       : cp.title,
                        subject     : cp.subject,
                        description : cp.description,
                        creator     : cp.creator,
                        lastModifiedBy: cp.lastModifiedByUser
                    ].findAll { k, v -> v != null }
                }
                // Paragraphs
                doc.paragraphs.each { XWPFParagraph p ->
                    String text = p.text
                    if (text) result.paragraphs << [style: p.style, text: text]
                }
                // Tables
                doc.tables.each { XWPFTable t ->
                    List<List<String>> rows = t.rows.collect { XWPFTableRow row ->
                        row.tableCells.collect { XWPFTableCell cell -> cell.text }
                    }
                    result.tables << rows
                }
            } finally {
                doc.close()
            }
        }
        return result
    }

    private static Object extractText(Map config) {
        String filePath = config.filePath as String
        if (!filePath) throw new IllegalArgumentException('DocxAdapter EXTRACT_TEXT: config.filePath is required')

        StringBuilder sb = new StringBuilder()
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XWPFDocument doc = new XWPFDocument(fis)
            try {
                doc.paragraphs.each { XWPFParagraph p ->
                    String t = p.text
                    if (t) sb.append(t).append('\n')
                }
            } finally {
                doc.close()
            }
        }
        return sb.toString()
    }

    private static Object merge(Map config) {
        List<String> inputPaths = config.filePaths as List<String>
        String outputPath       = config.outputPath as String ?: config.filePath as String
        if (!inputPaths) throw new IllegalArgumentException('DocxAdapter MERGE: config.filePaths (List<String>) is required')
        if (!outputPath) throw new IllegalArgumentException('DocxAdapter MERGE: config.outputPath is required')

        XWPFDocument merged = new XWPFDocument()
        try {
            inputPaths.each { String path ->
                new FileInputStream(new File(path)).withCloseable { fis ->
                    XWPFDocument src = new XWPFDocument(fis)
                    try {
                        // Copy paragraphs as plain text (structural merge)
                        src.paragraphs.each { XWPFParagraph p ->
                            XWPFParagraph np = merged.createParagraph()
                            np.style = p.style
                            XWPFRun run = np.createRun()
                            run.setText(p.text)
                        }
                        // Add a page break between documents
                        XWPFParagraph pb = merged.createParagraph()
                        pb.createRun().addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE)
                    } finally {
                        src.close()
                    }
                }
            }
            File out = new File(outputPath)
            out.parentFile?.mkdirs()
            new FileOutputStream(out).withCloseable { os -> merged.write(os) }
        } finally {
            merged.close()
        }
        return [outputPath: outputPath, merged: inputPaths.size()]
    }

    // =========================================================================
    // Document plan application
    // =========================================================================

    private static void applyDocumentPlan(XWPFDocument doc, DocumentPlan plan) {
        // Core properties
        if (plan.title || plan.properties) {
            def cp = doc.properties.coreProperties
            if (plan.title)                      cp.title       = plan.title
            if (plan.properties?.author)         cp.creator     = plan.properties.author
            if (plan.properties?.subject)        cp.setSubject(plan.properties.subject)
            if (plan.properties?.description)    cp.setDescription(plan.properties.description)
            if (plan.properties?.keywords)       cp.keywords    = plan.properties.keywords
        }

        // Sections
        plan.sections.each { SectionSpec spec ->
            writeSection(doc, spec, plan.styles ?: [:])
        }

        // Page setup
        if (plan.page) applyPageSetup(doc, plan.page)
    }

    private static void writeSection(XWPFDocument doc, SectionSpec spec, Map<String, StyleSpec> styles) {
        // Heading
        if (spec.heading) {
            XWPFParagraph h = doc.createParagraph()
            h.style = "Heading${spec.headingLevel}"
            XWPFRun run = h.createRun()
            run.setText(spec.heading)
        }

        // Rich text paragraph (takes precedence over plain text)
        if (spec.richText) {
            XWPFParagraph p = doc.createParagraph()
            applyParagraphStyle(p, spec.style, styles)
            spec.richText.each { RichRun rr ->
                if (!rr?.text) return
                XWPFRun run = p.createRun()
                run.setText(rr.text)
                if (rr.bold)      run.bold      = true
                if (rr.italic)    run.italic    = true
                if (rr.underline) run.setUnderline(UnderlinePatterns.SINGLE)
                if (rr.color)     run.setColor(normalizeHex(rr.color))
                if (rr.fontName)  run.fontFamily = rr.fontName
                if (rr.fontSize > 0) run.fontSize = rr.fontSize
            }
        } else if (spec.text) {
            XWPFParagraph p = doc.createParagraph()
            applyParagraphStyle(p, spec.style, styles)
            p.createRun().setText(spec.text)
        }

        // Table
        if (spec.table) writeTable(doc, spec.table, styles)

        // Bullet list
        spec.bulletList?.each { String item ->
            XWPFParagraph p = doc.createParagraph()
            p.style = 'ListBullet'
            p.createRun().setText(item)
        }

        // Numbered list
        spec.numberedList?.each { String item ->
            XWPFParagraph p = doc.createParagraph()
            p.style = 'ListNumber'
            p.createRun().setText(item)
        }
    }

    private static void writeTable(XWPFDocument doc, TableSpec spec, Map<String, StyleSpec> styles) {
        int cols = spec.headers ? spec.headers.size()
                 : spec.rows   ? spec.rows[0].size()
                 : 1

        XWPFTable table = doc.createTable(1, cols)
        table.removeRow(0)     // remove the initial blank row POI creates

        // Column widths
        spec.columnWidths?.eachWithIndex { int width, int i ->
            if (i < cols) table.getCTTbl().tblPr.tblW?.w = BigInteger.valueOf(width)
        }

        // Header row
        if (spec.headerRow && spec.headers) {
            XWPFTableRow headerRow = table.createRow()
            spec.headers.eachWithIndex { String header, int i ->
                XWPFTableCell cell = i == 0 ? headerRow.getCell(0) : headerRow.createCell()
                cell.text = header
                // Bold all runs in header cells
                cell.paragraphs.each { XWPFParagraph p ->
                    p.runs.each { XWPFRun r -> r.bold = true }
                }
            }
        }

        // Data rows
        spec.rows?.each { List<String> rowData ->
            XWPFTableRow row = table.createRow()
            rowData.eachWithIndex { String value, int i ->
                XWPFTableCell cell = i == 0 ? row.getCell(0) : row.createCell()
                cell.text = value ?: ''
            }
        }
    }

    private static void applyParagraphStyle(XWPFParagraph p, StyleSpec style,
                                             Map<String, StyleSpec> styles) {
        if (!style) return
        StyleSpec resolved = StyleResolver.resolve(style, styles)
        if (resolved?.alignment?.horizontal) {
            p.alignment = parseAlignment(resolved.alignment.horizontal)
        }
    }

    // =========================================================================
    // Page setup
    // =========================================================================

    private static void applyPageSetup(XWPFDocument doc, PageSpec page) {
        def body   = doc.document.body
        def sectPr = body.isSetSectPr() ? body.sectPr : body.addNewSectPr()

        // Page size
        CTPageSz pgSz = sectPr.isSetPgSz() ? sectPr.pgSz : sectPr.addNewPgSz()
        boolean landscape = page.orientation == 'landscape'
        // A4: 11906 x 16838 twips; Letter: 12240 x 15840
        switch (page.size?.toUpperCase()) {
            case 'LETTER': pgSz.w = BigInteger.valueOf(landscape ? 15840 : 12240)
                           pgSz.h = BigInteger.valueOf(landscape ? 12240 : 15840); break
            case 'A3':     pgSz.w = BigInteger.valueOf(landscape ? 23814 : 16834)
                           pgSz.h = BigInteger.valueOf(landscape ? 16834 : 23814); break
            case 'LEGAL':  pgSz.w = BigInteger.valueOf(landscape ? 20160 : 12240)
                           pgSz.h = BigInteger.valueOf(landscape ? 12240 : 20160); break
            default:       // A4
                           pgSz.w = BigInteger.valueOf(landscape ? 16838 : 11906)
                           pgSz.h = BigInteger.valueOf(landscape ? 11906 : 16838)
        }
        if (landscape) pgSz.orient = STPageOrientation.LANDSCAPE

        // Margins
        CTPageMar pgMar = sectPr.isSetPgMar() ? sectPr.pgMar : sectPr.addNewPgMar()
        pgMar.top    = BigInteger.valueOf(page.marginTop)
        pgMar.bottom = BigInteger.valueOf(page.marginBottom)
        pgMar.left   = BigInteger.valueOf(page.marginLeft)
        pgMar.right  = BigInteger.valueOf(page.marginRight)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static XWPFDocument openTemplate(String path) {
        XWPFDocument doc
        new FileInputStream(new File(path)).withCloseable { fis -> doc = new XWPFDocument(fis) }
        return doc
    }

    private static ParagraphAlignment parseAlignment(String s) {
        switch (s?.toLowerCase()) {
            case 'left':    return ParagraphAlignment.LEFT
            case 'center':  return ParagraphAlignment.CENTER
            case 'right':   return ParagraphAlignment.RIGHT
            case 'justify': return ParagraphAlignment.BOTH
            default:        return ParagraphAlignment.LEFT
        }
    }

    /** Strip leading '#' from a hex colour string (POI run.setColor expects bare hex). */
    private static String normalizeHex(String hex) {
        hex?.startsWith('#') ? hex.substring(1) : hex
    }
}
