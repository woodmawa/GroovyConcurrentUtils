package org.softwood.dag.task.pptx

import org.softwood.dag.task.TaskContext
import org.softwood.dag.task.xlsx.StyleDsl
import org.softwood.dag.task.xlsx.StyleSpec

/**
 * Fluent DSL builder for a complete PowerPoint presentation.
 *
 * <h3>Usage in PptxTask:</h3>
 * <pre>
 *   pptxTask('generate-deck') {
 *       file '/decks/q3-review.pptx'
 *       presentation {
 *           title      'Q3 Financial Review'
 *           properties author:'Finance Bot', subject:'Q3 Results'
 *
 *           layout {
 *               theme         'Office Theme'
 *               background    '#FFFFFF'
 *               fontFamily    'Calibri'
 *               titleFontSize 28
 *               bodyFontSize  18
 *               accentColor   '#1F4E78'
 *           }
 *
 *           style('highlight') { font bold:true, color:'#FF0000' }
 *
 *           slide {
 *               title  'Q3 Financial Review'
 *               shape { type 'TEXT_BOX'; text 'Prepared by Finance Bot'; positionInches 1.0, 5.5; sizeInches 8.0, 1.0 }
 *           }
 *           slide {
 *               title  'Revenue Summary'
 *               shape { table { headers ['Q1','Q2','Q3']; row ['1.2M','1.4M','1.6M'] }; positionInches 1.0, 1.5; sizeInches 8.0, 4.0 }
 *           }
 *       }
 *   }
 * </pre>
 */
class PresentationDsl {

    private final TaskContext              ctx
    private final Object                   prevValue

    private       String                   _title
    private final Map<String, String>      _properties   = [:]
    private final Map<String, StyleSpec>   _styles       = [:]
    private final List<SlidePlan>          _slides       = []
    private final SlideLayoutSpec          _defaultLayout = new SlideLayoutSpec()

    PresentationDsl(TaskContext ctx, Object prevValue) {
        this.ctx       = ctx
        this.prevValue = prevValue
    }

    // -------------------------------------------------------------------------
    // Presentation-level properties
    // -------------------------------------------------------------------------

    void title(String t) { _title = t }

    /**
     * Set core document properties.
     * <pre>properties author:'Finance Bot', subject:'Q3 Results'</pre>
     */
    void properties(Map<String, String> props) {
        if (props) _properties.putAll(props)
    }

    // -------------------------------------------------------------------------
    // Default layout
    // -------------------------------------------------------------------------

    /**
     * Configure the default slide layout / theme.
     * <pre>
     *   layout {
     *       theme         'Office Theme'
     *       background    '#FFFFFF'
     *       titleFontSize 28
     *       accentColor   '#1F4E78'
     *   }
     * </pre>
     */
    void layout(@DelegatesTo(LayoutDsl) Closure cl) {
        def dsl = new LayoutDsl(_defaultLayout)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
    }

    // -------------------------------------------------------------------------
    // Styles
    // -------------------------------------------------------------------------

    /**
     * Define a named text style for this presentation.
     * <pre>style('highlight') { font bold:true, color:'#FF0000' }</pre>
     */
    void style(String name, @DelegatesTo(StyleDsl) Closure cl) {
        def dsl = new StyleDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _styles[name] = dsl.build(name)
    }

    // -------------------------------------------------------------------------
    // Slides
    // -------------------------------------------------------------------------

    /**
     * Append a slide.
     * <pre>
     *   slide {
     *       title 'Revenue Summary'
     *       shape { type 'TABLE'; table { headers ['Q','Rev']; row ['Q1','1.2M'] } }
     *   }
     * </pre>
     */
    void slide(@DelegatesTo(SlideDsl) Closure cl) {
        def dsl = new SlideDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _slides << dsl.build()
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    PresentationPlan build() {
        new PresentationPlan(
            title        : _title,
            defaultLayout: _defaultLayout,
            slides       : new ArrayList<>(_slides),
            styles       : new LinkedHashMap<>(_styles),
            properties   : new LinkedHashMap<>(_properties)
        )
    }

    /**
     * Static convenience factory — applies the closure and returns the built plan.
     *
     * @param ctx       current task context
     * @param prevValue previous task result
     * @param cl        presentation DSL closure
     * @return immutable {@link PresentationPlan}
     */
    static PresentationPlan build(TaskContext ctx, Object prevValue, Closure cl) {
        def dsl = new PresentationDsl(ctx, prevValue)
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        dsl.build()
    }

    // =========================================================================
    // LayoutDsl  —  inner builder; mutates the shared SlideLayoutSpec instance
    // =========================================================================

    /**
     * DSL for the default slide layout, delegated from
     * {@link PresentationDsl#layout(Closure)}.
     */
    static class LayoutDsl {

        private final SlideLayoutSpec _spec

        LayoutDsl(SlideLayoutSpec spec) { _spec = spec }

        /** Theme name recognised by the POI adapter, e.g. {@code 'Office Theme'}. */
        void theme(String t)         { _spec.theme         = t }
        /** Slide background fill colour: {@code '#RRGGBB'}. */
        void background(String c)    { _spec.background    = c }
        /** Default font family, e.g. {@code 'Calibri'}. */
        void fontFamily(String f)    { _spec.fontFamily    = f }
        /** Title text point size. */
        void titleFontSize(int s)    { _spec.titleFontSize = s }
        /** Body text point size. */
        void bodyFontSize(int s)     { _spec.bodyFontSize  = s }
        /** Accent / highlight colour: {@code '#RRGGBB'}. */
        void accentColor(String c)   { _spec.accentColor   = c }
    }
}
