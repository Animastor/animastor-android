package com.example.animastor.ui.heroes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

// Small view builders of the hero screens — the editor's look (outlined
// inputs in 12dp cards, tonal chips) without XML layouts.

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** A string resource by its web i18n key (the same keys in strings.xml, HERO_CARD.md §8). */
@SuppressLint("DiscouragedApi")
internal fun Context.heroStr(key: String, vararg args: Any): String {
    val id = resources.getIdentifier(key, "string", packageName)
    if (id == 0) return key
    return if (args.isEmpty()) getString(id) else getString(id, *args)
}

internal fun Context.uiText(t: UiText): String = when (t) {
    is UiText.Raw -> t.text
    is UiText.Key -> heroStr(t.name, *t.args.toTypedArray())
}

internal fun Context.attrColor(attr: Int, view: View): Int = MaterialColors.getColor(view, attr)

internal fun column(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.VERTICAL
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

internal fun label(ctx: Context, text: String, size: Float = 14f, bold: Boolean = false, colorAttr: Int? = null): TextView =
    TextView(ctx).apply {
        this.text = text
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        val attr = colorAttr ?: com.google.android.material.R.attr.colorOnSurface
        setTextColor(MaterialColors.getColor(ctx, attr, 0xFF202020.toInt()))
        setPadding(0, ctx.dp(2), 0, ctx.dp(2))
    }

internal fun hint(ctx: Context, text: String): TextView =
    label(ctx, text, 12f, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant)

/** A tinted line: error / warning / info (role "alert" = error). */
internal fun banner(ctx: Context, text: String, kind: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 13f
    val (bgAttr, fgAttr) = when (kind) {
        "error" -> com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
        "warn" -> com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
        else -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
    }
    setTextColor(MaterialColors.getColor(ctx, fgAttr, 0xFF202020.toInt()))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(8).toFloat()
        setColor(MaterialColors.getColor(ctx, bgAttr, 0xFFEEEEEE.toInt()))
    }
    setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
    layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .also { it.setMargins(0, 0, 0, ctx.dp(6)) }
}

internal fun chip(ctx: Context, text: String, on: Boolean = false): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 12f
    val bg = if (on) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceVariant
    val fg = if (on) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant
    setTextColor(MaterialColors.getColor(ctx, fg, 0xFF202020.toInt()))
    background = GradientDrawable().apply {
        cornerRadius = ctx.dp(10).toFloat()
        setColor(MaterialColors.getColor(ctx, bg, 0xFFEEEEEE.toInt()))
    }
    setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .also { it.setMargins(0, 0, ctx.dp(6), 0) }
}

/** Outlined text input inside a card; [onChange] on every edit. */
internal fun field(
    ctx: Context,
    labelText: String,
    value: String,
    multiline: Boolean = false,
    hintText: String? = null,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
): View {
    val til = TextInputLayout(ctx).apply {
        hint = labelText
        isHintEnabled = true
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        if (hintText != null) helperText = hintText
        isEnabled = enabled
    }
    val et = TextInputEditText(ctx).apply {
        setText(value)
        textSize = 14f
        if (multiline) {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        } else {
            isSingleLine = true
        }
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
        })
    }
    til.addView(et)
    return MaterialCardView(ctx).apply {
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.setMargins(0, 0, 0, ctx.dp(8)) }
        radius = ctx.dp(12).toFloat()
        cardElevation = 0f
        addView(til)
    }
}

internal fun button(ctx: Context, text: String, outlined: Boolean = true, onClick: (View) -> Unit): MaterialButton {
    val style = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    return MaterialButton(ctx, null, style).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick(it) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.setMargins(0, 0, ctx.dp(8), 0) }
    }
}

internal fun textButton(ctx: Context, text: String, danger: Boolean = false, onClick: (View) -> Unit): MaterialButton =
    MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        this.text = text
        isAllCaps = false
        if (danger) setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorError, 0xFFB00020.toInt()))
        setOnClickListener { onClick(it) }
    }

/** A titled section of the card: title row (+ optional action on the right) and a body. */
internal fun section(ctx: Context, title: String, action: View? = null): Pair<View, LinearLayout> {
    val card = MaterialCardView(ctx).apply {
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.setMargins(0, 0, 0, ctx.dp(12)) }
        radius = ctx.dp(12).toFloat()
        cardElevation = ctx.dp(1).toFloat()
    }
    val body = column(ctx).apply { setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(10)) }
    val head = row(ctx)
    head.addView(label(ctx, title, 16f, bold = true).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    if (action != null) head.addView(action)
    body.addView(head)
    card.addView(body)
    return card to body
}
