package com.termux.app

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

internal fun File.writeTextAtomic(
    text: String,
    charset: Charset = Charsets.UTF_8
) {
    parentFile?.mkdirs()
    val tmpFile = File(parentFile, "$name.tmp")
    if (tmpFile.exists()) tmpFile.delete()

    FileOutputStream(tmpFile).use { output ->
        output.write(text.toByteArray(charset))
        output.fd.sync()
    }

    if (exists() && !delete()) {
        throw IllegalStateException("Failed to replace ${absolutePath}")
    }
    if (!tmpFile.renameTo(this)) {
        throw IllegalStateException("Failed to move temp file into place for ${absolutePath}")
    }
}
