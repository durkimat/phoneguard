package io.github.durkimat.familychat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The one group picker, used by setup step 6 and the admin screen: a search box, the chosen groups as removable chips, and "All your groups"
 * A to Z in a card that scrolls by itself, a checkbox per group. Groups are kept by id. [onChange] gets the chosen groups after every change.
 */
class GroupPickerView(c: Context, private val model: PickerModel, private val onChange: (List<Group>) -> Unit) : LinearLayout(c) {
    private val search = Ui.input(c, "Search your groups", InputType.TYPE_CLASS_TEXT).apply { contentDescription = "Search your groups" }
    private val chosenLabel = Ui.text(c, "", 17f, Ui.INK, true)
    private val chips = FlowLayout(c)
    private val none = Ui.text(c, "None yet", 17f, Ui.MUTED)
    private val allLabel = Ui.text(c, "", 17f, Ui.INK, true)
    private val list = Ui.col(c)
    private val listScroll = ScrollView(c).apply { addView(list) }
    private val listCard = Ui.card(c).apply { setPadding(Ui.dp(c, 8), Ui.dp(c, 4), Ui.dp(c, 8), Ui.dp(c, 4)); addView(listScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 300))) }
    private var loading = false
    private var failed = false

    init {
        orientation = VERTICAL
        addView(search, Ui.params())
        addView(chosenLabel, Ui.params(c = c, top = 16))
        addView(chips, Ui.params(c = c, top = 8)); addView(none, Ui.params(c = c, top = 8))
        addView(allLabel, Ui.params(c = c, top = 16))
        addView(listCard, Ui.params(c = c, top = 8))
        // the list scrolls inside the page: stop the page taking the drag
        listScroll.setOnTouchListener { v, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) v.parent.requestDisallowInterceptTouchEvent(true); false }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { model.query = s?.toString() ?: ""; renderList() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        render()
    }

    /** Called when the account's groups arrive (or fail to). */
    fun setGroups(groups: List<Group>, failed: Boolean = false) { loading = false; this.failed = failed; model.setAll(groups); render() }
    fun setLoading() { loading = true; render() }

    private fun render() {
        val c = context
        val ch = model.chosenGroups()
        chosenLabel.text = "Chosen (${ch.size})"
        chips.removeAllViews()
        for (g in ch) chips.addView(Ui.text(c, g.name + "  ×", 17f, Ui.ACCENT, true).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(c, 48); setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0)
            background = Ui.shape(c, Ui.ACCENT_SOFT, 24); contentDescription = "Remove ${g.name}"; isClickable = true
            setOnClickListener { model.remove(g.id); render(); onChange(model.chosenGroups()) }
        })
        chips.visibility = if (ch.isEmpty()) View.GONE else View.VISIBLE
        none.visibility = if (ch.isEmpty()) View.VISIBLE else View.GONE
        allLabel.text = "All your groups (${model.total}), A to Z"
        renderList()
    }

    private fun renderList() {
        val c = context
        list.removeAllViews()
        val rows = model.rows()
        if (rows.isEmpty()) {
            val msg = when { loading -> "Loading your groups…"; model.total == 0 -> if (failed) "Could not load your groups. Check the connection and try again." else "No groups found."; else -> "No group matches that search." }
            list.addView(Ui.text(c, msg, 17f, Ui.MUTED).apply { setPadding(Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16)) })
            return
        }
        for (r in rows) {
            val line = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(c, 56); setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0) }
            val box = CheckBox(c).apply { isChecked = r.checked; buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT); minWidth = Ui.dp(c, 48); minHeight = Ui.dp(c, 48) }
            val name = Ui.col(c).apply {
                addView(Ui.text(c, r.group.name, 17f)); if (r.hint != null) addView(Ui.text(c, r.hint, 14f, Ui.MUTED))
            }
            line.addView(box); line.addView(name, Ui.params(0, weight = 1f))
            val toggle = { model.toggle(r.group); render(); onChange(model.chosenGroups()) }
            box.setOnClickListener { toggle() }
            line.setOnClickListener { toggle() }
            list.addView(line)
        }
    }
}
