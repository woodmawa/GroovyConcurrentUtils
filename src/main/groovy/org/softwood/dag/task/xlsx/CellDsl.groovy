package org.softwood.dag.task.xlsx

import org.softwood.dag.task.xlsx.model.LinkSpec
import org.softwood.dag.task.xlsx.model.RichRun

/**
 * DSL builder for a single cell's content and appearance.
 *
 * <h3>Usage inside RowDsl:</h3>
 * <pre>
 *   cell('B') {
 *       value  'Revenue'
 *       style  'money'
 *       comment 'Sourced from ERP export'
 *   }
 *
 *   cell('C') {
 *       formula 'SUM(C2:C100)'
 *       style   'total'
 *   }
 *
 *   cell('D') {
 *       value  'See report'
 *       link   'https://reports.example.com'
 *   }
 *
 *   cell('E') {
 *       png image from '/tmp/logo.png'    // image shorthand
 *   }
 *
 *   cell('A1') {
 *       value   'Quarter'
 *       colspan 3           // merges A1:C1
 *   }
 * </pre>
 */
class CellDsl {

    private Object       _value
    private String       _formula
    private String       _styleRef
    private CommentSpec  _comment
    private LinkSpec      _link
    private List<RichRun> _richText   = []
    private int          _rowspan   = 1
    private int          _colspan   = 1
    private String       _cellName
    private String       _imagePath
    private String       _imageType = 'PNG'

    // -------------------------------------------------------------------------
    // Value / formula
    // -------------------------------------------------------------------------

    void value(Object v)    { _value   = v }
    void formula(String f)  { _formula = f }

    // -------------------------------------------------------------------------
    // Style
    // -------------------------------------------------------------------------

    void style(String name) { _styleRef = name }

    // -------------------------------------------------------------------------
    // Merging
    // -------------------------------------------------------------------------

    void colspan(int n) { _colspan = n }
    void rowspan(int n) { _rowspan = n }

    // -------------------------------------------------------------------------
    // Comment
    // -------------------------------------------------------------------------

    void comment(String text) {
        _comment = new CommentSpec(text: text)
    }

    void comment(Map<String, String> args) {
        _comment = new CommentSpec(text: args.text, author: args.author)
    }

    void comment(@DelegatesTo(CommentDsl) Closure cl) {
        def dsl = new CommentDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _comment = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Hyperlink / link
    // -------------------------------------------------------------------------

    /**
     * Plain link — type inferred from scheme:
     * {@code 'https://'} → URL, {@code 'mailto:'} → EMAIL, {@code '/'} → FILE.
     */
    void link(String url) {
        LinkSpec.LinkType type
        if (url.startsWith('mailto:'))   type = LinkSpec.LinkType.EMAIL
        else if (url.startsWith('file:')
              || url.startsWith('/'))    type = LinkSpec.LinkType.FILE
        else                             type = LinkSpec.LinkType.URL
        _link = new LinkSpec(type: type, target: url)
    }

    /**
     * Typed link with named arguments.
     * <pre>
     *   link url:'https://reports.example.com', display:'Report'
     *   link sheet:'Detail', cell:'A1'
     *   link file:'/reports/q3.pdf'
     *   link type:'INTERNAL', target:'Summary!B2'
     * </pre>
     */
    void link(Map<String, Object> args) {
        LinkSpec.LinkType type
        if (args.type instanceof LinkSpec.LinkType) {
            type = args.type as LinkSpec.LinkType
        } else if (args.type) {
            type = LinkSpec.LinkType.valueOf(args.type.toString().toUpperCase())
        } else if (args.sheet) {
            type = LinkSpec.LinkType.INTERNAL
        } else if (args.file) {
            type = LinkSpec.LinkType.FILE
        } else {
            type = LinkSpec.LinkType.URL
        }
        String target = args.target as String
                     ?: args.url   as String
                     ?: args.file  as String
                     ?: (args.sheet ? "${args.sheet}!${args.cell ?: 'A1'}" : null)
        _link = new LinkSpec(
            type   : type,
            target : target,
            display: (args.display ?: args.tooltip) as String
        )
    }

    // -------------------------------------------------------------------------
    // Rich text
    // -------------------------------------------------------------------------

    /**
     * Build mixed-format cell content from styled runs.
     * <pre>
     *   richText {
     *       run   'Plain text'
     *       bold  'Bold section'
     *       italic 'Italic', color:'#FF0000'
     *       run('Styled') { bold true; color '#0070C0' }
     *   }
     * </pre>
     */
    void richText(@DelegatesTo(RichTextDsl) Closure cl) {
        def dsl = new RichTextDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _richText = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Named cell / range
    // -------------------------------------------------------------------------

    void name(String n) { _cellName = n }

    // -------------------------------------------------------------------------
    // Images   (mirrors spreadsheet-builder API: png image from '/path')
    // -------------------------------------------------------------------------

    ImageBuilder getPng()  { new ImageBuilder('PNG',  this) }
    ImageBuilder getJpeg() { new ImageBuilder('JPEG', this) }
    ImageBuilder getEmf()  { new ImageBuilder('EMF',  this) }
    ImageBuilder getWmf()  { new ImageBuilder('WMF',  this) }

    /** Fluent "image from '/path'" helper — returned by the png/jpeg getters. */
    static class ImageBuilder {
        private final String   _type
        private final CellDsl  _owner

        ImageBuilder(String type, CellDsl owner) {
            _type  = type
            _owner = owner
        }

        CellDsl from(String pathOrUrl) {
            _owner._imagePath = pathOrUrl
            _owner._imageType = _type
            _owner
        }
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    CellSpec build(String ref = null, Integer colIndex = null) {
        new CellSpec(
            ref      : ref,
            colIndex : colIndex,
            value    : _value,
            formula  : _formula,
            styleRef : _styleRef,
            comment  : _comment,
            link     : _link,
            richText : new ArrayList<>(_richText),
            rowspan  : _rowspan,
            colspan  : _colspan,
            cellName : _cellName,
            imagePath: _imagePath,
            imageType: _imageType
        )
    }
}

// =============================================================================
// RichTextDsl  — builder for a sequence of styled runs
// =============================================================================

/**
 * DSL builder for a list of {@link RichRun}s inside a cell.
 * Obtained via {@link CellDsl#richText(Closure)}.
 */
class RichTextDsl {

    private final List<RichRun> _runs = []

    /** Append a plain-text run. */
    void run(String text) { _runs << new RichRun(text: text) }

    /** Append a styled run using a named-arg map. */
    void run(String text, Map<String, Object> style) { _runs << _buildRun(text, style) }

    /** Append a bold run with optional extra styling. {@code bold 'Total', color:'#FF0000'} */
    void bold(String text, Map<String, Object> opts = [:]) {
        _runs << _buildRun(text, (opts ?: [:]) + [bold: true])
    }

    /** Append an italic run with optional extra styling. */
    void italic(String text, Map<String, Object> opts = [:]) {
        _runs << _buildRun(text, (opts ?: [:]) + [italic: true])
    }

    /** Append a run using a full closure. */
    void run(@DelegatesTo(RichRunDsl) Closure cl) {
        def dsl = new RichRunDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _runs << dsl.build()
    }

    List<RichRun> build() { new ArrayList<>(_runs) }

    private static RichRun _buildRun(String text, Map<String, Object> s) {
        new RichRun(
            text     : text,
            bold     : s?.bold      as boolean ?: false,
            italic   : s?.italic    as boolean ?: false,
            underline: s?.underline as boolean ?: false,
            color    : s?.color     as String,
            fontName : s?.fontName  as String,
            fontSize : s?.fontSize  as Integer ?: 0
        )
    }
}

// =============================================================================
// RichRunDsl  — closure-form builder for a single run
// =============================================================================

/**
 * Closure-form DSL for a single {@link RichRun}.
 * Used inside {@link RichTextDsl#run(Closure)}.
 */
class RichRunDsl {

    private String  _text
    private boolean _bold      = false
    private boolean _italic    = false
    private boolean _underline = false
    private String  _color
    private String  _fontName
    private int     _fontSize  = 0

    void text(String t)            { _text      = t }
    void bold(boolean b = true)    { _bold      = b }
    void italic(boolean i = true)  { _italic    = i }
    void underline(boolean u=true) { _underline = u }
    void color(String c)           { _color     = c }
    void fontName(String n)        { _fontName  = n }
    void fontSize(int s)           { _fontSize  = s }

    RichRun build() {
        new RichRun(
            text     : _text,
            bold     : _bold,
            italic   : _italic,
            underline: _underline,
            color    : _color,
            fontName : _fontName,
            fontSize : _fontSize
        )
    }
}

// =============================================================================
// CommentDsl  (nested helper; also available standalone in RangeDsl)
// =============================================================================

/**
 * Fluent DSL for a cell comment.
 * <pre>
 *   comment {
 *       text   'Needs approval before release'
 *       author 'Finance Bot'
 *   }
 * </pre>
 */
class CommentDsl {
    private String _text
    private String _author

    void text(String t)   { _text   = t }
    void author(String a) { _author = a }

    CommentSpec build() { new CommentSpec(text: _text, author: _author) }
}
