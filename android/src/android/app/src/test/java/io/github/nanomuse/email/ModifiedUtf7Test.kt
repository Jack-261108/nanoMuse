package io.github.nanomuse.email

import org.junit.Assert.assertEquals
import org.junit.Test

class ModifiedUtf7Test {
    @Test
    fun `ascii folders pass through`() {
        assertEquals("INBOX", ModifiedUtf7.encode("INBOX"))
        assertEquals("INBOX", ModifiedUtf7.decode("INBOX"))
    }

    @Test
    fun `ampersand is escaped`() {
        assertEquals("A&-B", ModifiedUtf7.encode("A&B"))
        assertEquals("A&B", ModifiedUtf7.decode("A&-B"))
    }

    @Test
    fun `Chinese folder names round-trip`() {
        for (name in listOf("草稿箱", "已发送", "其他文件夹", "收件箱")) {
            assertEquals(name, ModifiedUtf7.decode(ModifiedUtf7.encode(name)))
        }
    }

    @Test
    fun `non-ascii is wrapped in ampersand and dash`() {
        val encoded = ModifiedUtf7.encode("草稿箱")
        assertEquals(true, encoded.startsWith("&"))
        assertEquals(true, encoded.endsWith("-"))
        assertEquals(false, encoded.contains("草稿箱"))
    }
}
