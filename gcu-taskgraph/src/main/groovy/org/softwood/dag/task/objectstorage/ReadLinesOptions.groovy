package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class ReadLinesOptions {
    static final ReadLinesOptions DEFAULT = new ReadLinesOptions()

    String charset = 'UTF-8'
    String newline = 'AUTO'  // AUTO, LF, CRLF, CR
    int maxLineLength = 1024 * 1024  // 1MB
    Long startAtByte
    boolean trimNewline = true
}
