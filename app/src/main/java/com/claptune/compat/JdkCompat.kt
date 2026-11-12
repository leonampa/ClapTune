@file:JvmName("JdkCompat")

package com.claptune.compat

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

// Targets of the build-time rewrite in buildSrc/JdkCompatTransform.kt; the names must match the JDK methods.

fun encode(s: String, charset: Charset): String = URLEncoder.encode(s, charset.name())

fun decode(s: String, charset: Charset): String = URLDecoder.decode(s, charset.name())
