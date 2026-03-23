package org.softwood.dag.task.xlsx

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Resolves formula placeholders at write time.
 *
 * <h3>Two substitution modes:</h3>
 * <ol>
 *   <li><b>{@code #{Name}}</b> — named-range references.  Looks up {@code Name} in
 *       the workbook/sheet {@code namedRanges} map and substitutes the A1-style ref.
 *       Unqualified refs (no {@code !}) are prefixed with {@code currentSheet!}.</li>
 *   <li><b>{@code {r}}</b> — row-number placeholder in {@link RangeSpec#columnFormula}.
 *       Call {@link #resolveColumnFormula} with the 1-based row index to expand it.</li>
 * </ol>
 */
class FormulaResolver {

    private static final Pattern NAMED_REF = Pattern.compile('#\\{(\\w+)\\}')

    /**
     * Replace all {@code #{Name}} tokens in {@code formula} with their A1-style
     * references from {@code namedRanges}.  Unknown names are left as-is.
     *
     * @param formula      raw formula string (may be null)
     * @param namedRanges  workbook + sheet named range map
     * @param currentSheet sheet name used to qualify bare refs
     * @return resolved formula string
     */
    static String resolve(String formula, Map<String, String> namedRanges, String currentSheet) {
        if (!formula) return formula
        Matcher matcher = NAMED_REF.matcher(formula)
        StringBuffer sb = new StringBuffer()
        while (matcher.find()) {
            String name = matcher.group(1)
            String ref  = namedRanges?.get(name)
            if (ref) {
                // Qualify with currentSheet if the ref has no sheet prefix
                if (!ref.contains('!') && currentSheet) ref = "'${currentSheet}'!${ref}"
                matcher.appendReplacement(sb, Matcher.quoteReplacement(ref))
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)))
            }
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    /**
     * Substitute the {@code {r}} row-number placeholder in a column formula
     * template with the given 1-based row index.
     *
     * @param template  column formula template, e.g. {@code "SUM(A{r}:C{r})"}
     * @param rowIndex  1-based row number
     * @return formula with {@code {r}} replaced
     */
    static String resolveColumnFormula(String template, int rowIndex) {
        if (!template) return template
        return template.replace('{r}', rowIndex.toString())
    }
}
