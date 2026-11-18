package com.example.animastor.ui.heroes

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.animastor.repository.Hero
import com.example.animastor.repository.HeroIssue
import com.example.animastor.repository.HeroModel
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

// ======================================================
// «Герои» tab — the grid of hero tiles (HERO_CARD.md §2)
// ======================================================
// Web twin: features/heroes/HeroesPanel.tsx. The tab replaces «Персонажи» /
// «Голоса» / «Поведение»: one hero = one tile = one card. Building the grid
// calls NO API (HeroesPanel.test.tsx «opening the grid calls no API at all»):
// it only reads the GET /book data it is given. Broken hero files (blocking
// hero_issues) are their own tiles with «delete file».

object HeroesPanel {

    fun build(
        ctx: Context,
        parent: ViewGroup,
        heroes: List<Hero>,
        issues: List<HeroIssue>,
        onOpen: (Hero) -> Unit,
        onAdd: () -> Unit,
        onDeleteBroken: (HeroIssue) -> Unit,
    ) {
        val root = column(ctx)
        val broken = HeroModel.blockingIssues(issues)
        if (heroes.isEmpty() && broken.isEmpty()) {
            root.addView(hint(ctx, ctx.heroStr("heroes_empty")).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(8)) })
        }
        val tiles = mutableListOf<View>()
        heroes.forEach { h -> tiles += heroTile(ctx, h) { onOpen(h) } }
        broken.forEach { issue -> tiles += brokenTile(ctx, issue, onDeleteBroken) }
        tiles += addTile(ctx, onAdd)
        tiles.chunked(2).forEach { pair ->
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            pair.forEachIndexed { i, tile ->
                tile.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also {
                    it.setMargins(if (i == 0) 0 else ctx.dp(4), 0, if (i == 0) ctx.dp(4) else 0, ctx.dp(8))
                }
                r.addView(tile)
            }
            if (pair.size == 1) r.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f).also { it.marginStart = ctx.dp(4) } })
            root.addView(r)
        }
        parent.addView(root)
    }

    /** The "characteristics" line of a tile (web HeroTile pips, in words). */
    fun statsLine(ctx: Context, hero: Hero): String {
        val s = HeroModel.heroStats(hero)
        val parts = mutableListOf<String>()
        s.passport?.let { parts += ctx.heroStr("hero_stat_passport", it) }
        s.voice?.let { parts += ctx.heroStr("hero_stat_voice", it) + " · " + ctx.heroStr(s.reference.key) }
        if (s.rulesTotal > 0) parts += ctx.heroStr("hero_stat_rules", s.rulesOn, s.rulesTotal)
        return parts.joinToString(" · ")
    }

    private fun tileCard(ctx: Context): Pair<MaterialCardView, LinearLayout> {
        val card = MaterialCardView(ctx).apply {
            radius = ctx.dp(12).toFloat()
            cardElevation = ctx.dp(1).toFloat()
            isClickable = true
            isFocusable = true
        }
        val body = column(ctx).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
        }
        card.addView(body)
        return card to body
    }

    private fun monogram(ctx: Context, text: String, kind: HeroModel.HeroKind): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.CENTER
        val bg = when (kind) {
            HeroModel.HeroKind.NARRATOR -> com.google.android.material.R.attr.colorTertiaryContainer
            HeroModel.HeroKind.VOICE_ONLY -> com.google.android.material.R.attr.colorSecondaryContainer
            HeroModel.HeroKind.CHARACTER -> com.google.android.material.R.attr.colorPrimaryContainer
        }
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(MaterialColors.getColor(ctx, bg, 0xFFDDDDDD.toInt()))
        }
        val sz = ctx.dp(56)
        layoutParams = LinearLayout.LayoutParams(sz, sz).also { it.bottomMargin = ctx.dp(6) }
    }

    private fun heroTile(ctx: Context, hero: Hero, onOpen: () -> Unit): View {
        val (card, body) = tileCard(ctx)
        val kind = HeroModel.heroKind(hero)
        body.addView(monogram(ctx, if (kind == HeroModel.HeroKind.NARRATOR) "🔊" else HeroModel.heroMonogram(hero), kind))
        val name = (hero.name ?: "").trim().ifEmpty { if (HeroModel.isNarrator(hero)) ctx.heroStr("hero_kind_narrator") else hero.id }
        body.addView(label(ctx, name, 15f, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2 })
        val role = (hero.role ?: "").trim().takeIf { it.isNotEmpty() && kind != HeroModel.HeroKind.NARRATOR }
        body.addView(hint(ctx, role ?: ctx.heroStr(kind.key)).apply { gravity = Gravity.CENTER })
        statsLine(ctx, hero).takeIf { it.isNotEmpty() }?.let { body.addView(hint(ctx, it).apply { gravity = Gravity.CENTER }) }
        card.contentDescription = name
        card.setOnClickListener { onOpen() }
        return card
    }

    private fun brokenTile(ctx: Context, issue: HeroIssue, onDelete: (HeroIssue) -> Unit): View {
        val (card, body) = tileCard(ctx)
        card.strokeWidth = ctx.dp(1)
        card.strokeColor = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorError, 0xFFB00020.toInt())
        body.addView(label(ctx, issue.hero_id ?: issue.file ?: "?", 14f, bold = true).apply { gravity = Gravity.CENTER })
        body.addView(hint(ctx, ctx.heroStr("hero_broken_title")).apply { gravity = Gravity.CENTER })
        body.addView(hint(ctx, issue.message ?: issue.code).apply { gravity = Gravity.CENTER; maxLines = 3 })
        val target = HeroModel.brokenFileTarget(issue)
        if (target != null) {
            if (target is com.example.animastor.repository.BrokenFileTarget.ByFile) body.addView(hint(ctx, ctx.heroStr("hero_broken_file_hint")))
            body.addView(textButton(ctx, ctx.heroStr("hero_broken_delete"), danger = true) { onDelete(issue) })
        }
        return card
    }

    private fun addTile(ctx: Context, onAdd: () -> Unit): View {
        val (card, body) = tileCard(ctx)
        body.addView(label(ctx, "+", 28f).apply { gravity = Gravity.CENTER })
        body.addView(label(ctx, ctx.heroStr("heroes_add"), 14f).apply { gravity = Gravity.CENTER })
        card.setOnClickListener { onAdd() }
        return card
    }
}
