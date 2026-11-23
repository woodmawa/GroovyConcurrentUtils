package org.softwood.dag.task.xlsx.model

import groovy.transform.ToString

/**
 * A single styled run within a rich-text cell value.
 *
 * <p>A rich-text cell is a sequence of {@code RichRun} objects, each carrying
 * its own font attributes.  Runs are assembled via
 * {@link org.softwood.dag.task.xlsx.CellDsl#richText(Closure)} and stored on
 * {@link org.softwood.dag.task.xlsx.CellSpec#richText}.</p>
 *
 * <h3>DSL example:</h3>
 * <pre>
 *   richText {
 *       run   'Total: '
 *       bold  '$1,234.56', color:'#1F4E78'
 *       italic ' (estimated)'
 *       run('Custom') { fontSize 9; fontName 'Courier New' }
 *   }
 * </pre>
 */
@ToString(includeNames = true, includeFields = true)
class RichRun {

    /** The text content of this run. */
    String  text

    /** {@code true} to render this run in bold. */
    boolean bold      = false

    /** {@code true} to render this run in italics. */
    boolean italic    = false

    /** {@code true} to underline this run. */
    boolean underline = false

    /**
     * Foreground (text) colour in {@code '#RRGGBB'} hex format.
     * {@code null} inherits the cell/style default.
     */
    String  color

    /**
     * Font family override, e.g. {@code 'Arial'}.
     * {@code null} inherits the cell/style default.
     */
    String  fontName

    /**
     * Point size override.  {@code 0} (default) inherits the cell/style default.
     */
    int     fontSize  = 0
}
