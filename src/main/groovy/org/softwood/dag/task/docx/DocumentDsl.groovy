package org.softwood.dag.task.docx

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.xlsx.StyleDsl
import org.softwood.dag.task.xlsx.StyleSpec

/**
 * Fluent DSL builder for a complete Word document.
 *
 * <h3>Usage in DocxTask:</h3>
 * <pre>
 *   docxTask('generate-report') {
 *       file '/reports/q3.docx'
 *       document {
 *           title      'Q3 Financial Report'
 *           properties author:'Finance Bot', subject:'Q3 Results'
 *
 *           page { size 'A4'; orientation 'portrait'; marginTop 1440 }
 *
 *           style('heading1') {
 *               font bold:true, size:16, color:'#1F4E78'
 *           }
 *
 *           section {
 *               heading 'Executive Summary'
 *               text    'Revenue grew 12% year-on-year...'
 *           }
 *           section {
 *               heading 'Key Risks', 2
 *               bullets ['Market volatility', 'Supply chain delays']
 *           }
 *           section {
 *               heading 'Quarterly Data', 2
 *               table {
 *                   headers ['Quarter', 'Revenue', 'Cost']
 *                   row     ['Q1', '1.2M', '0.8M']
 *                   row     ['Q2', '1.4M', '0.9M']
 *               }
 *           }
 *       }
 *   }
 * </pre>
 */
class DocumentDsl {

    private final TaskContext          ctx
    private final Object               prevValue

    private       String               _title
    private final Map<String, String>  _properties  = [:]
    private final Map<String, StyleSpec> _styles    = [:]
    private final List<SectionSpec>    _sections    = []
    private final PageSpec             _page        = new PageSpec()

    DocumentDsl(TaskContext ctx, Object prevValue) {
        this.ctx       = ctx
        this.prevValue = prevValue
    }

    // -------------------------------------------------------------------------
    // Document-level properties
    // -------------------------------------------------------------------------

    void title(String t) { _title = t }

    /**
     * Set core document properties.
     * <pre>properties author:'Finance Bot', subject:'Q3 Results'</pre>
     * Supported keys: {@code author}, {@code subject}, {@code description},
     * {@code category}, {@code keywords}, {@code company}.
     */
    void properties(Map<String, String> props) {
        if (props) _properties.putAll(props)
    }

    // -------------------------------------------------------------------------
    // Page setup
    // -------------------------------------------------------------------------

    /**
     * Configure page layout.
     * <pre>
     *   page { size 'A4'; orientation 'landscape'; marginTop 720 }
     * </pre>
     */
    void page(@DelegatesTo(PageDsl) Closure cl) {
        def dsl = new PageDsl(_page)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
    }

    // -------------------------------------------------------------------------
    // Styles
    // -------------------------------------------------------------------------

    /**
     * Define a named paragraph / character style for this document.
     * <pre>
     *   style('heading1') { font bold:true, size:16, color:'#1F4E78' }
     *   style('body')     { font size:11; align horizontal:'left' }
     * </pre>
     */
    void style(String name, @DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _styles[name] = dsl.build(name)
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    /**
     * Append a content section.
     * <pre>
     *   section {
     *       heading 'Summary'
     *       text    'The quarter ended strongly...'
     *   }
     * </pre>
     */
    void section(@DelegatesTo(SectionDsl) Closure cl) {
        def dsl = new SectionDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _sections << dsl.build()
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    DocumentPlan build() {
        new DocumentPlan(
            title     : _title,
            styles    : new LinkedHashMap<>(_styles),
            sections  : new ArrayList<>(_sections),
            properties: new LinkedHashMap<>(_properties),
            page      : _page
        )
    }

    /**
     * Static convenience factory — applies the closure and returns the built plan.
     *
     * @param ctx       current task context
     * @param prevValue previous task result
     * @param cl        document DSL closure
     * @return immutable {@link DocumentPlan}
     */
    static DocumentPlan build(TaskContext ctx, Object prevValue, Closure cl) {
        def dsl = new DocumentDsl(ctx, prevValue)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        dsl.build()
    }

    // =========================================================================
    // PageDsl  —  inner builder; mutates the shared PageSpec instance
    // =========================================================================

    /**
     * DSL for page setup, delegated from {@link DocumentDsl#page(Closure)}.
     */
    static class PageDsl {

        private final PageSpec _spec

        PageDsl(PageSpec spec) { _spec = spec }

        /** Paper size: {@code 'A4'} | {@code 'LETTER'} | {@code 'A3'} | {@code 'LEGAL'} */
        void size(String s)        { _spec.size        = s }
        /** Orientation: {@code 'portrait'} | {@code 'landscape'} */
        void orientation(String o) { _spec.orientation = o }

        void marginTop(int v)      { _spec.marginTop    = v }
        void marginBottom(int v)   { _spec.marginBottom = v }
        void marginLeft(int v)     { _spec.marginLeft   = v }
        void marginRight(int v)    { _spec.marginRight  = v }

        /** Set all four margins to the same value (twips). */
        void margins(int all) {
            _spec.marginTop = _spec.marginBottom = _spec.marginLeft = _spec.marginRight = all
        }

        /**
         * Set vertical and horizontal margins independently (twips).
         * <pre>margins 1440, 1800</pre>
         */
        void margins(int vertical, int horizontal) {
            _spec.marginTop    = _spec.marginBottom = vertical
            _spec.marginLeft   = _spec.marginRight  = horizontal
        }
    }
}
