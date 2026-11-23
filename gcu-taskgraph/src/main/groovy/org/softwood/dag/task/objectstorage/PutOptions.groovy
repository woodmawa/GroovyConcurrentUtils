package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class PutOptions {
    static final PutOptions DEFAULT = new PutOptions()

    /** Create only (fail if exists) */
    boolean ifNoneMatch = false

    String contentType
    String contentEncoding
    String charset = 'UTF-8'
    Map<String, String> metadata = [:]
    Map<String, String> tags = [:]
    String storageClass
    String serverSideEncryption
}