package org.softwood.dag.task.xlsx

import groovy.transform.ToString
import org.softwood.dag.task.xlsx.model.LinkSpec
import org.softwood.dag.task.xlsx.model.RichRun

// =============================================================================
// TOP-LEVEL PLAN  (the immutable intent that XlsxTask passes to the adapter)
// =============================================================================

/**
 * Immutable plan describing a complete workbook operation.
 * Built by {@link XlsxWorkbookDsl} and passed to the injected xlsxCallFn.
 */
@ToString(includeNames = true, includeFields = true)
class WorkbookPlan {
    /** Named reusable styles, keyed by style name. */
    Map<String, StyleSpec>  styles        = [:]
    /** Ordered list of sheet plans to apply. */
    List<SheetPlan>         sheets        = []
    /**
     * Workbook-level options:
     *   createIfMissing        (boolean, default true)
     *   preserveExistingStyles (boolean, default false)
     *   evaluateFormulas       (boolean, default false)
     *   sharedStrings          (boolean, default true)
     */
    Map<String, Object>     options       = [:]
    /** Optional template workbook to use as a base. */
    String                  templatePath
    /** Workbook-scope named ranges: name → e.g. "Summary!B2:B500". */
    Map<String, String>     namedRanges   = [:]
}

// =============================================================================
// SHEET PLAN
// =============================================================================

/**
 * Describes all operations to apply to a single worksheet.
 */
@ToString(includeNames = true, includeFields = true)
class SheetPlan {
    String              name
    /** Static rows specified directly in the DSL. */
    List<RowSpec>       rows              = []
    /** Deferred rows resolved from WorkingMemory at execution time. */
    List<DataRowsSpec>  deferredRows      = []
    List<ColumnSpec>    columns           = []
    /** Arbitrary range operations (style, borders, fill, validation). */
    List<RangeSpec>     ranges            = []
    List<MergeSpec>     merges            = []
    List<GroupSpec>     rowGroups         = []
    List<GroupSpec>     columnGroups      = []
    /** Top-left cell of the unfrozen pane, e.g. "B2". */
    String              freezeRef
    /** Range to apply an auto-filter, e.g. "A1:G1". */
    String              autoFilterRef
    /** Tab accent colour in "#RRGGBB" hex. */
    String              tabColor
    /** Sheet password (null = unprotected). */
    String              password
    boolean             locked            = false
    /** Page-setup options: paper, orientation, fitWidth, fitHeight. */
    Map<String, Object> pageSettings      = [:]
    float               defaultRowHeight  = 0f
    int                 defaultColumnWidth = 0
    /** Default style name applied to cells with no explicit style. */
    String              defaultStyle
    /** Named ranges defined on this sheet: name -> "A1:C10" reference. */
    Map<String, String> namedRanges       = [:]
}

// =============================================================================
// ROW / CELL / COLUMN
// =============================================================================

/**
 * A single row to write.  rowIndex is 1-based; null means "next available".
 */
@ToString(includeNames = true, includeFields = true)
class RowSpec {
    Integer         rowIndex
    List<CellSpec>  cells    = []
    String          styleRef
    float           height   = 0f
    boolean         hidden   = false
}

/**
 * Placeholder that tells the adapter to inject rows from WorkingMemory.
 * Resolved at execution time, not at DSL-build time.
 */
@ToString(includeNames = true, includeFields = true)
class DataRowsSpec {
    /** 1-based row index to start writing (default 1). */
    Integer startAt  = 1
    /** WorkingMemory key that holds List<List<Object>> or List<Map>. */
    String  dataKey
    /** Optional style applied to every injected row. */
    String  styleRef
}

/**
 * A single cell within a row.
 * Either ref ("B3") or colIndex (1-based) must be set; otherwise auto-sequenced.
 */
@ToString(includeNames = true, includeFields = true)
class CellSpec {
    /** A1-notation column+row reference, e.g. "C4". */
    String          ref
    /** 1-based column index (alternative to ref). */
    Integer         colIndex
    Object          value
    String          formula
    String          styleRef
    CommentSpec     comment
    /** Typed hyperlink or internal link. */
    LinkSpec        link
    /** Rich-text runs for mixed-format content; empty = use plain {@link #value}. */
    List<RichRun>   richText  = []
    /** Merge: cell spans this many rows downward (1 = no merge). */
    int             rowspan   = 1
    /** Merge: cell spans this many columns rightward (1 = no merge). */
    int             colspan   = 1
    /** Named range assigned to this cell, for use in formulas. */
    String          cellName
    /** Image path or URL; when set, value/formula are ignored. */
    String          imagePath
    /** Image type: PNG | JPEG | EMF | WMF (default PNG). */
    String          imageType = 'PNG'
}

/**
 * Column width / style specification.
 * ref is a single column letter ("A") or a range ("B:D").
 */
@ToString(includeNames = true, includeFields = true)
class ColumnSpec {
    String  ref
    float   width        = 0f
    boolean hidden       = false
    boolean autoSize     = false
    String  styleRef
    /** Outline grouping level (0 = none). */
    int     outlineLevel = 0
}

// =============================================================================
// RANGE / MERGE / GROUP
// =============================================================================

/**
 * An operation applied to a rectangular range of cells.
 */
@ToString(includeNames = true, includeFields = true)
class RangeSpec {
    /** A1-notation range e.g. "A1:F20" or single cell "B3". */
    String                  ref
    String                  styleRef
    boolean                 zebraStripe  = false
    /** Border style for perimeter: "thin" | "medium" | "thick" | "dashed". */
    String                  borders
    String                  comment
    /** Values to write into the range (row-major List<List<Object>>). */
    List                    values
    DataValidationSpec      dataValidation
    ConditionalFormatSpec   conditionalFormat
    /** Per-cell formula applied to every cell in the range. */
    String                  formula
    /** Column formula template; {@code {r}} is substituted with the 1-based row number. */
    String                  columnFormula
    /** Inline style definition; overrides {@link #styleRef} when set. */
    StyleSpec               inlineStyle
}

/** Merge a rectangular block of cells into one. */
@ToString(includeNames = true, includeFields = true)
class MergeSpec {
    /** A1-notation range to merge, e.g. "A1:C3". */
    String ref
}

/** Row or column grouping (outline). */
@ToString(includeNames = true, includeFields = true)
class GroupSpec {
    /**
     * For row groups: IntRange (1-based inclusive).
     * For column groups: String range e.g. "C:F".
     */
    def     range
    boolean collapsed = false
}

/** Cell comment metadata. */
@ToString(includeNames = true, includeFields = true)
class CommentSpec {
    String text
    String author
}

// =============================================================================
// VALIDATION / CONDITIONAL FORMAT
// =============================================================================

/**
 * Data-validation constraint for a cell or range.
 */
@ToString(includeNames = true, includeFields = true)
class DataValidationSpec {
    /** "list" | "number" | "date" | "textLength" | "decimal" | "formula" */
    String          type              = 'list'
    /** Dropdown items when type="list". */
    List<String>    list              = []
    /** Low-bound formula for type != list. */
    String          formula1
    /** High-bound formula when operator requires two operands. */
    String          formula2
    /** "between"|"notBetween"|"equal"|"notEqual"|"greaterThan"|"lessThan" etc. */
    String          operator
    boolean         showErrorMessage  = true
    String          errorTitle
    String          errorMessage
    /** "stop" | "warning" | "information" */
    String          errorStyle        = 'stop'
    boolean         showInputMessage  = false
    String          inputTitle
    String          inputMessage
}

/**
 * Conditional formatting rule for a range.
 */
@ToString(includeNames = true, includeFields = true)
class ConditionalFormatSpec {
    /** "cellValue" | "formula" | "colorScale" | "dataBar" | "iconSet" */
    String  type     = 'cellValue'
    String  operator
    Object  value1
    Object  value2
    /** Named StyleSpec to apply when rule fires. */
    String  styleRef
    /** Groovy formula string when type="formula". */
    String  formula
}

// =============================================================================
// STYLE MODEL
// =============================================================================

/**
 * Complete style specification for a cell or range.
 */
@ToString(includeNames = true, includeFields = true)
class StyleSpec {
    String          name
    FontSpec        font      = new FontSpec()
    FillSpec        fill      = new FillSpec()
    BorderSpec      border    = new BorderSpec()
    AlignmentSpec   alignment = new AlignmentSpec()
    /** Excel data-format string e.g. "#,##0.00" or "dd/MM/yyyy". */
    String          dataFormat
    boolean         locked    = false
    boolean         hidden    = false
    /** Name of parent style to inherit from (adapter applies parent first, then overrides). */
    String          extendsStyle
}

/** Font properties. */
@ToString(includeNames = true, includeFields = true)
class FontSpec {
    boolean bold          = false
    boolean italic        = false
    boolean underline     = false
    boolean strikethrough = false
    /** Point size; 0 = inherit workbook default. */
    int     size          = 0
    /** Foreground colour "#RRGGBB". */
    String  color
    /** Font family e.g. "Calibri". */
    String  name
}

/** Cell background fill. */
@ToString(includeNames = true, includeFields = true)
class FillSpec {
    /** Foreground colour "#RRGGBB". */
    String foreground
    /** Background colour "#RRGGBB" (relevant for patterned fills). */
    String background
    /** "solid" | "fineDots" | "altBars" | "none" */
    String pattern = 'solid'
}

/** Cell border specification. */
@ToString(includeNames = true, includeFields = true)
class BorderSpec {
    /** Shortcut — apply same style to all four sides. */
    String all
    String top
    String bottom
    String left
    String right
    /** Border colour "#RRGGBB". */
    String color
}

/** Text alignment and rotation. */
@ToString(includeNames = true, includeFields = true)
class AlignmentSpec {
    /** "left" | "center" | "right" | "fill" | "justify" | "centerContinuous" */
    String  horizontal
    /** "top" | "middle" | "bottom" | "distributed" */
    String  vertical
    boolean wrapText = false
    int     indent   = 0
    /** Degrees, -90 to 90. */
    int     rotation = 0
}
