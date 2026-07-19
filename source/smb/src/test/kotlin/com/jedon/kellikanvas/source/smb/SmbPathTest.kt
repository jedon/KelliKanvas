package com.jedon.kellikanvas.source.smb

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbPathTest {
    @Test
    fun normalize_acceptsRelativePhotoPaths() {
        assertThat(SmbPath.normalize("Digital Photos")).isEqualTo("Digital Photos")
        assertThat(SmbPath.normalize("Media\\Pictures")).isEqualTo("Media/Pictures")
        assertThat(SmbPath.normalize("")).isEqualTo("")
        assertThat(SmbPath.normalize(".")).isEqualTo("")
    }

    @Test
    fun normalize_rejectsTraversalAndAbsolute() {
        assertThrows(IllegalArgumentException::class.java) { SmbPath.normalize("/Photos") }
        assertThrows(IllegalArgumentException::class.java) { SmbPath.normalize("a/../b") }
        assertThrows(IllegalArgumentException::class.java) { SmbPath.normalize("a/./b") }
        assertThrows(IllegalArgumentException::class.java) { SmbPath.normalize("a\u0000b") }
    }

    @Test
    fun join_buildsChildPaths() {
        assertThat(SmbPath.join("", "Canvas")).isEqualTo("Canvas")
        assertThat(SmbPath.join("Media", "Pictures")).isEqualTo("Media/Pictures")
    }
}
