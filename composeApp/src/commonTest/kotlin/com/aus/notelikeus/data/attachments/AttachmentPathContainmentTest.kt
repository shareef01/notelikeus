package com.aus.notelikeus.data.attachments

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPathContainmentTest {
    @Test
    fun acceptsADirectChildOfTheRoot() {
        assertTrue(
            isStrictChildPath(
                rootCanonical = "/data/user/0/com.aus.notelikeus/files/attachments",
                candidateCanonical = "/data/user/0/com.aus.notelikeus/files/attachments/a.jpg",
            ),
        )
    }

    @Test
    fun acceptsANestedChildOfTheRoot() {
        assertTrue(
            isStrictChildPath(
                rootCanonical = "C:\\Users\\me\\.notelikeus\\attachments",
                candidateCanonical = "C:\\Users\\me\\.notelikeus\\attachments\\nested\\a.jpg",
            ),
        )
    }

    @Test
    fun rejectsParentTraversal() {
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                candidateCanonical = "/data/app/files/secret.txt",
            ),
        )
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                // Canonical form of attachments/../secret.txt
                candidateCanonical = "/data/app/files/secret.txt",
            ),
        )
    }

    @Test
    fun rejectsAbsoluteExternalPaths() {
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                candidateCanonical = "/etc/passwd",
            ),
        )
        assertFalse(
            isStrictChildPath(
                rootCanonical = "C:\\Users\\me\\.notelikeus\\attachments",
                candidateCanonical = "C:\\Windows\\System32\\config\\SAM",
            ),
        )
    }

    @Test
    fun rejectsSimilarlyPrefixedSiblingDirectories() {
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                candidateCanonical = "/data/app/files/attachments-evil/a.jpg",
            ),
        )
        assertFalse(
            isStrictChildPath(
                rootCanonical = "C:\\Users\\me\\.notelikeus\\attachments",
                candidateCanonical = "C:\\Users\\me\\.notelikeus\\attachments-evil\\a.jpg",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun rejectsTheRootItself() {
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                candidateCanonical = "/data/app/files/attachments",
            ),
        )
    }

    @Test
    fun windowsComparisonIsCaseInsensitiveWhenRequested() {
        assertTrue(
            isStrictChildPath(
                rootCanonical = "C:\\Users\\Me\\.notelikeus\\attachments",
                candidateCanonical = "c:\\users\\me\\.notelikeus\\attachments\\photo.jpg",
                ignoreCase = true,
            ),
        )
        assertFalse(
            isStrictChildPath(
                rootCanonical = "C:\\Users\\Me\\.notelikeus\\attachments",
                candidateCanonical = "c:\\users\\me\\.notelikeus\\attachments\\photo.jpg",
                ignoreCase = false,
            ),
        )
    }

    @Test
    fun rejectsNullBytesInEitherPath() {
        assertFalse(
            isStrictChildPath(
                rootCanonical = "/data/app/files/attachments",
                candidateCanonical = "/data/app/files/attachments/a\u0000.jpg",
            ),
        )
    }
}
