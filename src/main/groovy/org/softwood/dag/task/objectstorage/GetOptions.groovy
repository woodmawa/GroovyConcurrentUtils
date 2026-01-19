package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class GetOptions {
    static final GetOptions DEFAULT = new GetOptions()

    /** Byte range start (inclusive) */
    Long rangeStart

    /** Byte range end (inclusive) */
    Long rangeEnd

    /** Conditional get (match version token) */
    String ifMatchVersionToken

    /** Conditional get (not match version token) */
    String ifNoneMatchVersionToken

    /** Character set for text operations */
    String charset = 'UTF-8'
}
