package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class CopyOptions {
    static final CopyOptions DEFAULT = new CopyOptions()

    /** Replace metadata (vs copy from source) */
    boolean replaceMetadata = false

    Map<String, String> metadata = [:]
    Map<String, String> tags = [:]
    String storageClass
}