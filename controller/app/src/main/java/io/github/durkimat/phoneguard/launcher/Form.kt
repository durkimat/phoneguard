package io.github.durkimat.phoneguard.launcher

/**
 * One line of a [Form]. [onText] set: OK opens the text entry, filled with [initial], and hands back what was typed. [onStep] set: Left and Right
 * step the value down and up one notch, `*` and `#` a big step (OK also steps up when [okStep] is true, for a yes/no). [run] set: OK does it.
 */
class FormRow(
    val label: String,
    val value: () -> String = { "" },
    val onText: ((String) -> Unit)? = null,
    val initial: () -> String = { "" },
    val exact: Boolean = false,
    val onStep: ((dir: Int, big: Boolean) -> Unit)? = null,
    val okStep: Boolean = false,
    val run: (() -> Action)? = null,
) {
    val text: String get() = value().let { if (it.isEmpty()) label else "$label: $it" }
}

/** A list of editable lines with one main action (the left soft key). The rows are built on demand, so a row can appear or vanish with another's value. */
class Form(val title: String, val primary: String, val rowsOf: () -> List<FormRow>, val onPrimary: () -> Action)
