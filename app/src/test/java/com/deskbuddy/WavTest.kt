package com.deskbuddy
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
class WavTest {
    @Test fun pcmIsWrappedAsMono16kWave(){
        val pcm=byteArrayOf(1,2,3,4);val wav=Wav.wrap(pcm)
        assertEquals(48,wav.size);assertEquals("RIFF",String(wav,0,4));assertEquals("WAVE",String(wav,8,4))
        val b=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000,b.getInt(24));assertEquals(4,b.getInt(40));assertArrayEquals(pcm,wav.copyOfRange(44,48))
    }
    @Test fun externalConfigRequiresHttpsAndAModel(){
        assertNotNull(AsrConfig(true,"http://example.com","","asr").error)
        assertNotNull(AsrConfig(true,"https://example.com","","").error)
        assertNull(AsrConfig(true,"https://example.com/v1/audio/transcriptions","","asr").error)
    }
}
