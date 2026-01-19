package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class ReplaceOptions {
    static final ReplaceOptions DEFAULT = new ReplaceOptions()

    /** Must exist (fail if not found) */
    boolean mustExist = true

    /** Conditional replace (match version token) */
    String ifMatchVersionToken

    String contentType
    String contentEncoding
    String charset = 'UTF-8'
    Map<String, String> metadata = [:]
    Map<String, String> tags = [:]
    String storageClass
}