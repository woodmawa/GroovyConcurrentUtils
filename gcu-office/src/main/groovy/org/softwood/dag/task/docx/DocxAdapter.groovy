package org.softwood.dag.task.docx

import groovy.util.logging.Slf4j
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFNumbering
import org.apache.poi.xwpf.usermodel.XWPFStyle
import org.apache.poi.xwpf.usermodel.XWPFStyles
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import org.apache.poi.xwpf.usermodel.XWPFTableRow
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STPageOrientation
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STStyleType
import org.softwood.dag.task.office.OfficeFiles
import org.softwood.dag.task.xlsx.StyleResolver
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.model.RichRun

import java.math.BigInteger
import org.apache.poi.xwpf.usermodel.IBodyElement

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
 *
 * <p>GCU 2.0.1: headings get heading styles the document defines (a new document had none, so Word showed them as
 * body text); bullet and numbered items get list numbering (they had none); table header/body styles and column
 * widths, a section style's font, and the category/company properties are applied. Page setup needs the full OOXML
 * schemas: with only {@code poi-ooxml-lite} on the classpath it fails with a message naming {@code poi-ooxml-full}
 * (it used to be swallowed, and the NoClassDefFoundError escaped anyway). Every plan field is applied or refused
 * by name before the file is written ({@link #checkPlan}); files are written atomically.</p>
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
                case 'APPEND_SECTION':  return appendSections(config)   // 2.1.0
                case 'REPLACE_SECTION': return replaceSection(config)   // 2.1.0
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
        checkPlan(plan, config)

        XWPFDocument doc = config.templatePath
            ? openTemplate(config.templatePath as String)
            : new XWPFDocument()
        try {
            applyDocumentPlan(doc, plan)
            OfficeFiles.writeAtomically(new File(filePath)) { OutputStream out -> doc.write(out) }
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
            OfficeFiles.writeAtomically(new File(outputPath)) { OutputStream os -> merged.write(os) }
        } finally {
            merged.close()
        }
        return [outputPath: outputPath, merged: inputPaths.size()]
    }

    /**
     * 2.1.0 APPEND_SECTION: {@code config.sections} (List of SectionSpec) written after the existing content of
     * {@code config.filePath}, in order, with the heading styles and list numbering GENERATE gives; the file is created
     * when it does not exist. Every section is checked before anything is written.
     */
    private static Object appendSections(Map config) {
        String filePath = config.filePath as String
        List<SectionSpec> sections = (config.sections ?: []) as List<SectionSpec>
        if (!filePath) throw new IllegalArgumentException('DocxAdapter APPEND_SECTION: config.filePath is required')
        if (!sections) throw new IllegalArgumentException('DocxAdapter APPEND_SECTION: config.sections (List<SectionSpec>) is required')
        DocumentPlan plan = new DocumentPlan(sections: sections)
        checkPlan(plan, [:])

        File file = new File(filePath)
        XWPFDocument doc = file.exists() ? file.withInputStream { InputStream ins -> new XWPFDocument(ins) } : new XWPFDocument()
        try {
            ensureHeadingStyles(doc, sections.findAll { it.heading }.collect { it.headingLevel } as Set<Integer>)
            Map<String, BigInteger> lists = [:]
            sections.each { SectionSpec s -> writeSection(doc, s, [:], lists) }
            OfficeFiles.writeAtomically(file) { OutputStream out -> doc.write(out) }
        } finally {
            doc.close()
        }
        return [filePath: filePath, sectionsAppended: sections.size()]
    }

    /** Fields a REPLACE_SECTION replacement may carry: the body only -- the heading stays the one being replaced. */
    private static final List<String> REPLACE_FIELDS = ['text', 'bulletList', 'numberedList']

    /**
     * 2.1.0 REPLACE_SECTION: the body of the section headed {@code config.targetSection} -- everything between that
     * heading and the next heading, tables included -- is replaced by {@code config.section} (a SectionSpec carrying
     * text, bulletList or numberedList), in place. A heading that is not there, or a replacement with a field only
     * GENERATE can apply (heading, richText, table, style), is refused and the file is left as it was.
     */
    private static Object replaceSection(Map config) {
        String filePath = config.filePath as String
        String target   = config.targetSection as String
        SectionSpec spec = config.section as SectionSpec
        if (!filePath) throw new IllegalArgumentException('DocxAdapter REPLACE_SECTION: config.filePath is required')
        if (!target)   throw new IllegalArgumentException('DocxAdapter REPLACE_SECTION: config.targetSection is required')
        if (spec == null) throw new IllegalArgumentException('DocxAdapter REPLACE_SECTION: config.section (SectionSpec) is required')
        List<String> problems = []
        if (spec.heading)  problems << "section.heading is not accepted -- the heading is '${target}', the one replaced".toString()
        if (spec.richText) problems << "section.richText is not applied by REPLACE_SECTION (use one of ${REPLACE_FIELDS})".toString()
        if (spec.table)    problems << "section.table is not applied by REPLACE_SECTION (use one of ${REPLACE_FIELDS})".toString()
        if (spec.style)    problems << "section.style is not applied by REPLACE_SECTION (use one of ${REPLACE_FIELDS})".toString()
        OfficeFiles.refuse('DocxAdapter REPLACE_SECTION', problems)

        File file = new File(filePath)
        XWPFDocument doc = file.withInputStream { InputStream ins -> new XWPFDocument(ins) }
        try {
            List<IBodyElement> body = doc.bodyElements
            int at = body.findIndexOf { IBodyElement e -> isHeading(e) && (e as XWPFParagraph).text == target }
            if (at < 0) {
                List<String> headings = body.findAll { isHeading(it) }.collect { (it as XWPFParagraph).text }
                throw new IllegalArgumentException("DocxAdapter REPLACE_SECTION: nothing written -- no section headed '${target}' (headings: ${headings})".toString())
            }
            while (at + 1 < doc.bodyElements.size() && !isHeading(doc.bodyElements[at + 1])) {
                doc.removeBodyElement(at + 1)
            }
            IBodyElement next = at + 1 < doc.bodyElements.size() ? doc.bodyElements[at + 1] : null
            Closure<XWPFParagraph> newPara = { ->
                next == null ? doc.createParagraph() : doc.insertNewParagraph((next as XWPFParagraph).CTP.newCursor())
            }
            if (spec.text) {
                newPara.call().createRun().setText(spec.text)
            }
            Map<String, BigInteger> lists = [:]
            [bullet: spec.bulletList, decimal: spec.numberedList].each { String kind, List<String> items ->
                if (!items) return
                BigInteger numId = addListNumbering(doc, lists, kind)
                items.each { String item ->
                    XWPFParagraph p = newPara.call()
                    p.numID = numId
                    p.numILvl = BigInteger.ZERO
                    p.createRun().setText(item)
                }
            }
            OfficeFiles.writeAtomically(file) { OutputStream out -> doc.write(out) }
        } finally {
            doc.close()
        }
        return [filePath: filePath, replacedSection: target]
    }

    /** A heading paragraph: its style id is Heading<n> (as GENERATE writes them) or a template's heading style. */
    static boolean isHeading(IBodyElement e) {
        e instanceof XWPFParagraph && ((e as XWPFParagraph).style ?: '').toLowerCase().startsWith('heading')
    }

    // =========================================================================
    // Document plan application
    // =========================================================================

    private static void applyDocumentPlan(XWPFDocument doc, DocumentPlan plan) {
        // Core properties (2.0.1: category and company too)
        if (plan.title || plan.properties) {
            def cp = doc.properties.coreProperties
            if (plan.title)                      cp.title       = plan.title
            if (plan.properties?.author)         cp.creator     = plan.properties.author
            if (plan.properties?.subject)        cp.setSubjectProperty(plan.properties.subject)
            if (plan.properties?.description)    cp.setDescription(plan.properties.description)
            if (plan.properties?.keywords)       cp.keywords    = plan.properties.keywords
            if (plan.properties?.category)       cp.category    = plan.properties.category
            if (plan.properties?.company)        doc.properties.extendedProperties.underlyingProperties.company = plan.properties.company
        }

        // 2.0.1: heading styles for the levels used, and one bullet numbering shared by every bullet list
        ensureHeadingStyles(doc, plan.sections.findAll { it.heading }.collect { it.headingLevel } as Set<Integer>)
        Map<String, BigInteger> lists = [:]

        // Sections
        plan.sections.each { SectionSpec spec ->
            writeSection(doc, spec, plan.styles ?: [:], lists)
        }

        // Page setup -- needs the full OOXML schemas; a missing schema class is named, not swallowed (2.0.1)
        if (plan.page) {
            try {
                applyPageSetup(doc, plan.page)
            } catch (NoClassDefFoundError e) {
                throw new IllegalStateException("DocxAdapter: page setup needs the full OOXML schemas -- put " +
                    "org.apache.poi:poi-ooxml-full on the classpath in place of poi-ooxml-lite (missing: ${e.message})".toString(), e)
            }
        }
    }

    private static void writeSection(XWPFDocument doc, SectionSpec spec, Map<String, StyleSpec> styles,
                                     Map<String, BigInteger> lists) {
        StyleSpec style = spec.style ? StyleResolver.resolve(spec.style, styles) : null

        // Heading
        if (spec.heading) {
            XWPFParagraph h = doc.createParagraph()
            h.style = "Heading${spec.headingLevel}"
            XWPFRun run = h.createRun()
            run.setText(spec.heading)
        }

        // Rich text paragraph (takes precedence over plain text); each run's own formatting wins over the section's
        if (spec.richText) {
            XWPFParagraph p = doc.createParagraph()
            applyParagraphStyle(p, style)
            spec.richText.each { RichRun rr ->
                if (!rr?.text) return
                XWPFRun run = p.createRun()
                run.setText(rr.text)
                applyRunStyle(run, style)
                if (rr.bold)      run.bold      = true
                if (rr.italic)    run.italic    = true
                if (rr.underline) run.setUnderline(UnderlinePatterns.SINGLE)
                if (rr.color)     run.setColor(normalizeHex(rr.color))
                if (rr.fontName)  run.fontFamily = rr.fontName
                if (rr.fontSize > 0) run.fontSize = rr.fontSize
            }
        } else if (spec.text) {
            XWPFParagraph p = doc.createParagraph()
            applyParagraphStyle(p, style)
            XWPFRun run = p.createRun()
            run.setText(spec.text)
            applyRunStyle(run, style)
        }

        // Table
        if (spec.table) writeTable(doc, spec.table, styles)

        // 2.0.1: list items carry real list numbering. They used to get the style ids ListBullet / ListNumber, which a
        // new document does not define, and no numbering, so Word showed them as plain paragraphs.
        if (spec.bulletList) {
            BigInteger numId = lists['bullet'] ?: (lists['bullet'] = addListNumbering(doc, lists, 'bullet'))
            spec.bulletList.each { String item -> writeListItem(doc, item, numId, style) }
        }
        if (spec.numberedList) {
            // each numbered list restarts at 1
            BigInteger numId = addListNumbering(doc, lists, 'decimal')
            spec.numberedList.each { String item -> writeListItem(doc, item, numId, style) }
        }
    }

    private static void writeTable(XWPFDocument doc, TableSpec spec, Map<String, StyleSpec> styles) {
        int cols = spec.headers ? spec.headers.size()
                 : spec.rows   ? spec.rows[0].size()
                 : 1
        StyleSpec headerStyle = spec.headerStyle ? StyleResolver.resolve(spec.headerStyle, styles) : null
        StyleSpec bodyStyle   = spec.bodyStyle   ? StyleResolver.resolve(spec.bodyStyle, styles)   : null

        // doc.createTable(1, cols) creates one pre-populated row — reuse it rather than
        // removing it (createRow() on an empty table returns a row with no cells).
        XWPFTable table = doc.createTable(1, cols)

        int nextRow = 0   // index of next row to fill (0 = pre-created row)

        // Header row — fill the pre-created row (bold by default; 2.0.1: headerStyle applied over it)
        if (spec.headerRow && spec.headers) {
            XWPFTableRow headerRow = table.getRow(nextRow++)
            spec.headers.eachWithIndex { String header, int i ->
                XWPFTableCell cell = i < headerRow.tableCells.size()
                    ? headerRow.getCell(i)
                    : headerRow.addNewTableCell()
                cell.text = header
                cell.paragraphs.each { XWPFParagraph p ->
                    p.runs.each { XWPFRun r -> r.bold = true; applyRunStyle(r, headerStyle) }
                }
                if (headerStyle?.fill?.foreground) cell.color = normalizeHex(headerStyle.fill.foreground)
            }
        }

        // Data rows — createRow() copies structure from the last existing row
        spec.rows?.each { List<String> rowData ->
            XWPFTableRow row = (nextRow == 0) ? table.getRow(0) : table.createRow()
            nextRow++
            rowData.eachWithIndex { String value, int i ->
                XWPFTableCell cell = i < row.tableCells.size()
                    ? row.getCell(i)
                    : row.addNewTableCell()
                cell.text = value ?: ''
                if (bodyStyle?.fill?.foreground) cell.color = normalizeHex(bodyStyle.fill.foreground)
                cell.paragraphs.each { XWPFParagraph p ->
                    p.runs.each { XWPFRun r -> applyRunStyle(r, bodyStyle) }
                }
            }
        }

        // 2.0.1: column widths in twips, on the table grid and on every cell
        if (spec.columnWidths) {
            def ctTbl = table.CTTbl
            def grid  = ctTbl.tblGrid ?: ctTbl.addNewTblGrid()
            while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0)
            spec.columnWidths.each { Integer w -> grid.addNewGridCol().w = BigInteger.valueOf(w as long) }
            table.rows.each { XWPFTableRow row ->
                row.tableCells.eachWithIndex { XWPFTableCell c, int i ->
                    if (i < spec.columnWidths.size()) c.width = String.valueOf(spec.columnWidths[i])
                }
            }
        }
    }

    /** Paragraph alignment from an already-resolved style. */
    private static void applyParagraphStyle(XWPFParagraph p, StyleSpec resolved) {
        if (resolved?.alignment?.horizontal) {
            p.alignment = parseAlignment(resolved.alignment.horizontal)
        }
    }

    /** Run font from an already-resolved style (2.0.1: a section style's font used to be ignored). */
    private static void applyRunStyle(XWPFRun run, StyleSpec resolved) {
        def f = resolved?.font
        if (!f) return
        if (f.bold)          run.bold      = true
        if (f.italic)        run.italic    = true
        if (f.underline)     run.setUnderline(UnderlinePatterns.SINGLE)
        if (f.strikethrough) run.strikeThrough = true
        if (f.size > 0)      run.fontSize  = f.size
        if (f.color)         run.setColor(normalizeHex(f.color))
        if (f.name)          run.fontFamily = f.name
    }

    private static void writeListItem(XWPFDocument doc, String item, BigInteger numId, StyleSpec style) {
        XWPFParagraph p = doc.createParagraph()
        p.numID = numId
        p.numILvl = BigInteger.ZERO
        applyParagraphStyle(p, style)
        XWPFRun run = p.createRun()
        run.setText(item)
        applyRunStyle(run, style)
    }

    private static final List<Integer> HEADING_HALF_POINTS = [32, 26, 24, 22, 22, 22]

    /** Define Heading1..6 for the levels used, when the document (new, or a template) does not define them. */
    private static void ensureHeadingStyles(XWPFDocument doc, Set<Integer> levels) {
        if (!levels) return
        XWPFStyles styles = doc.styles ?: doc.createStyles()
        levels.each { Integer n ->
            String id = "Heading${n}"
            if (styles.styleExist(id)) return
            CTStyle ct = CTStyle.Factory.newInstance()
            ct.styleId = id
            ct.type    = STStyleType.PARAGRAPH
            ct.addNewName().val     = "heading ${n}".toString()
            ct.addNewBasedOn().val  = 'Normal'
            ct.addNewNext().val     = 'Normal'
            ct.addNewUiPriority().val = BigInteger.valueOf(9)
            ct.addNewQFormat()
            def ppr = ct.addNewPPr()
            ppr.addNewKeepNext()
            ppr.addNewOutlineLvl().val = BigInteger.valueOf(n - 1)
            def rpr = ct.addNewRPr()
            rpr.addNewB()
            rpr.addNewSz().val = BigInteger.valueOf(HEADING_HALF_POINTS[n - 1])
            styles.addStyle(new XWPFStyle(ct, styles))
        }
    }

    /**
     * A new list numbering instance ({@code bullet} or {@code decimal}) on one shared abstract definition per kind.
     * Returns its numId. The abstract ids are cached in {@code lists} under {@code abs-<kind>}.
     */
    private static BigInteger addListNumbering(XWPFDocument doc, Map<String, BigInteger> lists, String kind) {
        XWPFNumbering numbering = doc.numbering ?: doc.createNumbering()
        BigInteger abstractId = lists["abs-${kind}".toString()]
        if (abstractId == null) {
            CTAbstractNum cta = CTAbstractNum.Factory.newInstance()
            cta.abstractNumId = BigInteger.valueOf(numbering.abstractNums.size())
            CTLvl lvl = cta.addNewLvl()
            lvl.ilvl = BigInteger.ZERO
            lvl.addNewStart().val   = BigInteger.ONE
            lvl.addNewNumFmt().val  = kind == 'bullet' ? STNumberFormat.BULLET : STNumberFormat.DECIMAL
            lvl.addNewLvlText().val = kind == 'bullet' ? '•' : '%1.'
            def ind = lvl.addNewPPr().addNewInd()
            ind.left    = BigInteger.valueOf(720)
            ind.hanging = BigInteger.valueOf(360)
            abstractId = numbering.addAbstractNum(new XWPFAbstractNum(cta, numbering))
            lists["abs-${kind}".toString()] = abstractId
        }
        return numbering.addNum(abstractId)
    }

    private static final Set<String> PROPERTY_KEYS = ['author', 'subject', 'description', 'category', 'keywords', 'company'] as Set
    private static final Set<String> PAGE_SIZES    = ['A4', 'A3', 'LETTER', 'LEGAL'] as Set

    /** Refuse, before anything is written, a plan field this adapter cannot apply -- every problem named at once. */
    static void checkPlan(DocumentPlan plan, Map config) {
        List<String> problems = []
        Map<String, StyleSpec> styles = plan.styles ?: [:]
        plan.properties?.keySet()?.each { Object k ->
            if (!PROPERTY_KEYS.contains(k as String)) problems << "properties.${k} is not supported (${PROPERTY_KEYS.sort()})".toString()
        }
        if (config.templatePath && !new File(config.templatePath as String).isFile()) problems << "templatePath not found: ${config.templatePath}".toString()
        if (plan.page) {
            if (!PAGE_SIZES.contains((plan.page.size ?: 'A4').toUpperCase())) problems << "page.size '${plan.page.size}' is not supported (${PAGE_SIZES.sort()})".toString()
            if (!(plan.page.orientation in ['portrait', 'landscape'])) problems << "page.orientation '${plan.page.orientation}' must be portrait or landscape".toString()
        }
        Closure extendsCheck = { String where, StyleSpec s ->
            if (s?.extendsStyle && !styles.containsKey(s.extendsStyle)) problems << "${where}.extendsStyle '${s.extendsStyle}' is not in styles".toString()
        }
        plan.sections.eachWithIndex { SectionSpec s, int i ->
            String w = "sections[${i}]"
            if (s.heading && (s.headingLevel < 1 || s.headingLevel > 6)) problems << "${w}.headingLevel ${s.headingLevel} must be 1 to 6".toString()
            extendsCheck(w + '.style', s.style)
            if (s.table) {
                int cols = s.table.headers ? s.table.headers.size() : (s.table.rows ? s.table.rows[0].size() : 1)
                if (s.table.columnWidths && s.table.columnWidths.size() != cols) {
                    problems << "${w}.table.columnWidths has ${s.table.columnWidths.size()} widths for ${cols} columns".toString()
                }
                extendsCheck(w + '.table.headerStyle', s.table.headerStyle)
                extendsCheck(w + '.table.bodyStyle', s.table.bodyStyle)
            }
        }
        OfficeFiles.refuse('DocxAdapter GENERATE', problems)
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