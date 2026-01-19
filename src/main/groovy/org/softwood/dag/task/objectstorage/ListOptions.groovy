package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class ListOptions {
    static final ListOptions DEFAULT = new ListOptions()

    /** Prefix filter */
    String prefix = ""

    /** Delimiter for folder emulation (usually "/") */
    String delimiter

    /** Page size */
    int pageSize = 1000

    /** Continuation token for paging */
    String pageToken

    /** Include metadata (may require extra API calls) */
    boolean includeMetadata = false

    /** Recursive listing (ignore delimiter) */
    boolean recursive = false
}
