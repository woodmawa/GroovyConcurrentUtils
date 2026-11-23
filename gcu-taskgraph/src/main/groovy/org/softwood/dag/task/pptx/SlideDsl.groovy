package org.softwood.dag.task.pptx

/**
 * DSL builder for a single presentation slide.
 *
 * <h3>Usage inside PresentationDsl:</h3>
 * <pre>
 *   // Title slide with a text shape
 *   slide {
 *       title 'Q3 Financial Review'
 *       notes 'Opening remarks — keep to 2 minutes'
 *       shape {
 *           type          'TEXT_BOX'
 *           text          'Prepared by Finance Bot'
 *           positionInches 1.0, 5.5
 *           sizeInches     8.0, 0.8
 *       }
 *   }
 *
 *   // Content slide with a table
 *   slide {
 *       title  'Revenue by Quarter'
 *       layout 'TITLE_CONTENT'
 *       shape {
 *           table {
 *               headers ['Quarter', 'Revenue', 'Cost']
 *               row     ['Q1', '1.2M', '0.8M']
 *               row     ['Q2', '1.4M', '0.9M']
 *           }
 *           positionInches 0.5, 1.5
 *           sizeInches     9.0, 5.0
 *       }
 *       transition { type 'FADE'; duration 400 }
 *   }
 *
 *   // Image slide
 *   slide {
 *       title 'Architecture Overview'
 *       shape {
 *           image '/assets/arch-diagram.png'
 *           positionInches 0.5, 1.0
 *           sizeInches     9.0, 5.5
 *       }
 *   }
 * </pre>
 */
class SlideDsl {

    private String           _title
    private String           _layout
    private String           _notes
    private List<ShapeSpec>  _shapes     = []
    private TransitionSpec   _transition

    // -------------------------------------------------------------------------
    // Slide metadata
    // -------------------------------------------------------------------------

    void title(String t)  { _title  = t }
    void layout(String l) { _layout = l }
    void notes(String n)  { _notes  = n }

    // -------------------------------------------------------------------------
    // Shapes
    // -------------------------------------------------------------------------

    /**
     * Add a content shape to the slide.
     * Shapes are rendered in z-order (first = bottom-most).
     * <pre>
     *   shape {
     *       type          'TEXT_BOX'
     *       text          'Subtitle text'
     *       positionInches 1.0, 4.5
     *       sizeInches     8.0, 1.2
     *   }
     * </pre>
     */
    void shape(@DelegatesTo(ShapeDsl) Closure cl) {
        def dsl = new ShapeDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _shapes << dsl.build()
    }

    // -------------------------------------------------------------------------
    // Transition
    // -------------------------------------------------------------------------

    /**
     * Attach a slide-transition effect.
     * <pre>transition { type 'FADE'; duration 400 }</pre>
     */
    void transition(@DelegatesTo(TransitionDsl) Closure cl) {
        def dsl = new TransitionDsl()
        cl.delegate = dsl
        cl.resolveStrategy = Closure.DELEGATE_FIRST
        cl()
        _transition = dsl.build()
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    SlidePlan build() {
        new SlidePlan(
            title     : _title,
            layout    : _layout,
            shapes    : new ArrayList<>(_shapes),
            transition: _transition,
            notes     : _notes
        )
    }

    // =========================================================================
    // TransitionDsl  —  inner builder; produces a TransitionSpec
    // =========================================================================

    /**
     * DSL for a slide transition, delegated from {@link SlideDsl#transition(Closure)}.
     */
    static class TransitionDsl {

        private TransitionType  _type       = TransitionType.NONE
        private int             _durationMs = 500

        /**
         * Set transition type by enum value.
         * <pre>type TransitionType.FADE</pre>
         */
        void type(TransitionType t) { _type = t }

        /**
         * Set transition type by name (case-insensitive).
         * <pre>type 'FADE'</pre>
         */
        void type(String t) { _type = TransitionType.valueOf(t.toUpperCase()) }

        /** Duration in milliseconds (default 500). */
        void duration(int ms) { _durationMs = ms }

        TransitionSpec build() {
            new TransitionSpec(type: _type, durationMs: _durationMs)
        }
    }
}
