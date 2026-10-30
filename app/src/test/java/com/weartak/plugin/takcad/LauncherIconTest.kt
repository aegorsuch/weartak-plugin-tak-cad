// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], application = CadApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconTest {
    @Test fun packageLauncherResolvesOfficialAdaptiveIcon() {
        val context = RuntimeEnvironment.getApplication()
        val info = context.applicationInfo
        assertEquals(R.mipmap.ic_launcher, info.icon)
        val icon = context.getDrawable(info.icon) as AdaptiveIconDrawable
        assertEquals(Color.WHITE, (icon.background as ColorDrawable).color)
        assertTrue(icon.foreground is BitmapDrawable)
    }

    @Test fun foregroundRetainsTransparentPaddingAndBlackOfficialArtwork() {
        val context = RuntimeEnvironment.getApplication()
        val foreground = context.getDrawable(R.drawable.ic_takcad_foreground) as BitmapDrawable
        val bitmap = foreground.bitmap
        assertEquals(bitmap.width, bitmap.height)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(0, Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 10)))
        val artwork = bitmap.getPixel(bitmap.width / 2, bitmap.height / 4)
        assertTrue(Color.alpha(artwork) >= 240)
        assertEquals(0, Color.red(artwork) + Color.green(artwork) + Color.blue(artwork))
        val center = bitmap.width / 2.0
        val safeRadius = bitmap.width * 33.0 / 108
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 32) {
                assertTrue("Artwork outside adaptive safe circle at $x,$y",
                    (x - center) * (x - center) + (y - center) * (y - center) <= safeRadius * safeRadius)
            }
        }
    }
}
