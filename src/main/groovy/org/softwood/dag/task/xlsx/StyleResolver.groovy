package org.softwood.dag.task.xlsx

/**
 * Resolves a {@link StyleSpec} inheritance chain at write time.
 *
 * <p>Walks the {@link StyleSpec#extendsStyle} chain, collecting ancestors in
 * order (root first, leaf last), then merges them so that child values override
 * parent values.  Null / false / 0 on a child spec means "keep parent's value".</p>
 *
 * <p>Cycle detection throws {@link IllegalStateException} when the chain exceeds
 * {@link #MAX_CHAIN_DEPTH} entries.</p>
 */
class StyleResolver {

    static final int MAX_CHAIN_DEPTH = 10

    /**
     * Resolve {@code spec} against {@code registry}, returning a new
     * {@link StyleSpec} with all inherited values filled in.
     * If {@code spec.extendsStyle} is null, {@code spec} is returned unchanged.
     */
    static StyleSpec resolve(StyleSpec spec, Map<String, StyleSpec> registry) {
        if (!spec?.extendsStyle) return spec

        // Collect ancestor chain (oldest ancestor first, spec last)
        List<StyleSpec> chain = []
        Set<String>     seen  = []
        String          cursor = spec.extendsStyle
        while (cursor) {
            if (!seen.add(cursor))
                throw new IllegalStateException("Circular StyleSpec extends chain detected at '${cursor}'")
            if (chain.size() >= MAX_CHAIN_DEPTH)
                throw new IllegalStateException("StyleSpec extends chain exceeds max depth ${MAX_CHAIN_DEPTH}")
            StyleSpec parent = registry[cursor]
            if (!parent) break
            chain.add(0, parent)          // prepend so root is first
            cursor = parent.extendsStyle
        }
        chain << spec                     // child goes last

        return mergeChain(chain)
    }

    // -------------------------------------------------------------------------
    // Merge helpers
    // -------------------------------------------------------------------------

    private static StyleSpec mergeChain(List<StyleSpec> chain) {
        StyleSpec result = new StyleSpec(
            font      : new FontSpec(),
            fill      : new FillSpec(),
            border    : new BorderSpec(),
            alignment : new AlignmentSpec()
        )
        chain.each { StyleSpec s -> mergeInto(result, s) }
        return result
    }

    private static void mergeInto(StyleSpec target, StyleSpec source) {
        if (!source) return

        if (source.name)        target.name        = source.name
        if (source.dataFormat)  target.dataFormat  = source.dataFormat
        if (source.locked)      target.locked      = true
        if (source.hidden)      target.hidden      = true

        // FontSpec
        FontSpec sf = source.font
        if (sf) {
            if (sf.bold)          target.font.bold          = true
            if (sf.italic)        target.font.italic        = true
            if (sf.underline)     target.font.underline     = true
            if (sf.strikethrough) target.font.strikethrough = true
            if (sf.size > 0)      target.font.size          = sf.size
            if (sf.color)         target.font.color         = sf.color
            if (sf.name)          target.font.name          = sf.name
        }

        // FillSpec
        FillSpec ff = source.fill
        if (ff) {
            if (ff.foreground) target.fill.foreground = ff.foreground
            if (ff.background) target.fill.background = ff.background
            if (ff.pattern)    target.fill.pattern    = ff.pattern
        }

        // BorderSpec
        BorderSpec bf = source.border
        if (bf) {
            if (bf.all)    target.border.all    = bf.all
            if (bf.top)    target.border.top    = bf.top
            if (bf.bottom) target.border.bottom = bf.bottom
            if (bf.left)   target.border.left   = bf.left
            if (bf.right)  target.border.right  = bf.right
            if (bf.color)  target.border.color  = bf.color
        }

        // AlignmentSpec
        AlignmentSpec af = source.alignment
        if (af) {
            if (af.horizontal) target.alignment.horizontal = af.horizontal
            if (af.vertical)   target.alignment.vertical   = af.vertical
            if (af.wrapText)   target.alignment.wrapText   = true
            if (af.indent > 0) target.alignment.indent     = af.indent
            if (af.rotation)   target.alignment.rotation   = af.rotation
        }
    }
}
