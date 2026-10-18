package com.deskbuddy
import java.nio.ByteBuffer
import java.nio.ByteOrder
object Wav {
    fun wrap(pcm:ByteArray):ByteArray = ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(36+pcm.size);put("WAVEfmt ".toByteArray());putInt(16)
        putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16)
        put("data".toByteArray());putInt(pcm.size);put(pcm)
    }.array()
}
