package com.daymark.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * Keyboard options for free-text fields: auto-capitalize the first letter of each sentence, the way
 * most apps' text fields behave. Numeric fields (PIN, tracker values) set their own options and
 * should not use this.
 */
val SentenceCaps = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
