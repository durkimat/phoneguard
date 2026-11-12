package io.github.durkimat.phoneguard.launcher

import android.content.Context
import io.github.durkimat.phoneguard.Store
import io.github.durkimat.phoneguard.core.ParentLook

/**
 * How the phone looks, as plain data: a theme (its colours and which skin draws it), a font, a main-menu style and an optional
 * accent colour. Nothing here draws anything. The phone keeps its own choices (Settings), and a parent can set any of these
 * from Home Assistant; whatever a parent sets wins, field by field, and the phone's own choice shows for the rest.
 */
data class Look(val theme: String, val font: String, val menuStyle: String, val accent: Int? = null, val style: Style = Style()) {
    /** Which skin draws this look. */
    val skin: String get() = Themes.byId(theme).skin

    /** The theme's colours, with the accent swapped when one is set. */
    fun colours(): Theme = Themes.byId(theme).let { t -> accent?.let { t.copy(accent = it) } ?: t }

    companion object {
        fun resolve(own: Look, parent: ParentLook?): Look {
            if (parent == null) return own
            return Look(
                theme = parent.theme?.takeIf { id -> Themes.all.any { it.id == id } } ?: own.theme,
                font = parent.font?.takeIf { id -> Fonts.all.any { it.id == id } } ?: own.font,
                menuStyle = parent.menuStyle?.takeIf { id -> MenuStyles.all.any { it.id == id } } ?: own.menuStyle,
                accent = parent.accent ?: own.accent,
                style = Choices.keys.fold(own.style) { s, k -> parent.opts[k]?.takeIf { Choices.valid(k, it) }?.let { s.with(k, it) } ?: s },
            )
        }

        /** The settings a parent is holding (unknown names are ignored, so they do not count). */
        fun locked(parent: ParentLook?): Set<String> = buildSet {
            if (parent == null) return@buildSet
            if (parent.theme != null && Themes.all.any { it.id == parent.theme }) add("theme")
            if (parent.font != null && Fonts.all.any { it.id == parent.font }) add("font")
            if (parent.menuStyle != null && MenuStyles.all.any { it.id == parent.menuStyle }) add("menu_style")
            if (parent.accent != null) add("accent")
            Choices.keys.filter { Choices.valid(it, parent.opts[it]) }.forEach { add(it) }
        }

        fun own(c: Context): Look {
            val p = c.createDeviceProtectedStorageContext().getSharedPreferences("launcher", Context.MODE_PRIVATE)
            return Look(
                p.getString("theme", Themes.DEFAULT) ?: Themes.DEFAULT,
                p.getString("font", Fonts.DEFAULT) ?: Fonts.DEFAULT,
                p.getString("menu_style", MenuStyles.DEFAULT) ?: MenuStyles.DEFAULT,
                style = Choices.keys.fold(Style()) { s, k -> p.getString("opt_$k", null)?.takeIf { Choices.valid(k, it) }?.let { s.with(k, it) } ?: s },
            )
        }

        /** What is shown now: the phone's own choices with the parent's laid over them. */
        fun current(c: Context): Look = resolve(own(c), Store.config(c)?.look)
    }
}
