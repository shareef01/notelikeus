package com.aus.notelikeus.ui.editor.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInputSelection
import com.aus.notelikeus.ui.editor.BlurSelectionKeeper
import com.aus.notelikeus.ui.editor.TextFormatting
import com.aus.notelikeus.ui.editor.formattingShortcuts
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Formatting from the keyboard. The toolbar's buttons were taken out of the focus order and the
 * body had no shortcuts, so a keyboard-only user on Windows could not format a note at all.
 */
@OptIn(ExperimentalTestApi::class)
class RichTextToolbarKeyboardTest {

    @Test
    fun `every formatting button is in the focus order`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RichTextToolbar(
                    onBoldClick = {},
                    onItalicClick = {},
                    onListClick = {},
                    onChecklistClick = {},
                    onLinkClick = {},
                    onAddImageClick = {},
                    contentColor = Color.Black,
                )
            }
        }
        val buttons = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals(6, buttons.size)
        onAllNodes(hasClickAction()).apply {
            for (index in buttons.indices) {
                get(index).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
            }
        }
    }

    @Test
    fun `Ctrl+B and Ctrl+I in the body format, other keys do not`() = runComposeUiTest {
        var bold = 0
        var italic = 0
        setContent {
            var text by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .testTag("body")
                    .focusRequester(focus)
                    .formattingShortcuts(onBold = { bold++ }, onItalic = { italic++ }),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        val body = onNodeWithTag("body")
        body.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        body.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.I) } }
        body.performKeyInput { pressKey(Key.B) }
        body.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.B) } } }
        assertEquals(1, bold)
        assertEquals(1, italic)
    }

    @Test
    fun `clicking a formatting button gives the caret back to the body`() = runComposeUiTest {
        var bold = 0
        setContent {
            MaterialTheme {
                val focus = remember { FocusRequester() }
                Column {
                    var text by remember { mutableStateOf("hello") }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.testTag("body").focusRequester(focus),
                    )
                    RichTextToolbar(
                        onBoldClick = { bold++ },
                        onItalicClick = {},
                        onListClick = {},
                        onChecklistClick = {},
                        onLinkClick = {},
                        contentColor = Color.Black,
                        returnFocusTo = focus,
                    )
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        onNodeWithTag("body").assertIsFocused()
        onNodeWithContentDescription("Bold").performMouseInput { click() }
        assertEquals(1, bold)
        onNodeWithTag("body").assertIsFocused()
    }

    /** The body and toolbar wired as EditorScreen wires them, selection keeper included. */
    private fun androidx.compose.ui.test.ComposeUiTest.editorWithSelection() {
        setContent {
            MaterialTheme {
                val focus = remember { FocusRequester() }
                val keeper = remember { BlurSelectionKeeper() }
                var value by remember { mutableStateOf(TextFieldValue("alpha beta")) }
                Column {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (keeper.accept(value, it)) value = it },
                        modifier = Modifier
                            .testTag("body")
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                keeper.onFocusChanged(state.isFocused, value)?.let { value = it }
                            },
                    )
                    RichTextToolbar(
                        onBoldClick = { value = TextFormatting.wrapSelection(value, "**") },
                        onItalicClick = {},
                        onListClick = {},
                        onChecklistClick = {},
                        onLinkClick = {},
                        contentColor = Color.Black,
                        returnFocusTo = focus,
                    )
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        onNodeWithTag("body").performTextInputSelection(TextRange(6, 10))
    }

    // Compose collapses a text field's selection as it loses focus. Measured on a Pixel 7 before
    // the keeper: selection, Shift+Tab to Bold, Enter inserted an empty `****` at the caret.
    @Test
    fun `clicking Bold wraps the selected words`() = runComposeUiTest {
        editorWithSelection()
        onNodeWithContentDescription("Bold").performMouseInput { click() }
        onNodeWithTag("body").assertTextEquals("alpha **beta**")
    }

    @Test
    fun `moving focus to Bold and pressing Enter wraps the selected words`() = runComposeUiTest {
        editorWithSelection()
        // Focus moves to the button as Shift+Tab moves it in the editor (verified on a Pixel 7);
        // what matters here is that the body loses focus before Bold runs.
        onNodeWithContentDescription("Bold").requestFocus()
        onNodeWithContentDescription("Bold").assertIsFocused()
        onNodeWithContentDescription("Bold").performKeyInput { pressKey(Key.Enter) }
        onNodeWithTag("body").assertTextEquals("alpha **beta**")
    }
}
