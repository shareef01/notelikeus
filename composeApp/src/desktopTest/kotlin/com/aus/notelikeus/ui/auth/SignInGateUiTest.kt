package com.aus.notelikeus.ui.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Where the gate shows its one error: under the field it is about, in place of that field's hint. */
@OptIn(ExperimentalTestApi::class)
class SignInGateUiTest {

    private val codeError = "That code is wrong or has expired. Check it, or send a new one."

    @Test
    fun `an invalid email is reported under the email field instead of its hint`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SignInGate(codeSentTo = null, onRequestCode = {}, onSubmitCode = { _, _ -> }, onUseDifferentEmail = {})
            }
        }
        onNodeWithText("Email address").performTextInput("nope")
        onNodeWithText("Send code").performClick()

        onNodeWithText("Enter a valid email address.").assertExists()
        onNodeWithText("We’ll email you a 6-digit code. No password needed.").assertDoesNotExist()
    }

    @Test
    fun `a server error on the code step sits below the code field`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SignInGate(
                    codeSentTo = "sam@example.com",
                    onRequestCode = {},
                    onSubmitCode = { _, _ -> },
                    onUseDifferentEmail = {},
                    externalError = codeError,
                )
            }
        }
        waitForIdle()

        val field = onNodeWithText("6-digit code").getUnclippedBoundsInRoot()
        val error = onNodeWithText(codeError).getUnclippedBoundsInRoot()
        // It used to sit above the "Enter the code we emailed" line, two blocks away from the field.
        assertTrue(error.top >= field.top, "error at ${error.top} should be below the field at ${field.top}")
        onNodeWithText("Not there? Check your spam folder, or resend the code.").assertDoesNotExist()
    }
}
