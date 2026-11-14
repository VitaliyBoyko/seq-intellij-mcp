// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-License-Identifier: MIT
// License: see LICENSE in the repository root.

package com.vitaliiboiko.seqmcp

import com.intellij.BundleBase
import org.jetbrains.annotations.PropertyKey
import java.util.Locale
import java.util.ResourceBundle

/** Resolve Seq UI messages using the platform's message formatting rules. */
object SeqMcpBundle {
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = "messages.MyBundle") key: String, vararg params: Any): String {
        val messages = ResourceBundle.getBundle(
            "messages.MyBundle",
            Locale.getDefault(),
            SeqMcpBundle::class.java.classLoader,
        )
        return BundleBase.message(messages, key, *params)
    }
}
