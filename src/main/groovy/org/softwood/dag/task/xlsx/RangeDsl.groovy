package org.softwood.dag.task.xlsx

/**
 * DSL builder for operations applied to a rectangular cell range.
 *
 * <h3>Usage inside SheetDsl:</h3>
 * <pre>
 *   // Apply a named style
 *   range 'A1:H1', style: 'header'
 *
 *   // Full closure form
 *   range('B2:E100') {
 *       style    'money'
 *       borders  'thin'
 *       comment  'Imported from ERP'
 *   }
 *
 *   // Write values into a range
 *   range('A2:C4') {
 *       values [[1,2,3],[4,5,6],[7,8,9]]
 *   }
 *
 *   // Zebra striping
 *   range('A1:G500') {
 *       zebraStripe true
 *       borders     'thin'
 *   }
 *
 *   // Dropdown validation
 *   range('F2:F500') {
 *       dataValidation list: ['Open', 'Closed', 'Pending'],
 *                      errorMessage: 'Must be Open, Closed or Pending'
 *   }
 *
 *   // Full validation DSL
 *   range('B2:B100') {
 *       dataValidation {
 *           type        'number'
 *           operator    'between'
 *           formula1    '0'
 *           formula2    '1000000'
 *           errorTitle  'Out of range'
 *           errorMessage 'Please enter a value between 0 and 1,000,000'
 *       }
 *   }
 *
 *   // Conditional format
 *   range('C2:C100') {
 *       conditionalFormat {
 *           type     'cellValue'
 *           operator 'lessThan'
 *           value1   0
 *           styleRef 'negativeNumber'
 *       }
 *   }
 * </pre>
 */
class RangeDsl {

    private String                  _styleRef
    private boolean                 _zebraStripe        = false
    private String                  _borders
    private String                  _comment
    private List                    _values
    private DataValidationSpec      _dataValidation
    private ConditionalFormatSpec   _conditionalFormat
    private String                  _ref
    private String                  _formula
    private String                  _columnFormula
    private StyleSpec               _inlineStyle

    // -------------------------------------------------------------------------
    // Style / formatting
    // -------------------------------------------------------------------------

    void style(String name) { _styleRef    = name }

    /**
     * Inline style definition via closure — takes precedence over {@link #style(String)}.
     * <pre>range('A1:H1') { style { font bold:true; fill '#1F4E78' } }</pre>
     */
    void style(@DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _inlineStyle = dsl.build('__inline__')
    }

    void borders(String s)  { _borders     = s }
    void zebraStripe(boolean z = true) { _zebraStripe = z }

    // -------------------------------------------------------------------------
    // Comment
    // -------------------------------------------------------------------------

    void comment(String text) { _comment = text }

    // -------------------------------------------------------------------------
    // Values
    // -------------------------------------------------------------------------

    /**
     * Populate the range with values (row-major).
     * <pre>values [[row1col1, row1col2], [row2col1, row2col2]]</pre>
     */
    void values(List data) { _values = data }

    // -------------------------------------------------------------------------
    // Data validation — map shorthand
    // -------------------------------------------------------------------------

    /**
     * Shorthand for a simple list-type validation.
     * <pre>dataValidation list: ['Yes', 'No'], errorMessage: 'Choose Yes or No'</pre>
     */
    void dataValidation(Map<String, Object> args) {
        def spec = new DataValidationSpec()
        if (args.type)         spec.type              = args.type as String
        if (args.list)         { spec.type = 'list';  spec.list = args.list as List<String> }
        if (args.formula1)     spec.formula1          = args.formula1 as String
        if (args.formula2)     spec.formula2          = args.formula2 as String
        if (args.operator)     spec.operator          = args.operator as String
        if (args.errorTitle)   spec.errorTitle        = args.errorTitle as String
        if (args.errorMessage) spec.errorMessage      = args.errorMessage as String
        if (args.errorStyle)   spec.errorStyle        = args.errorStyle as String
        if (args.inputTitle)   spec.inputTitle        = args.inputTitle as String
        if (args.inputMessage) spec.inputMessage      = args.inputMessage as String
        _dataValidation = spec
    }

    /** Full closure form for data validation. */
    void dataValidation(@DelegatesTo(DataValidationDsl) Closure cl) {
        def dsl = new DataValidationDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _dataValidation = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Conditional formatting
    // -------------------------------------------------------------------------

    void conditionalFormat(@DelegatesTo(ConditionalFormatDsl) Closure cl) {
        def dsl = new ConditionalFormatDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _conditionalFormat = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Setter methods (alternative to passing ref to build)
    // -------------------------------------------------------------------------

    /** Set the range ref from within a closure: {@code ref 'B2:D10'} */
    void ref(String r) { _ref = r }

    /** Per-cell formula applied to every cell in the range. */
    void formula(String f) { _formula = f }

    /**
     * Column formula template applied row by row.
     * {@code {r}} is replaced with the 1-based row number.
     * <pre>columnFormula 'B{r}-C{r}'</pre>
     */
    void columnFormula(String pattern) { _columnFormula = pattern }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    RangeSpec build(String ref) {
        new RangeSpec(
            ref                : ref,
            styleRef           : _styleRef,
            zebraStripe        : _zebraStripe,
            borders            : _borders,
            comment            : _comment,
            values             : _values,
            dataValidation     : _dataValidation,
            conditionalFormat  : _conditionalFormat,
            formula            : _formula,
            columnFormula      : _columnFormula,
            inlineStyle        : _inlineStyle
        )
    }

    /** No-arg form — uses the ref set via {@link #ref(String)}. */
    RangeSpec build() { build(_ref) }
}

// =============================================================================
// DataValidationDsl  — full closure form
// =============================================================================

/**
 * DSL builder for a full data-validation specification.
 */
class DataValidationDsl {

    String          type              = 'list'
    List<String>    list              = []
    String          formula1
    String          formula2
    String          operator
    boolean         showErrorMessage  = true
    String          errorTitle
    String          errorMessage
    String          errorStyle        = 'stop'
    boolean         showInputMessage  = false
    String          inputTitle
    String          inputMessage

    void type(String t)           { this.type              = t }
    void list(List<String> l)     { this.type = 'list';    this.list = l }
    void formula1(String f)       { this.formula1          = f }
    void formula2(String f)       { this.formula2          = f }
    void operator(String o)       { this.operator          = o }
    void errorTitle(String t)     { this.errorTitle        = t }
    void errorMessage(String m)   { this.errorMessage      = m }
    void errorStyle(String s)     { this.errorStyle        = s }
    void inputTitle(String t)     { this.inputTitle        = t }
    void inputMessage(String m)   { this.inputMessage      = m; this.showInputMessage = true }

    DataValidationSpec build() {
        new DataValidationSpec(
            type            : type,
            list            : list,
            formula1        : formula1,
            formula2        : formula2,
            operator        : operator,
            showErrorMessage: showErrorMessage,
            errorTitle      : errorTitle,
            errorMessage    : errorMessage,
            errorStyle      : errorStyle,
            showInputMessage: showInputMessage,
            inputTitle      : inputTitle,
            inputMessage    : inputMessage
        )
    }
}

// =============================================================================
// ConditionalFormatDsl  — full closure form
// =============================================================================

/**
 * DSL builder for a conditional-formatting rule.
 */
class ConditionalFormatDsl {

    String  type     = 'cellValue'
    String  operator
    Object  value1
    Object  value2
    String  styleRef
    String  formula

    void type(String t)     { this.type     = t }
    void operator(String o) { this.operator = o }
    void value1(Object v)   { this.value1   = v }
    void value2(Object v)   { this.value2   = v }
    void styleRef(String s) { this.styleRef = s }
    void formula(String f)  { this.formula  = f; this.type = 'formula' }

    ConditionalFormatSpec build() {
        new ConditionalFormatSpec(
            type    : type,
            operator: operator,
            value1  : value1,
            value2  : value2,
            styleRef: styleRef,
            formula : formula
        )
    }
}
