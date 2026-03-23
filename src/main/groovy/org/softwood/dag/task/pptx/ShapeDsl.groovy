package org.softwood.dag.task.pptx

import org.softwood.dag.task.xlsx.StyleDsl
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.RichTextDsl
import org.softwood.dag.task.xlsx.model.RichRun
import org.softwood.dag.task.docx.TableDsl
import org.softwood.dag.task.docx.TableSpec

/**
 * DSL builder for a single content shape on a slide.
 *
 * <p>Position and size values are in EMUs (English Metric Units).
 * Use the {@code *Inches} helpers for readability:
 * {@code 914400 EMU = 1 inch}.</p>
 *
 * <h3>Usage inside SlideDsl:</h3>
 * <pre>
 *   // Text box
 *   shape {
 *       type          'TEXT_BOX'
 *       text          'Q3 Results'
 *       style         { font bold:true, size:24, color:'#1F4E78' }
 *       positionInches 0.5, 1.0
 *       sizeInches     9.0, 1.5
 *   }
 *
 *   // Rich-text box
 *   shape {
 *       richText {
 *           run  'Total: '
 *           bold '$4.2M', color:'#1F4E78'
 *       }
 *       positionInches 0.5, 2.5
 *       sizeInches     4.5, 1.0
 *   }
 *
 *   // Embedded table
 *   shape {
 *       type  'TABLE'
 *       table {
 *           headers ['Quarter', 'Revenue']
 *           row     ['Q1', '1.2M']
 *           row     ['Q2', '1.4M']
 *           headerStyle { fill '#1F4E78'; font bold:true, color:'#FFFFFF' }
 *       }
 *       positionInches 0.5, 2.0
 *       sizeInches     9.0, 4.5
 *   }
 *
 *   // Image
 *   shape {
 *       image '/assets/arch-diagram.png'
 *       positionInches 0.5, 1.0
 *       sizeInches     9.0, 5.5
 *   }
 * </pre>
 */
class ShapeDsl {

    /** 1 inch expressed in EMUs. */
    static final long EMU_PER_INCH = 914400L

    private ShapeType       _type     = ShapeType.TEXT_BOX
    private String          _text
    private List<RichRun>   _richText = []
    private TableSpec       _table
    private String          _imagePath
    private StyleSpec       _style
    private long            _x        = 0L
    private long            _y        = 0L
    private long            _width    = 0L
    private long            _height   = 0L

    // -------------------------------------------------------------------------
    // Type
    // -------------------------------------------------------------------------

    /**
     * Set shape type by enum value.
     * <pre>type ShapeType.IMAGE</pre>
     */
    void type(ShapeType t) { _type = t }

    /**
     * Set shape type by name (case-insensitive).
     * <pre>type 'TABLE'</pre>
     */
    void type(String t) { _type = ShapeType.valueOf(t.toUpperCase()) }

    // -------------------------------------------------------------------------
    // Content
    // -------------------------------------------------------------------------

    void text(String t) { _text = t }

    /**
     * Build mixed-format text content from styled runs.
     * When non-empty takes precedence over {@link #text(String)}.
     * <pre>
     *   richText {
     *       run  'Revenue: '
     *       bold '$4.2M', color:'#1F4E78'
     *   }
     * </pre>
     */
    void richText(@DelegatesTo(RichTextDsl) Closure cl) {
        def dsl = new RichTextDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _richText = dsl.build()
        _type = ShapeType.TEXT_BOX
    }

    /**
     * Embed a table in this shape.
     * Sets {@link #type} to {@code TABLE} automatically.
     * <pre>
     *   table {
     *       headers ['Q', 'Rev', 'Cost']
     *       row     ['Q1', '1.2M', '0.8M']
     *   }
     * </pre>
     */
    void table(@DelegatesTo(TableDsl) Closure cl) {
        def dsl = new TableDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _table = dsl.build()
        _type  = ShapeType.TABLE
    }

    /**
     * Set an image path or URL.  Sets {@link #type} to {@code IMAGE} automatically.
     * <pre>image '/assets/logo.png'</pre>
     */
    void image(String path) {
        _imagePath = path
        _type      = ShapeType.IMAGE
    }

    // -------------------------------------------------------------------------
    // Style
    // -------------------------------------------------------------------------

    /**
     * Inline style override for this shape.
     * <pre>style { font bold:true, size:18, color:'#1F4E78' }</pre>
     */
    void style(@DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _style = dsl.build('__shape__')
    }

    // -------------------------------------------------------------------------
    // Position and size — raw EMU
    // -------------------------------------------------------------------------

    /**
     * Set position in EMUs from the slide top-left corner.
     * <pre>position 457200L, 685800L  // 0.5", 0.75"</pre>
     */
    void position(long x, long y) { _x = x; _y = y }

    /**
     * Set dimensions in EMUs.
     * <pre>size 8229600L, 4572000L  // 9" × 5"</pre>
     */
    void size(long width, long height) { _width = width; _height = height }

    // -------------------------------------------------------------------------
    // Position and size — inch convenience
    // -------------------------------------------------------------------------

    /**
     * Set position in inches (converted to EMUs internally).
     * <pre>positionInches 0.5, 1.0</pre>
     */
    void positionInches(double x, double y) {
        _x = (long) (x * EMU_PER_INCH)
        _y = (long) (y * EMU_PER_INCH)
    }

    /**
     * Set dimensions in inches (converted to EMUs internally).
     * <pre>sizeInches 9.0, 5.5</pre>
     */
    void sizeInches(double w, double h) {
        _width  = (long) (w * EMU_PER_INCH)
        _height = (long) (h * EMU_PER_INCH)
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    ShapeSpec build() {
        new ShapeSpec(
            type     : _type,
            text     : _text,
            richText : new ArrayList<>(_richText),
            table    : _table,
            imagePath: _imagePath,
            style    : _style,
            x        : _x,
            y        : _y,
            width    : _width,
            height   : _height
        )
    }
}
