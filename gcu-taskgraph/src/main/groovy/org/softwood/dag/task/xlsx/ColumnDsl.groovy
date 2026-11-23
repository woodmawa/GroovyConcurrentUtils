package org.softwood.dag.task.xlsx

/**
 * DSL builder for a column's presentation properties.
 *
 * <h3>Usage inside SheetDsl:</h3>
 * <pre>
 *   column 'A', width: 20
 *   column 'B:D', style: 'money', width: 14
 *   column('E') {
 *       autoSize true
 *       style    'date'
 *   }
 *   column 'F', hidden: true
 * </pre>
 */
class ColumnDsl {

    private float   _width        = 0f
    private boolean _hidden       = false
    private boolean _autoSize     = false
    private String  _styleRef
    private int     _outlineLevel = 0

    void width(Number w)        { _width        = w.floatValue() }
    void hidden(boolean h=true) { _hidden       = h }
    void autoSize(boolean a=true){ _autoSize    = a }
    void style(String s)        { _styleRef     = s }
    void outlineLevel(int l)    { _outlineLevel = l }

    ColumnSpec build(String ref) {
        new ColumnSpec(
            ref         : ref,
            width       : _width,
            hidden      : _hidden,
            autoSize    : _autoSize,
            styleRef    : _styleRef,
            outlineLevel: _outlineLevel
        )
    }
}
