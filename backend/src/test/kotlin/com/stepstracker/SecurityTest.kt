package com.stepstracker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {
    private val security = Security(AppConfig(jwtSecret = "a-secure-test-secret-with-32-characters"))

    @Test fun `argon password hashes verify`() {
        val hash = security.hashPassword("correct-horse-battery")
        assertTrue(security.verifyPassword(hash, "correct-horse-battery"))
        assertFalse(security.verifyPassword(hash, "wrong-password"))
    }
}

