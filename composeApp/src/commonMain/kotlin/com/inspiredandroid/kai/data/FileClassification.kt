package com.inspiredandroid.kai.data

enum class FileCategory {
    IMAGE,
    TEXT,
    PDF,
    DOCUMENT,
    ARCHIVE,
    UNSUPPORTED,
}

const val MAX_TEXT_FILE_BYTES = 200_000
const val MAX_PDF_BYTES = 20_000_000
const val MAX_IMAGE_BYTES = 15_000_000

// Raw image input cap before compression — images typically shrink after compression,
// so we allow larger raw files than MAX_IMAGE_BYTES while still preventing an OOM
// from reading a multi-gigabyte file into memory.
const val MAX_RAW_IMAGE_BYTES = 50_000_000

private val textMimeTypes = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/sql",
    "application/graphql",
    "application/toml",
)

private val textExtensions = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml",
    "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rb", "rs", "go", "c", "h", "cpp", "hpp",
    "swift", "sh", "bash", "zsh", "sql", "graphql",
    "toml", "ini", "cfg", "conf", "log", "properties",
    "gradle", "tsx", "jsx", "gsc",
)

internal val imageExtensions = setOf(
    "jpg",
    "jpeg",
    "png",
    "gif",
    "webp",
    "bmp",
    "svg",
)

private val documentExtensions = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
)

private val documentMimeTypes = setOf(
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

private val archiveExtensions = setOf("zip", "tar", "gz", "tgz", "rar", "7z")

private val archiveMimeTypes = setOf(
    "application/zip",
    "application/x-tar",
    "application/gzip",
    "application/x-gzip",
    "application/x-7z-compressed",
)

val supportedFileExtensions = (imageExtensions + textExtensions + documentExtensions + archiveExtensions).toList()

fun classifyFile(mimeType: String?, fileName: String?): FileCategory {
    if (mimeType != null) {
        if (mimeType.startsWith("image/")) return FileCategory.IMAGE
        if (mimeType == "application/pdf" || mimeType in documentMimeTypes) return FileCategory.DOCUMENT
        if (mimeType.startsWith("text/") || mimeType in textMimeTypes) return FileCategory.TEXT
        if (mimeType in archiveMimeTypes) return FileCategory.ARCHIVE
    }
    // Fall back to extension
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    if (ext != null && ext in imageExtensions) return FileCategory.IMAGE
    if (ext != null && ext in textExtensions) return FileCategory.TEXT
    if (ext != null && ext in documentExtensions) return FileCategory.DOCUMENT
    if (ext != null && ext in archiveExtensions) return FileCategory.ARCHIVE

    // If mimeType is null and no recognized extension, unsupported
    if (mimeType == null) return FileCategory.UNSUPPORTED

    return FileCategory.UNSUPPORTED
}
