package com.mettyoung.deconstructchinese.config

/** Build-injected API keys, keyed by provider id ("qwen", "gemini", ...). Missing/blank unless provided at build time. */
expect val defaultApiKeys: Map<String, String>
