package com.mettyoung.deconstructchinese.config

// Build-time injection is Android-only for now; web users enter the key in-app (persisted).
actual val defaultApiKeys: Map<String, String> = emptyMap()
