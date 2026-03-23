package org.softwood.dag.task.docx

import org.softwood.dag.task.xlsx.StyleDsl
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.RichTextDsl
import org.softwood.dag.task.xlsx.model.RichRun

/**
 * DSL builder for a single document section.
 *
 * <h3>Usage inside DocumentDsl:</h3>
 * <pre>
 *   // Heading with plain text
 *   section {
 *       heading 'Executive Summary'
 *       text    'Revenue grew 12% year-on-year...'
 *   }
 *
 *   // Heading at level 2 with rich text
 *   section {
 *       heading 'Key Findings', 2
 *       richText {
 *           run  'Net revenue: '
 *           bold '$4.2M', color:'#1F4E78'
 *       }
 *   }
 *
 *   // Bullet list
 *   section {
 *       heading 'Risks', 2
 *       bullets ['Market volatility', 'Supply chain delays', 'Regulatory changes']
 *   }
 *
 *   // Numbered list
 *   section {
 *       heading 'Action Items', 2
 *       numbered ['Approve budget', 'Sign contract', 'Notify stakeholders']
 *   }
 *
 *   // Inline table
 *   section {
 *       heading 'Quarterly Breakdown', 2
 *       table {
 *           headers ['Quarter', 'Revenue', 'Cost', 'Margin']
 *           rows    [['Q1','1.2M','0.8M','33%'],
 *                    ['Q2','1.4M','0.9M','36%']]
 *       }
 *   }
 *
 *   // Style override
 *   section {
 *       text  'Note: figures are unaudited.'
 *       style { font italic:true, size:9, color:'#666666' }
 *   }
 * </pre>
 */
class SectionDsl {

    private String        _heading
    private int           _headingLevel  = 1
    private String        _text
    private List<RichRun> _richText      = []
    private TableSpec     _table
    private List<String>  _bulletList    = []
    private List<String>  _numberedList  = []
    private StyleSpec     _style

    // -------------------------------------------------------------------------
    // Heading
    // -------------------------------------------------------------------------

    /**
     * Set section heading text and optional level.
     * <pre>
     *   heading 'Summary'       // H1 (default)
     *   heading 'Details', 2   // H2
     * </pre>
     */
    void heading(String text, int level = 1) {
        _heading      = text
        _headingLevel = level
    }

    // -------------------------------------------------------------------------
    // Content
    // -------------------------------------------------------------------------

    void text(String t) { _text = t }

    /**
     * Build mixed-format paragraph content from styled runs.
     * When non-empty this takes precedence over {@link #text(String)}.
     * <pre>
     *   richText {
     *       run   'Total: '
     *       bold  '$1,234', color:'#1F4E78'
     *       italic ' (estimated)'
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
    // Table
    // -------------------------------------------------------------------------

    /**
     * Embed a table in this section.
     * <pre>
     *   table {
     *       headers ['Col A', 'Col B']
     *       row     ['val1', 'val2']
     *   }
     * </pre>
     */
    void table(@DelegatesTo(TableDsl) Closure cl) {
        def dsl = new TableDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _table = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Lists
    // -------------------------------------------------------------------------

    /** Unordered (bullet) list. {@code bullets ['Item 1', 'Item 2']} */
    void bullets(List<String> items) { _bulletList = new ArrayList<>(items) }

    /** Ordered (numbered) list. {@code numbered ['Step 1', 'Step 2']} */
    void numbered(List<String> items) { _numberedList = new ArrayList<>(items) }

    // -------------------------------------------------------------------------
    // Style
    // -------------------------------------------------------------------------

    /**
     * Inline paragraph-style override for this section.
     * <pre>style { font italic:true, size:9 }</pre>
     */
    void style(@DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _style = dsl.build('__section__')
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    SectionSpec build() {
        new SectionSpec(
            heading     : _heading,
            headingLevel: _headingLevel,
            text        : _text,
            richText    : new ArrayList<>(_richText),
            table       : _table,
            bulletList  : new ArrayList<>(_bulletList),
            numberedList: new ArrayList<>(_numberedList),
            style       : _style
        )
    }
}
