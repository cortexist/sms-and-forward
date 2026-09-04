/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import dev.octoshrimpy.quik.R
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FontProvider @Inject constructor(context: Context) {

    // JetBrains Mono, bundled in res/font -- the family the Omarchy shell is drawn in.
    // Bundled fonts load synchronously, so the callback shape kept for the callers
    // resolves at once; a load failure falls back to the system monospace face.
    private val mono: Typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
        ?: Typeface.MONOSPACE.also { Timber.w("Bundled font failed to load; using system monospace") }

    fun getLato(callback: (Typeface) -> Unit) = callback(mono)

}