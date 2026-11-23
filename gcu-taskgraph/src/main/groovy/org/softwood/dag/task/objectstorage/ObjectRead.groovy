package org.softwood.dag.task.objectstorage

/**
 * Streamed object read handle
 */
interface ObjectRead extends Closeable {
    ObjectInfo getInfo()
    InputStream getStream()
}