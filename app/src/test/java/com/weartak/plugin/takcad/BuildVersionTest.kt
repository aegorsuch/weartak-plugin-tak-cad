// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.takcad

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildVersionTest {
    @Test fun versionMatchesWearTakPipelineScheme() {
        val iid = System.getenv("CI_PIPELINE_IID")?.toInt()
        val sha = System.getenv("CI_COMMIT_SHORT_SHA") ?: "local"
        assertEquals(if (iid == null) 584 else 5840000 + iid, BuildConfig.VERSION_CODE)
        assertEquals(if (iid == null) "5.8.4" else "5.8.4-p$iid+$sha", BuildConfig.VERSION_NAME)
    }
}
