package org.softwood.dag.task.pptx

import groovy.util.logging.Slf4j
import org.apache.poi.sl.usermodel.PictureData
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

        XMLSlideShow ss = new XMLSlideShow()
        try {
            applyPresentationPlan(ss, plan)
            File file = new File(filePath)
            file.parentFile?.mkdirs()
            new FileOutputStream(file).withCloseable { out -> ss.write(out) }
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
                    // Notes
                    try {
                        XSLFNotes notes = ss.getNotesSlide(slide)
                        if (notes) slideInfo.notes = notes.textBody?.text
                    } catch (ignored) {}
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

        File file = new File(filePath)
        XMLSlideShow ss = file.exists() ? openExisting(file) : new XMLSlideShow()
        try {
            writeSlidePlan(ss, slidePlan)
            new FileOutputStream(file).withCloseable { out -> ss.write(out) }
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
        // Slides
        plan.slides.each { SlidePlan sp -> writeSlidePlan(ss, sp, plan.defaultLayout) }
    }

    private static void writeSlidePlan(XMLSlideShow ss, SlidePlan sp,
                                        SlideLayoutSpec defaultLayout = null) {
        // Resolve layout
        XSLFSlideLayout layout = resolveLayout(ss, sp.layout)
        XSLFSlide slide = layout ? ss.createSlide(layout) : ss.createSlide()

        // Title placeholder
        if (sp.title) {
            try {
                slide.titleShape?.text = sp.title
            } catch (ignored) {
                // Some layouts have no title placeholder — create a text box instead
                XSLFTextBox tb = slide.createTextBox()
                tb.anchor = new Rectangle2D.Double(30, 10, 620, 50)
                tb.text   = sp.title
            }
        }

        // Shapes
        sp.shapes?.each { ShapeSpec shape -> writeShape(ss, slide, shape) }

        // Transition
        if (sp.transition && sp.transition.type != TransitionType.NONE) {
            applyTransition(slide, sp.transition)
        }

        // Notes
        if (sp.notes) {
            try {
                XSLFNotes notes = ss.getNotesSlide(slide) ?: ss.createNotesSlide(slide)
                if (notes?.textBody) notes.textBody.text = sp.notes
            } catch (ignored) {
                log.debug 'PptxAdapter: could not set notes for slide — {}', ignored.message
            }
        }
    }

    // =========================================================================
    // Shape writing
    // =========================================================================

    private static void writeShape(XMLSlideShow ss, XSLFSlide slide, ShapeSpec spec) {
        Rectangle2D anchor = toAnchor(spec)

        switch (spec.type) {
            case ShapeType.IMAGE:
                writeImageShape(ss, slide, spec, anchor)
                break
            case ShapeType.TABLE:
                writeTableShape(slide, spec.table, anchor)
                break
            default:
                writeTextShape(slide, spec, anchor)
        }
    }

    private static void writeTextShape(XSLFSlide slide, ShapeSpec spec, Rectangle2D anchor) {
        XSLFTextBox tb = slide.createTextBox()
        tb.anchor      = anchor.width > 0 ? anchor : new Rectangle2D.Double(50, 50, 500, 100)

        if (spec.richText) {
            tb.clearText()
            XSLFTextParagraph para = tb.addNewTextParagraph()
            spec.richText.each { RichRun rr ->
                if (!rr?.text) return
                XSLFTextRun run = para.addNewTextRun()
                run.setText(rr.text)
                if (rr.bold)        run.bold      = true
                if (rr.italic)      run.italic    = true
                if (rr.underline)   run.underlined = true
                if (rr.fontName)    run.fontFamily = rr.fontName
                if (rr.fontSize > 0) run.fontSize  = (double) rr.fontSize
                if (rr.color)       run.fontColor  = hexToColor(rr.color)
            }
        } else if (spec.text) {
            tb.text = spec.text
        }

        // Inline style — font size and fill
        if (spec.style?.font?.size > 0) {
            tb.textParagraphs.each { XSLFTextParagraph p ->
                p.textRuns.each { XSLFTextRun r -> r.fontSize = (double) spec.style.font.size }
            }
        }
        if (spec.style?.font?.color) {
            Color c = hexToColor(spec.style.font.color)
            tb.textParagraphs.each { XSLFTextParagraph p ->
                p.textRuns.each { XSLFTextRun r -> r.fontColor = c }
            }
        }
    }

    private static void writeImageShape(XMLSlideShow ss, XSLFSlide slide,
                                         ShapeSpec spec, Rectangle2D anchor) {
        if (!spec.imagePath) {
            log.warn 'PptxAdapter: IMAGE shape has no imagePath — skipping'
            return
        }
        File imgFile = new File(spec.imagePath)
        if (!imgFile.exists()) {
            log.warn 'PptxAdapter: image file not found: {}', spec.imagePath
            return
        }
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
        try {
            def ctSlide = slide.xmlObject
            def trans   = ctSlide.isSetTransition() ? ctSlide.transition : ctSlide.addNewTransition()
            // Duration in ms → tenths of a second for OOXML (advTm in ms, spd in string)
            switch (spec.type) {
                case TransitionType.FADE:  trans.addNewFade();  break
                case TransitionType.PUSH:  trans.addNewPush();  break
                case TransitionType.WIPE:  trans.addNewWipe();  break
                case TransitionType.SPLIT: trans.addNewStrips(); break
                case TransitionType.ZOOM:  trans.addNewZoom();  break
                default: break
            }
        } catch (Exception e) {
            log.debug 'PptxAdapter: could not apply transition {} — {}', spec.type, e.message
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static XSLFSlideLayout resolveLayout(XMLSlideShow ss, String layoutName) {
        if (!layoutName || ss.slideMasters.isEmpty()) return null
        ss.slideMasters.each { master ->
            XSLFSlideLayout found = master.slideLayouts.find { it.name == layoutName }
            if (found) return found
        }
        return null
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
