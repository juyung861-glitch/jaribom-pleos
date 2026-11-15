package com.jaribom.app.speech

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

/** 6차 F6-6: 안내 음성의 오디오 usage 는 내비 안내(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)다. 포커스 요청은 GAIN_TRANSIENT_MAY_DUCK 를 유지한다. */
class AudioUsageTest {

    @Test
    fun `안내 음성 usage 는 ASSISTANCE_NAVIGATION_GUIDANCE`() {
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioFocusHelper.USAGE)
    }
}
