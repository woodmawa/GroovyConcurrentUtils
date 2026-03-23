package org.softwood.dag.task.docx

import groovy.transform.ToString
import org.softwood.dag.task.xlsx.StyleSpec
import org.softwood.dag.task.xlsx.model.RichRun

// =============================================================================
// TOP-LEVEL PLAN  (immutable intent passed to the injected docxCallFn)
// =============================================================================

/**
 * Immutable plan describing a complete Word document operation.
 * Built by {@link DocumentDsl} and passed to the injected {@code docxCallFn}.
 *
 * <h3>Adapter contract:</h3>
 * <pre>
 *   docxCallFn.call('SCRIPT', [filePath: '...', documentPlan: plan])
 * </pre>
 */
@ToString(includeNames = true, includeFields = true)
class DocumentPlan {
    /** Document title inserted into core properties and, optionally, a title page. */
    String                    title
    /** Named paragraph/character styles, keyed by style name. */
    Map<String, StyleSpec>    styles      = [:]
    /** Ordered list of content sections. */
    List<SectionSpec>         sections    = []
    /**
     * Standard document properties.
     * Recognised keys: {@code author}, {@code subject}, {@code description},
     * {@code category}, {@code keywords}, {@code company}.
     */
    Map<String, String>       properties  = [:]
    /** Page layout specification. */
    PageSpec                  page        = new PageSpec()
}

// =============================================================================
// SECTION
// =============================================================================

/**
 * A single content section within a document.
 * Sections are appended in order.  A section may contain a heading, text,
 * rich text, a table, or list items — only the non-null/non-empty fields
 * are rendered by the adapter.
 */
@ToString(includeNames = true, includeFields = true)
class SectionSpec {
    /** Optional heading text. {@code null} = no heading rendered. */
    String           heading
    /** Heading level 1–6 (H1 by default). Ignored when {@link #heading} is null. */
    int              headingLevel   = 1
    /** Plain-text paragraph content. */
    String           text
    /**
     * Rich-text runs for mixed-format paragraphs.
     * When non-empty, takes precedence over {@link #text}.
     */
    List<RichRun>    richText       = []
    /** Optional table embedded in this section. */
    TableSpec        table
    /** Bullet list items (unordered). */
    List<String>     bulletList     = []
    /** Numbered list items (ordered). */
    List<String>     numberedList   = []
    /**
     * Inline paragraph style override applied to all content in this section.
     * {@code null} = use the document default.
     */
    StyleSpec        style
}

// =============================================================================
// TABLE
// =============================================================================

/**
 * A table to embed inside a {@link SectionSpec}.
 */
@ToString(includeNames = true, includeFields = true)
class TableSpec {
    /** Column header labels. */
    List<String>       headers      = []
    /** Data rows, each a list of cell string values. */
    List<List<String>> rows         = []
    /** When {@code true} the first row is styled as a header row (default). */
    boolean            headerRow    = true
    /** Style applied to the header row; {@code null} = default. */
    StyleSpec          headerStyle
    /** Style applied to body rows; {@code null} = default. */
    StyleSpec          bodyStyle
    /**
     * Per-column widths in twips (1/1440 inch).
     * Empty = let the adapter auto-size.
     */
    List<Integer>      columnWidths = []
}

// =============================================================================
// PAGE SETUP
// =============================================================================

/**
 * Page layout for the document.
 */
@ToString(includeNames = true, includeFields = true)
class PageSpec {
    /**
     * Paper size: {@code 'A4'} | {@code 'LETTER'} | {@code 'A3'} | {@code 'LEGAL'}.
     * Default {@code 'A4'}.
     */
    String size         = 'A4'
    /**
     * Page orientation: {@code 'portrait'} | {@code 'landscape'}.
     * Default {@code 'portrait'}.
     */
    String orientation  = 'portrait'
    /** Top margin in twips (default 1440 = 1 inch). */
    int    marginTop    = 1440
    /** Bottom margin in twips (default 1440 = 1 inch). */
    int    marginBottom = 1440
    /** Left margin in twips (default 1800 = 1.25 inch). */
    int    marginLeft   = 1800
    /** Right margin in twips (default 1800 = 1.25 inch). */
    int    marginRight  = 1800
}
