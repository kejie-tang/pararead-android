package org.pararead.android

/** Brief feedback only; durable job status remains available in the library. */
internal fun readerNoticeDuration(message: String): Long? =
    if (message.startsWith("全篇已取消")) 2500L else null
