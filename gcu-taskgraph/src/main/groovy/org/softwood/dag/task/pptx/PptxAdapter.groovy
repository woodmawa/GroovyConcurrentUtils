package org.softwood.dag.task.pptx

import groovy.util.logging.Slf4j
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.Placeholder
import org.apache.poi.xslf.usermodel.XSLFAutoShape
import org.apache.poi.xslf.usermodel.XSLFConnectorShape
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFNotes
import org.apache.poi.xslf.usermodel.XSLFPictureData
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFSlide
import org.apache.poi.xslf.usermodel.XSLFSlideLayout
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTableCell
import org.apache.poi.xslf.usermodel.XSLFTableRow
import org.apache.poi.xslf.usermodel.XSLFTextBox
import org.apache.poi.xslf.usermodel.XSLFTextParagraph
import org.apache.poi.xslf.usermodel.XSLFTextRun
import org.softwood.dag.task.docx.TableSpec
import org.softwood.dag.task.office.OfficeFiles
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.model.RichRun

import java.awt.Color
import java.awt.geom.Rectangle2D

/**
 * POI-backed adapter for PowerPoint (.pptx) operations.
 *
 * <p>Injected into {@link org.softwood.dag.task.PptxTask} via
 * {@code task.pptxCallFn = PptxAdapter.asFn()}.  The task class remains
 * POI-free; all Apache POI XSLF imports live here.</p>
 *
 * <h3>Supported actions:</h3>
 * <ul>
 *   <li>GENERATE     — build a presentation from a {@link PresentationPlan} and write to disk</li>
 *   <li>SCRIPT       — alias for GENERATE when presentation() DSL closure was used</li>
 *   <li>READ         — open .pptx; return slide count, titles, and notes as a Map</li>
 *   <li>ADD_SLIDE    — open an existing .pptx and append a {@link SlidePlan}</li>
 *   <li>EXTRACT_TEXT — open .pptx; return all slide text as a single String</li>
 * </ul>
 *
 * <h3>EMU conversion:</h3>
 * POI XSLF {@code setAnchor} expects a {@link Rectangle2D} in <em>points</em>
 * (1/72 inch).  Conversion: {@code emu / 12700.0 = points}.
 * (1 inch = 914400 EMU = 72 points → 1 EMU = 72/914400 = 1/12700 points.)
 *
 * <p>GCU 2.0.1: speaker notes are written (a property the notes slide does not have made them throw, and the error
 * was swallowed); a named layout is found (the lookup returned from inside an each closure, so it never did) and an
 * unknown one is refused; the title and properties, RECTANGLE / OVAL / LINE shapes, a text shape's bold, italic, font
 * and fill, the defaultLayout background and fonts and the transition speed are applied; a missing image fails
 * instead of being skipped. CHART, defaultLayout theme and accentColor and named styles are refused by name before
 * anything is written ({@link #checkPlan}); files are written atomically.</p>
 */
@Slf4j
class PptxAdapter {

    /** EMU → points divisor. */
    private static final double EMU_TO_PT = 12700.0

    /**
     * Return the dispatch closure to inject into a
     * {@link org.softwood.dag.task.PptxTask}.
     * Signature: {@code { String action, Map config -> Object }}.
     */
    static Closure<Object> asFn() {
        return { String action, Map config ->
            switch (action?.toUpperCase()) {
                case 'GENERATE':
                case 'SCRIPT':       return generate(config)
                case 'READ':         return read(config)
                case 'ADD_SLIDE':    return addSlide(config)
                case 'EXTRACT_TEXT': return extractText(config)
                default: throw new IllegalArgumentException("PptxAdapter: unknown action '${action}'")
            }
        }
    }

    // =========================================================================
    // Action handlers
    // =========================================================================

    private static Object generate(Map config) {
        PresentationPlan plan     = (config.presentationPlan ?: config.plan) as PresentationPlan
        String           filePath = config.filePath as String
        if (!plan)     throw new IllegalArgumentException('PptxAdapter GENERATE: config.presentationPlan (PresentationPlan) is required')
        if (!filePath) throw new IllegalArgumentException('PptxAdapter GENERATE: config.filePath is required')
        checkPlan(plan.slides, plan, 'GENERATE')

        XMLSlideShow ss = new XMLSlideShow()
        try {
            checkLayouts(ss, plan.slides, 'GENERATE')
            applyPresentationPlan(ss, plan)
            OfficeFiles.writeAtomically(new File(filePath)) { OutputStream out -> ss.write(out) }
        } finally {
            ss.close()
        }
        log.info 'PptxAdapter GENERATE: wrote {}', filePath
        return [filePath: filePath, slides: plan.slides.size()]
    }

    private static Object read(Map config) {
        String filePath = config.filePath as String
        if (!filePath) throw new IllegalArgumentException('PptxAdapter READ: config.filePath is required')

        Map result = [slideCount: 0, slides: []]
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XMLSlideShow ss = new XMLSlideShow(fis)
            try {
                result.slideCount = ss.slides.size()
                ss.slides.eachWithIndex { XSLFSlide slide, int i ->
                    Map slideInfo = [index: i + 1, title: null, notes: null, shapes: []]
                    // Title
                    try { slideInfo.title = slide.title } catch (ignored) {}
                    // Notes (2.0.1: from the notes body placeholder; a notes slide has no textBody property)
                    slideInfo.notes = notesText(ss, slide)
                    // Shape text
                    slide.shapes.each { shape ->
                        try {
                            String t = shape.hasProperty('text') ? shape.text : null
                            if (t) (slideInfo.shapes as List) << t
                        } catch (ignored) {}
                    }
                    (result.slides as List) << slideInfo
                }
            } finally {
                ss.close()
            }
        }
        return result
    }

    private static Object addSlide(Map config) {
        SlidePlan slidePlan = config.slide as SlidePlan
        String    filePath  = config.filePath as String
        if (!slidePlan) throw new IllegalArgumentException('PptxAdapter ADD_SLIDE: config.slide (SlidePlan) is required')
        if (!filePath)  throw new IllegalArgumentException('PptxAdapter ADD_SLIDE: config.filePath is required')
        checkPlan([slidePlan], null, 'ADD_SLIDE')

        File file = new File(filePath)
        XMLSlideShow ss = file.exists() ? openExisting(file) : new XMLSlideShow()
        try {
            checkLayouts(ss, [slidePlan], 'ADD_SLIDE')
            writeSlidePlan(ss, slidePlan)
            OfficeFiles.writeAtomically(file) { OutputStream out -> ss.write(out) }
        } finally {
            ss.close()
        }
        return [filePath: filePath, totalSlides: ss.slides.size()]
    }

    private static Object extractText(Map config) {
        String filePath = config.filePath as String
        if (!filePath) throw new IllegalArgumentException('PptxAdapter EXTRACT_TEXT: config.filePath is required')

        StringBuilder sb = new StringBuilder()
        new FileInputStream(new File(filePath)).withCloseable { fis ->
            XMLSlideShow ss = new XMLSlideShow(fis)
            try {
                ss.slides.eachWithIndex { XSLFSlide slide, int i ->
                    sb.append("--- Slide ${i + 1} ---\n")
                    slide.shapes.each { shape ->
                        try {
                            String t = shape.hasProperty('text') ? shape.text : null
                            if (t?.trim()) sb.append(t).append('\n')
                        } catch (ignored) {}
                    }
                }
            } finally {
                ss.close()
            }
        }
        return sb.toString()
    }

    // =========================================================================
    // Presentation plan application
    // =========================================================================

    private static void applyPresentationPlan(XMLSlideShow ss, PresentationPlan plan) {
        // 2.0.1: title and core properties (they were never written)
        if (plan.title || plan.properties) {
            def cp = ss.properties.coreProperties
            if (plan.title)                   cp.title       = plan.title
            if (plan.properties?.author)      cp.creator     = plan.properties.author
            if (plan.properties?.subject)     cp.setSubjectProperty(plan.properties.subject)
            if (plan.properties?.description) cp.setDescription(plan.properties.description)
            if (plan.properties?.keywords)    cp.keywords    = plan.properties.keywords
            if (plan.properties?.category)    cp.category    = plan.properties.category
            if (plan.properties?.company)     ss.properties.extendedProperties.underlyingProperties.company = plan.properties.company
        }
        // Slides
        plan.slides.each { SlidePlan sp -> writeSlidePlan(ss, sp, plan.defaultLayout) }
    }

    private static void writeSlidePlan(XMLSlideShow ss, SlidePlan sp,
                                        SlideLayoutSpec defaultLayout = null) {
        // Resolve layout (an unknown name was refused by checkLayouts)
        XSLFSlideLayout layout = resolveLayout(ss, sp.layout)
        XSLFSlide slide = layout ? ss.createSlide(layout) : ss.createSlide()

        // 2.0.1: slide background from the presentation default
        if (defaultLayout?.background) slide.background.fillColor = hexToColor(defaultLayout.background)

        // Title: the layout's title placeholder when it has one, else a text box
        if (sp.title) {
            XSLFTextShape titleShape = slide.placeholders.find { XSLFTextShape ph ->
                ph.textType == Placeholder.TITLE || ph.textType == Placeholder.CENTERED_TITLE
            }
            if (titleShape) {
                titleShape.text = sp.title
            } else {
                XSLFTextBox tb = slide.createTextBox()
                tb.anchor = new Rectangle2D.Double(30, 10, 620, 50)
                tb.text   = sp.title
                titleShape = tb
            }
            applyFonts(titleShape, defaultLayout?.fontFamily, defaultLayout?.titleFontSize ?: 0)
        }

        // Shapes
        sp.shapes?.each { ShapeSpec shape -> writeShape(ss, slide, shape, defaultLayout) }

        // Transition
        if (sp.transition && sp.transition.type != TransitionType.NONE) {
            applyTransition(slide, sp.transition)
        }

        // 2.0.1: speaker notes go in the notes slide's body placeholder. The old code set a textBody property the
        // notes slide does not have, caught the exception and logged it at debug, so notes were silently dropped.
        if (sp.notes) {
            XSLFNotes notes = ss.getNotesSlide(slide)
            XSLFTextShape body = notes?.placeholders?.find { XSLFTextShape ph -> ph.textType == Placeholder.BODY }
            if (!body) throw new IllegalStateException('PptxAdapter: the notes slide has no body placeholder to hold speaker notes')
            body.text = sp.notes
        }
    }

    // =========================================================================
    // Shape writing
    // =========================================================================

    private static void writeShape(XMLSlideShow ss, XSLFSlide slide, ShapeSpec spec, SlideLayoutSpec defaultLayout = null) {
        Rectangle2D anchor = toAnchor(spec)

        switch (spec.type) {
            case ShapeType.IMAGE:
                writeImageShape(ss, slide, spec, anchor)
                break
            case ShapeType.TABLE:
                writeTableShape(slide, spec.table, anchor)
                break
            case ShapeType.RECTANGLE:
                writeAutoShape(slide, spec, anchor, org.apache.poi.sl.usermodel.ShapeType.RECT, defaultLayout)
                break
            case ShapeType.OVAL:
                writeAutoShape(slide, spec, anchor, org.apache.poi.sl.usermodel.ShapeType.ELLIPSE, defaultLayout)
                break
            case ShapeType.LINE:
                writeLine(slide, spec, anchor)
                break
            case ShapeType.CHART:
                throw new IllegalArgumentException('PptxAdapter: CHART shapes are not supported')
            default:
                writeTextShape(slide, spec, anchor, defaultLayout)
        }
    }

    /** 2.0.1: RECTANGLE and OVAL used to fall through to a text box. */
    private static void writeAutoShape(XSLFSlide slide, ShapeSpec spec, Rectangle2D anchor,
                                       org.apache.poi.sl.usermodel.ShapeType type, SlideLayoutSpec defaultLayout) {
        XSLFAutoShape shape = slide.createAutoShape()
        shape.shapeType = type
        shape.anchor    = anchor.width > 0 ? anchor : new Rectangle2D.Double(50, 50, 200, 100)
        if (spec.style?.fill?.foreground) shape.fillColor = hexToColor(spec.style.fill.foreground)
        if (spec.style?.border?.color)    shape.lineColor = hexToColor(spec.style.border.color)
        if (spec.text || spec.richText)   fillText(shape, spec, defaultLayout)
    }

    /** 2.0.1: LINE used to fall through to a text box. */
    private static void writeLine(XSLFSlide slide, ShapeSpec spec, Rectangle2D anchor) {
        XSLFConnectorShape line = slide.createConnector()
        line.anchor    = anchor
        line.lineColor = hexToColor(spec.style?.border?.color ?: '#000000')
        line.lineWidth = 1.0d
    }

    private static void writeTextShape(XSLFSlide slide, ShapeSpec spec, Rectangle2D anchor, SlideLayoutSpec defaultLayout = null) {
        XSLFTextBox tb = slide.createTextBox()
        tb.anchor      = anchor.width > 0 ? anchor : new Rectangle2D.Double(50, 50, 500, 100)
        fillText(tb, spec, defaultLayout)
        // 2.0.1: the shape's fill
        if (spec.style?.fill?.foreground) tb.fillColor = hexToColor(spec.style.fill.foreground)
    }

    /**
     * Text and its formatting for a text box or auto-shape. Plain text takes the shape style's font (bold, italic,
     * underline, name, size, colour -- 2.0.1: only size and colour were applied), falling back to the presentation
     * default font family and body size. A rich-text run's own formatting wins; the defaults fill what it leaves unset.
     */
    private static void fillText(XSLFTextShape shape, ShapeSpec spec, SlideLayoutSpec defaultLayout) {
        def f = spec.style?.font
        String family = f?.name ?: defaultLayout?.fontFamily
        int size      = (f?.size ?: 0) > 0 ? f.size : (defaultLayout?.bodyFontSize ?: 0)

        if (spec.richText) {
            shape.clearText()
            XSLFTextParagraph para = shape.addNewTextParagraph()
            spec.richText.each { RichRun rr ->
                if (!rr?.text) return
                XSLFTextRun run = para.addNewTextRun()
                run.setText(rr.text)
                if (rr.bold)        run.bold       = true
                if (rr.italic)      run.italic     = true
                if (rr.underline)   run.underlined = true
                String rf = rr.fontName ?: family
                if (rf)             run.fontFamily = rf
                int rs = rr.fontSize > 0 ? rr.fontSize : size
                if (rs > 0)         run.fontSize   = (double) rs
                if (rr.color)       run.fontColor  = hexToColor(rr.color)
                else if (f?.color)  run.fontColor  = hexToColor(f.color)
            }
        } else if (spec.text) {
            shape.text = spec.text
            shape.textParagraphs.each { XSLFTextParagraph p ->
                p.textRuns.each { XSLFTextRun r ->
                    if (f?.bold)      r.bold       = true
                    if (f?.italic)    r.italic     = true
                    if (f?.underline) r.underlined = true
                    if (family)       r.fontFamily = family
                    if (size > 0)     r.fontSize   = (double) size
                    if (f?.color)     r.fontColor  = hexToColor(f.color)
                }
            }
        }
    }

    /** Font family and size on every run of a shape (the title, from the presentation default). */
    private static void applyFonts(XSLFTextShape shape, String family, int size) {
        shape?.textParagraphs?.each { XSLFTextParagraph p ->
            p.textRuns.each { XSLFTextRun r ->
                if (family)   r.fontFamily = family
                if (size > 0) r.fontSize   = (double) size
            }
        }
    }

    private static void writeImageShape(XMLSlideShow ss, XSLFSlide slide,
                                         ShapeSpec spec, Rectangle2D anchor) {
        // 2.0.1: a missing image fails the plan (checkPlan refuses it first); it used to be skipped with a warning
        File imgFile = spec.imagePath ? new File(spec.imagePath) : null
        if (!imgFile?.isFile()) throw new IllegalArgumentException("PptxAdapter: image not found: ${spec.imagePath}".toString())
        String ext = spec.imagePath.tokenize('.').last().toLowerCase()
        PictureData.PictureType picType
        switch (ext) {
            case 'jpg':
            case 'jpeg': picType = PictureData.PictureType.JPEG; break
            case 'gif':  picType = PictureData.PictureType.GIF;  break
            case 'bmp':  picType = PictureData.PictureType.BMP;  break
            case 'wmf':  picType = PictureData.PictureType.WMF;  break
            case 'emf':  picType = PictureData.PictureType.EMF;  break
            default:     picType = PictureData.PictureType.PNG
        }
        XSLFPictureData pd     = ss.addPicture(imgFile.bytes, picType)
        XSLFPictureShape shape = slide.createPicture(pd)
        shape.anchor = anchor.width > 0 ? anchor : new Rectangle2D.Double(50, 50, 400, 300)
    }

    private static void writeTableShape(XSLFSlide slide, TableSpec spec, Rectangle2D anchor) {
        if (!spec) return
        int cols = spec.headers ? spec.headers.size() : spec.rows ? spec.rows[0].size() : 1
        int dataRows = spec.rows?.size() ?: 0
        int totalRows = (spec.headerRow && spec.headers ? 1 : 0) + dataRows
        if (totalRows == 0) totalRows = 1

        XSLFTable table = slide.createTable(totalRows, cols)
        table.anchor    = anchor.width > 0 ? anchor : new Rectangle2D.Double(50, 100, 600, 200)

        int rowOffset = 0
        // Header
        if (spec.headerRow && spec.headers) {
            spec.headers.eachWithIndex { String header, int c ->
                XSLFTableCell cell = table.getCell(0, c)
                cell.text = header
                // Bold header text
                cell.textParagraphs.each { XSLFTextParagraph p ->
                    p.textRuns.each { XSLFTextRun r -> r.bold = true }
                }
            }
            rowOffset = 1
        }
        // Data rows
        spec.rows?.eachWithIndex { List<String> rowData, int r ->
            rowData.eachWithIndex { String val, int c ->
                try {
                    XSLFTableCell cell = table.getCell(r + rowOffset, c)
                    cell.text = val ?: ''
                } catch (ignored) {}
            }
        }
    }

    // =========================================================================
    // Transition
    // =========================================================================

    private static void applyTransition(XSLFSlide slide, TransitionSpec spec) {
        def ctSlide = slide.xmlObject
        def trans   = ctSlide.isSetTransition() ? ctSlide.transition : ctSlide.addNewTransition()
        switch (spec.type) {
            case TransitionType.FADE:  trans.addNewFade();  break
            case TransitionType.PUSH:  trans.addNewPush();  break
            case TransitionType.WIPE:  trans.addNewWipe();  break
            case TransitionType.SPLIT: trans.addNewStrips(); break
            case TransitionType.ZOOM:  trans.addNewZoom();  break
            default: break
        }
        // 2.0.1: the duration sets the speed (OOXML has three: up to 500 ms fast, up to 1 s medium, longer slow).
        // Set as the attribute through a cursor: the STTransitionSpeed schema type is not in poi-ooxml-lite.
        // Errors are no longer swallowed at debug level.
        String speed = spec.durationMs <= 500 ? 'fast' : spec.durationMs <= 1000 ? 'med' : 'slow'
        def cursor = trans.newCursor()
        try {
            cursor.setAttributeText(new javax.xml.namespace.QName('spd'), speed)
        } finally {
            cursor.dispose()
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * The layout with this name, or null for no name. 2.0.1: the old lookup returned from inside an each closure,
     * which only ends that iteration, so it always returned null and a named layout was never used.
     */
    private static XSLFSlideLayout resolveLayout(XMLSlideShow ss, String layoutName) {
        if (!layoutName) return null
        for (def master : ss.slideMasters) {
            XSLFSlideLayout found = master.slideLayouts.find { it.name == layoutName }
            if (found) return found
        }
        throw new IllegalArgumentException("PptxAdapter: layout '${layoutName}' not found (available: ${layoutNames(ss)})".toString())
    }

    private static List<String> layoutNames(XMLSlideShow ss) {
        ss.slideMasters.collectMany { master -> master.slideLayouts.collect { it.name } } as List<String>
    }

    /** Every slide's layout must exist in the presentation: refused by name before anything is written. */
    private static void checkLayouts(XMLSlideShow ss, List<SlidePlan> slides, String action) {
        List<String> known = layoutNames(ss)
        List<String> problems = []
        slides.eachWithIndex { SlidePlan sp, int i ->
            if (sp.layout && !known.contains(sp.layout)) problems << "slides[${i}].layout '${sp.layout}' not found (available: ${known})".toString()
        }
        OfficeFiles.refuse("PptxAdapter ${action}".toString(), problems)
    }

    private static final Set<String> PROPERTY_KEYS = ['author', 'subject', 'description', 'category', 'keywords', 'company'] as Set

    /**
     * Refuse, before anything is written, a field this adapter cannot apply -- every problem named at once.
     * {@code plan} is null for ADD_SLIDE (one slide, no presentation-level fields).
     */
    static void checkPlan(List<SlidePlan> slides, PresentationPlan plan, String action) {
        List<String> problems = []
        if (plan) {
            if (plan.defaultLayout?.theme)       problems << 'defaultLayout.theme is not supported: POI cannot switch a theme -- start from a template instead'
            if (plan.defaultLayout?.accentColor) problems << 'defaultLayout.accentColor is not supported: theme colours cannot be changed -- set colours on each shape'
            if (plan.styles)                     problems << 'styles is not supported: no shape refers to a named style -- set style on each shape'
            plan.properties?.keySet()?.each { Object k ->
                if (!PROPERTY_KEYS.contains(k as String)) problems << "properties.${k} is not supported (${PROPERTY_KEYS.sort()})".toString()
            }
        }
        slides.eachWithIndex { SlidePlan sp, int i ->
            sp.shapes?.eachWithIndex { ShapeSpec sh, int j ->
                String w = "slides[${i}].shapes[${j}]"
                if (sh.type == ShapeType.CHART) problems << "${w}.type CHART is not supported".toString()
                if (sh.type == ShapeType.TABLE && !sh.table) problems << "${w}.table is required for TABLE".toString()
                if (sh.type == ShapeType.IMAGE) {
                    if (!sh.imagePath)                         problems << "${w}.imagePath is required for IMAGE".toString()
                    else if (!new File(sh.imagePath).isFile()) problems << "${w}.imagePath: image not found: ${sh.imagePath}".toString()
                }
            }
        }
        OfficeFiles.refuse("PptxAdapter ${action}".toString(), problems)
    }

    /** Speaker notes text from the notes slide's body placeholder, or null when the slide has none. */
    private static String notesText(XMLSlideShow ss, XSLFSlide slide) {
        XSLFNotes notes = slide.notes
        if (!notes) return null
        XSLFTextShape body = notes.placeholders.find { XSLFTextShape ph -> ph.textType == Placeholder.BODY }
        return body?.text
    }

    private static XMLSlideShow openExisting(File file) {
        XMLSlideShow ss
        new FileInputStream(file).withCloseable { fis -> ss = new XMLSlideShow(fis) }
        return ss
    }

    /** Convert EMU coordinates in a ShapeSpec to a points-based Rectangle2D. */
    private static Rectangle2D toAnchor(ShapeSpec spec) {
        new Rectangle2D.Double(
            spec.x      / EMU_TO_PT,
            spec.y      / EMU_TO_PT,
            spec.width  / EMU_TO_PT,
            spec.height / EMU_TO_PT
        )
    }

    private static Color hexToColor(String hex) {
        if (!hex) return Color.BLACK
        String h = hex.startsWith('#') ? hex.substring(1) : hex
        int r = Integer.parseInt(h.substring(0, 2), 16)
        int g = Integer.parseInt(h.substring(2, 4), 16)
        int b = Integer.parseInt(h.substring(4, 6), 16)
        return new Color(r, g, b)
    }
}