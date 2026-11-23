package org.softwood.dag.task.xlsx

import groovy.transform.CompileStatic

/**
 * DSL builder for a named, reusable cell style.
 *
 * <h3>Usage inside XlsxWorkbookDsl:</h3>
 * <pre>
 *   style('header') {
 *       font  bold: true, size: 12, color: '#FFFFFF'
 *       fill  '#1F4E78'
 *       align horizontal: 'center', vertical: 'middle'
 *       border all: 'thin'
 *   }
 *
 *   style('money') {
 *       dataFormat '#,##0.00'
 *       align horizontal: 'right'
 *   }
 *
 *   style('dateCol') {
 *       dataFormat 'dd/MM/yyyy'
 *       font italic: true
 *   }
 * </pre>
 */
class StyleDsl {

    private final FontSpec      _font      = new FontSpec()
    private final FillSpec      _fill      = new FillSpec()
    private final BorderSpec    _border    = new BorderSpec()
    private final AlignmentSpec _alignment = new AlignmentSpec()
    private       String        _dataFormat
    private       boolean       _locked    = false
    private       boolean       _hidden    = false
    private       String        _extendsStyle

    // -------------------------------------------------------------------------
    // Font
    // -------------------------------------------------------------------------

    /**
     * Configure font using a named-arg map.
     * <pre>font bold: true, size: 14, color: '#FF0000', name: 'Arial'</pre>
     */
    void font(Map<String, Object> args) {
        if (args.bold        != null) _font.bold          = args.bold as boolean
        if (args.italic      != null) _font.italic        = args.italic as boolean
        if (args.underline   != null) _font.underline     = args.underline as boolean
        if (args.strike      != null) _font.strikethrough = args.strike as boolean
        if (args.size        != null) _font.size          = args.size as int
        if (args.color       != null) _font.color         = args.color as String
        if (args.name        != null) _font.name          = args.name as String
    }

    /** Configure font with a closure delegating to FontSpec directly. */
    void font(@DelegatesTo(FontSpec) Closure cl) {
        cl.delegate = _font
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
    }

    // -------------------------------------------------------------------------
    // Fill
    // -------------------------------------------------------------------------

    /** Solid fill shorthand: {@code fill '#D9EAF7'} */
    void fill(String hexColor) {
        _fill.foreground = hexColor
        _fill.pattern    = 'solid'
    }

    /**
     * Patterned fill: {@code fill foreground: '#D9EAF7', pattern: 'fineDots'}
     */
    void fill(Map<String, Object> args) {
        if (args.foreground != null) _fill.foreground = args.foreground as String
        if (args.background != null) _fill.background = args.background as String
        if (args.pattern    != null) _fill.pattern    = args.pattern    as String
    }

    // -------------------------------------------------------------------------
    // Borders
    // -------------------------------------------------------------------------

    /**
     * Configure all four borders with a map.
     * <pre>border all: 'thin', color: '#000000'</pre>
     * <pre>border top: 'medium', bottom: 'thin'</pre>
     */
    void border(Map<String, Object> args) {
        if (args.all    != null) _border.all    = args.all    as String
        if (args.top    != null) _border.top    = args.top    as String
        if (args.bottom != null) _border.bottom = args.bottom as String
        if (args.left   != null) _border.left   = args.left   as String
        if (args.right  != null) _border.right  = args.right  as String
        if (args.color  != null) _border.color  = args.color  as String
    }

    /** All-sides shorthand: {@code border 'thin'} */
    void border(String style) { _border.all = style }

    // -------------------------------------------------------------------------
    // Alignment
    // -------------------------------------------------------------------------

    /**
     * Configure text alignment.
     * <pre>align horizontal: 'center', vertical: 'middle', wrap: true</pre>
     */
    void align(Map<String, Object> args) {
        if (args.horizontal != null) _alignment.horizontal = args.horizontal as String
        if (args.vertical   != null) _alignment.vertical   = args.vertical   as String
        if (args.wrap       != null) _alignment.wrapText   = args.wrap       as boolean
        if (args.indent     != null) _alignment.indent     = args.indent     as int
        if (args.rotation   != null) _alignment.rotation   = args.rotation   as int
    }

    // -------------------------------------------------------------------------
    // Style inheritance
    // -------------------------------------------------------------------------

    /** Inherit from a named parent style; this style's properties override the parent. */
    void extend(String parentStyleName) { _extendsStyle = parentStyleName }

    // -------------------------------------------------------------------------
    // Data format / protection
    // -------------------------------------------------------------------------

    /** Excel data-format string, e.g. {@code '#,##0.00'}. */
    void dataFormat(String fmt) { _dataFormat = fmt }

    /** Currency format: {@code '#,##0.00'} */
    void currency()               { _dataFormat = '#,##0.00' }
    /** Currency with ISO code label: {@code '#,##0.00 [$USD]'} */
    void currency(String isoCode) { _dataFormat = "#,##0.00 [\$${isoCode}]" }
    /** Percentage format: {@code '0.00%'} */
    void percent()                { _dataFormat = '0.00%' }
    /** Percentage with specific decimal places: {@code percent(1)} → {@code '0.0%'} */
    void percent(int decimals)    { _dataFormat = "0.${'0' * decimals}%" }
    /** Integer format: {@code '#,##0'} */
    void integer()                { _dataFormat = '#,##0' }
    /** Short date format: {@code 'dd/mm/yyyy'} */
    void date()                   { _dataFormat = 'dd/mm/yyyy' }
    /** Date with custom pattern, e.g. {@code 'yyyy-MM-dd'} */
    void date(String pattern)     { _dataFormat = pattern }
    /** DateTime format: {@code 'dd/mm/yyyy hh:mm'} */
    void dateTime()               { _dataFormat = 'dd/mm/yyyy hh:mm' }
    /** Scientific notation: {@code '0.00E+00'} */
    void scientific()             { _dataFormat = '0.00E+00' }
    /** Text / string format: {@code '@'} */
    void text()                   { _dataFormat = '@' }

    void locked(boolean v = true) { _locked = v }
    void hidden(boolean v = true) { _hidden = v }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    StyleSpec build(String name) {
        new StyleSpec(
            name        : name,
            font        : _font,
            fill        : _fill,
            border      : _border,
            alignment   : _alignment,
            dataFormat  : _dataFormat,
            locked      : _locked,
            hidden      : _hidden,
            extendsStyle: _extendsStyle
        )
    }
}
