package org.softwood.dag.task.xlsx.model

import groovy.transform.ToString

/**
 * Typed hyperlink specification for a cell.
 *
 * <p>Used by {@link org.softwood.dag.task.xlsx.CellSpec} and populated by
 * {@link org.softwood.dag.task.xlsx.CellDsl#link(String)} or
 * {@link org.softwood.dag.task.xlsx.CellDsl#link(Map)}.</p>
 *
 * <h3>DSL examples:</h3>
 * <pre>
 *   link 'https://reports.example.com'                       // URL (inferred)
 *   link 'mailto:finance@example.com'                        // EMAIL (inferred)
 *   link sheet:'Detail', cell:'A1'                           // INTERNAL
 *   link file:'/reports/q3.pdf', display:'Q3 Report'         // FILE
 *   link type:'URL', target:'https://...', display:'See more' // explicit
 * </pre>
 */
@ToString(includeNames = true, includeFields = true)
class LinkSpec {

    /**
     * Discriminator for the type of link.
     * <ul>
     *   <li>{@code URL}      — external web address</li>
     *   <li>{@code EMAIL}    — {@code mailto:} address</li>
     *   <li>{@code FILE}     — local or UNC file path</li>
     *   <li>{@code INTERNAL} — reference to a cell within the workbook</li>
     * </ul>
     */
    enum LinkType { URL, EMAIL, FILE, INTERNAL }

    /** Type of this link. Default is {@code URL}. */
    LinkType type    = LinkType.URL

    /**
     * The link destination.
     * <ul>
     *   <li>URL: full URL including scheme, e.g. {@code 'https://example.com'}</li>
     *   <li>EMAIL: {@code 'mailto:user@example.com'} or bare address</li>
     *   <li>FILE: absolute file path, e.g. {@code '/reports/q3.pdf'}</li>
     *   <li>INTERNAL: {@code 'SheetName!A1'} notation</li>
     * </ul>
     */
    String target

    /**
     * Optional display label or tooltip shown in the cell.
     * When {@code null} the adapter uses the raw {@link #target} value.
     */
    String display
}
