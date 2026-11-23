package org.softwood.dag.task.pptx

import groovy.transform.ToString
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.model.RichRun
import org.softwood.dag.task.docx.TableSpec

// =============================================================================
// ENUMS  (top-level so they are accessible without inner-class syntax)
// =============================================================================

/**
 * Type discriminator for a {@link ShapeSpec}.
 */
enum ShapeType {
    TEXT_BOX,   // plain or rich-text box
    IMAGE,      // embedded image
    TABLE,      // tabular data (uses {@link TableSpec})
    CHART,      // chart reference
    RECTANGLE,  // filled/stroked rectangle
    OVAL,       // filled/stroked oval
    LINE        // straight line
}

/**
 * Slide-transition effect type.
 */
enum TransitionType {
    NONE,   // no transition (default)
    FADE,
    PUSH,
    WIPE,
    SPLIT,
    ZOOM
}

// =============================================================================
// TOP-LEVEL PLAN
// =============================================================================

/**
 * Immutable plan describing a complete PowerPoint presentation.
 * Built by {@link PresentationDsl} and passed to the injected {@code pptxCallFn}.
 *
 * <h3>Adapter contract:</h3>
 * <pre>
 *   pptxCallFn.call('SCRIPT', [filePath: '...', presentationPlan: plan])
 * </pre>
 */
@ToString(includeNames = true, includeFields = true)
class PresentationPlan {
    /** Presentation title inserted into core properties. */
    String                      title
    /** Default theme / layout applied to all slides unless overridden per slide. */
    SlideLayoutSpec             defaultLayout = new SlideLayoutSpec()
    /** Ordered list of slides. */
    List<SlidePlan>             slides        = []
    /** Named text/paragraph styles shared across all slides. */
    Map<String, StyleSpec>      styles        = [:]
    /**
     * Core document properties.
     * Recognised keys: {@code author}, {@code subject}, {@code description},
     * {@code category}, {@code keywords}, {@code company}.
     */
    Map<String, String>         properties    = [:]
}

// =============================================================================
// SLIDE
// =============================================================================

/**
 * A single slide within the presentation.
 */
@ToString(includeNames = true, includeFields = true)
class SlidePlan {
    /** Slide title text; {@code null} = no title shape rendered. */
    String              title
    /** Layout name override for this slide; {@code null} = use presentation default. */
    String              layout
    /** Content shapes in z-order (bottom to top). */
    List<ShapeSpec>     shapes     = []
    /** Transition from the previous slide; {@code null} = no transition. */
    TransitionSpec      transition
    /** Speaker notes text. */
    String              notes
}

// =============================================================================
// SHAPE
// =============================================================================

/**
 * A single content shape on a slide.
 * Position and size are in EMUs (English Metric Units); 914400 EMU = 1 inch.
 */
@ToString(includeNames = true, includeFields = true)
class ShapeSpec {
    /** Discriminator. Default {@code TEXT_BOX}. */
    ShapeType       type       = ShapeType.TEXT_BOX
    /** Plain-text content for {@code TEXT_BOX} shapes. */
    String          text
    /**
     * Rich-text runs for mixed-format content.
     * When non-empty takes precedence over {@link #text}.
     */
    List<RichRun>   richText   = []
    /** Table data for {@code TABLE} shapes. */
    TableSpec       table
    /** File path or URL for {@code IMAGE} shapes. */
    String          imagePath
    /** Style overrides (font, fill, border). */
    StyleSpec       style
    /** X position in EMUs from the left edge of the slide. */
    long            x          = 0L
    /** Y position in EMUs from the top edge of the slide. */
    long            y          = 0L
    /** Shape width in EMUs. */
    long            width      = 0L
    /** Shape height in EMUs. */
    long            height     = 0L
}

// =============================================================================
// LAYOUT
// =============================================================================

/**
 * Default theme and layout settings applied to all slides in the presentation.
 * Individual slides may override {@link SlidePlan#layout}.
 */
@ToString(includeNames = true, includeFields = true)
class SlideLayoutSpec {
    /** Theme name as known by the POI adapter, e.g. {@code "Office Theme"}. */
    String  theme
    /** Slide background fill colour in {@code '#RRGGBB'} hex; {@code null} = theme default. */
    String  background
    /** Default font family, e.g. {@code "Calibri"}. */
    String  fontFamily
    /** Title text point size; {@code 0} = theme default. */
    int     titleFontSize = 0
    /** Body text point size; {@code 0} = theme default. */
    int     bodyFontSize  = 0
    /** Accent / highlight colour in {@code '#RRGGBB'} hex. */
    String  accentColor
}

// =============================================================================
// TRANSITION
// =============================================================================

/**
 * Slide-transition specification.
 */
@ToString(includeNames = true, includeFields = true)
class TransitionSpec {
    /** Effect type. Default {@code NONE}. */
    TransitionType  type       = TransitionType.NONE
    /** Effect duration in milliseconds. Default 500 ms. */
    int             durationMs = 500
}
