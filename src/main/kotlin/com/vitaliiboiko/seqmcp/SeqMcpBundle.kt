// SPDX-FileCopyrightText: Private Entrepreneur Vitalii Boiko
// SPDX-FileCopyrightText: 2000-2021 JetBrains s.r.o.
// SPDX-License-Identifier: MIT AND Apache-2.0
// License: see LICENSE and LICENSES/Apache-2.0.txt in the repository root.

package com.vitaliiboiko.seqmcp

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.MyBundle"

object SeqMcpBundle {

    private val bundle = DynamicBundle(SeqMcpBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        bundle.getMessage(key, *params)
}
