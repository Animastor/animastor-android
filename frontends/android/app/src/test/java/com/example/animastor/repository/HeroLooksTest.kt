package com.example.animastor.repository

import com.example.animastor.repository.HeroFixtures.chapters
import com.example.animastor.repository.HeroFixtures.narrator
import com.example.animastor.repository.HeroFixtures.shopWomen
import com.example.animastor.repository.HeroFixtures.yura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Looks — passport overrides (VBOOK_FORMAT_V4.md §4a). resolveLook is the
 * client copy of vbook-runtime resolveHeroLook. One to one with web
 * features/heroes/heroLooks.test.ts and sceneBook.test.tsx (lookPatchFields).
 */
class HeroLooksTest {
    private val ch1 get() = chapters[0]
    private val ch2 get() = chapters[1]
    private val ch3 get() = chapters[2]
    private val base = LookValues("man in his early thirties, short dark hair", "worn brown leather jacket", listOf("man", "dark hair", "leather jacket"))

    private fun assertBase(r: ResolvedLook) {
        assertEquals(base.appearance, r.appearance); assertEquals(base.clothes, r.clothes); assertEquals(base.video_tokens, r.video_tokens)
    }

    @Test fun `no references - the base passport`() {
        val r = HeroModel.resolveLook(yura, ch1, ch1.scenes!![0])!!
        assertEquals("base", r.look_id); assertEquals("base", r.source); assertBase(r)
        assertEquals(emptyList<String>(), r.changed); assertEquals(emptyList<String>(), r.patched); assertEquals(emptyList<String>(), r.warnings)
    }

    @Test fun `chapter look replaces whole fields, the rest stays base`() {
        val r = HeroModel.resolveLook(yura, ch2, ch2.scenes!![0])!!
        assertEquals("coat", r.look_id); assertEquals("chapter", r.source)
        assertEquals(base.appearance, r.appearance); assertEquals("long dark grey wool coat", r.clothes); assertEquals(listOf("man", "grey coat"), r.video_tokens)
        assertEquals(listOf("clothes", "video_tokens"), r.changed)
        assertEquals(base, r.base)
    }

    @Test fun `base in a scene wins over the chapter look`() {
        val r = HeroModel.resolveLook(yura, ch2, ch2.scenes!![1])!!
        assertEquals("base", r.look_id); assertEquals("scene", r.source); assertBase(r); assertEquals(emptyList<String>(), r.changed)
    }

    @Test fun `scene look wins, the one-off patch goes on top of it`() {
        val r = HeroModel.resolveLook(yura, ch3, ch3.scenes!![0])!!
        assertEquals("beard", r.look_id); assertEquals("scene", r.source)
        assertEquals("man in his early thirties, thick dark beard", r.appearance); assertEquals(base.clothes, r.clothes)
        assertEquals(listOf("man", "beard", "scarf"), r.video_tokens)
        assertEquals(listOf("appearance", "video_tokens"), r.changed)
        assertEquals(listOf("video_tokens"), r.patched)
    }

    @Test fun `scene look wins over the chapter look`() {
        val r = HeroModel.resolveLook(yura, ch2, LookScene(looks = mapOf("yura" to "beard")))!!
        assertEquals("beard", r.look_id); assertEquals("scene", r.source); assertEquals(base.clothes, r.clothes)
    }

    @Test fun `an unknown look is skipped with a warning - next source, then base`() {
        val a = HeroModel.resolveLook(yura, ch2, LookScene(looks = mapOf("yura" to "gone")))!!
        assertEquals("coat", a.look_id); assertEquals("chapter", a.source); assertEquals(listOf("unknown_look:gone"), a.warnings)
        val b = HeroModel.resolveLook(yura, LookChapter(looks = mapOf("yura" to "gone")), null)!!
        assertEquals("base", b.look_id); assertEquals("base", b.source); assertBase(b); assertEquals(listOf("unknown_look:gone"), b.warnings)
    }

    @Test fun `empty patch values do not erase anything`() {
        val r = HeroModel.resolveLook(yura, ch1, LookScene(look_patch = mapOf("yura" to LookPatch(appearance = "", clothes = null, video_tokens = emptyList<String>()))))!!
        assertBase(r); assertEquals(emptyList<String>(), r.patched)
    }

    @Test fun `a hero without a passport has no look`() {
        assertNull(HeroModel.resolveLook(shopWomen, ch2, ch2.scenes!![2]))
        assertNull(HeroModel.resolveLook(narrator, ch1, null))
    }

    @Test fun `previewLook shows a look without a scene`() {
        val coat = HeroModel.previewLook(yura, "coat")!!
        assertEquals("coat", coat.look_id); assertEquals(listOf("clothes", "video_tokens"), coat.changed)
        val b = HeroModel.previewLook(yura, "base")!!
        assertEquals("base", b.look_id); assertEquals(emptyList<String>(), b.changed)
    }

    @Test fun `lookUsage lists the places that reference each look, forced base scenes under base`() {
        val u = HeroModel.lookUsage(chapters, "yura")
        assertEquals(listOf("base", "beard", "coat"), u.keys.sorted())
        assertEquals(listOf(LookPlace("ch-000002", null, 2, "", null, "")), u["coat"])
        val beard = u["beard"]!!.single()
        assertEquals("ch-000003", beard.chapterId); assertEquals("sc-000031", beard.sceneId); assertEquals("Зима", beard.chapterTitle); assertEquals(1, beard.sceneNumber)
        val forced = u["base"]!!.single()
        assertEquals("ch-000002", forced.chapterId); assertEquals("sc-000022", forced.sceneId); assertEquals("Дома", forced.sceneTitle)
        assertEquals(emptyMap<String, List<LookPlace>>(), HeroModel.lookUsage(chapters, "shop_women"))
        assertEquals(emptyMap<String, List<LookPlace>>(), HeroModel.lookUsage(null, "yura"))
    }

    @Test fun `look draft - untouched sends nothing, changes only, clearing removes a field`() {
        val coat = yura.looks!![0]
        assertEquals(emptyMap<String, Any?>(), HeroModel.diffLook(coat, HeroModel.draftFromLook(coat)))
        assertEquals(
            mapOf("label" to "В сером пальто", "fields.appearance" to "older, grey temples", "fields.clothes" to null),
            HeroModel.diffLook(coat, HeroModel.draftFromLook(coat).copy(label = " В сером пальто ", clothes = "", appearance = "older, grey temples")),
        )
        assertEquals(emptyMap<String, Any?>(), HeroModel.diffLook(coat, HeroModel.draftFromLook(coat).copy(videoTokens = "man,  grey coat")))
        assertEquals(mapOf("fields.video_tokens" to null), HeroModel.diffLook(coat, HeroModel.draftFromLook(coat).copy(videoTokens = "")))
    }

    @Test fun `look validation, fields of a new look and a new look id`() {
        val coat = yura.looks!![0]
        assertEquals(emptyList<String>(), HeroModel.validateLookDraft(HeroModel.draftFromLook(coat)))
        assertEquals(listOf("look_err_label_required", "look_err_empty"), HeroModel.validateLookDraft(HeroModel.draftFromLook(null)))
        assertEquals(listOf("hero_err_tokens_max"), HeroModel.validateLookDraft(HeroModel.draftFromLook(coat).copy(videoTokens = "a,b,c,d,e")))
        assertEquals(LookFields(clothes = "red dress", video_tokens = listOf("woman", "red dress")),
            HeroModel.lookFieldsFromDraft(LookDraft("x", "", " ", " red dress ", "woman, red dress")))
        assertNull(HeroModel.validateNewLookId("", yura))
        assertNull(HeroModel.validateNewLookId("wounded", yura))
        assertEquals("look_id_reserved", HeroModel.validateNewLookId("base", yura))
        assertEquals("look_id_taken", HeroModel.validateNewLookId("coat", yura))
        assertEquals("look_id_invalid", HeroModel.validateNewLookId("В пальто", yura))
    }

    @Test fun `diffAssignment - no change sends nothing, splits checked and unchecked places`() {
        val current = HeroModel.lookUsage(chapters, "yura")["coat"]!!
        val same = HeroModel.diffAssignment(current, setOf(HeroModel.lookPlaceKey("ch-000002", null)))
        assertTrue(HeroModel.isEmptyAssignment(same.assign) && HeroModel.isEmptyAssignment(same.release))
        val d = HeroModel.diffAssignment(current, setOf(HeroModel.lookPlaceKey("ch-000003", null), HeroModel.lookPlaceKey("ch-000001", "sc-000011")))
        assertEquals(LookAssignment(listOf("ch-000003"), listOf(HeroSceneRef("ch-000001", "sc-000011"))), d.assign)
        assertEquals(LookAssignment(listOf("ch-000002"), emptyList()), d.release)
        assertFalse(HeroModel.isEmptyAssignment(d.assign))
    }

    // ── item 13: scene tab — look_patch only, never passport.* ──

    @Test fun `lookPatchFields - only changed fields as look_patch dot hero dot field, never passport`() {
        val existing = mapOf<String, LookPatch?>("yura" to LookPatch(clothes = "coat"))
        val fields = SceneLooks.lookPatchFields(listOf(SceneLooks.LookPatchBlock("yura", mapOf("clothes" to "no hat"))), existing)
        assertEquals(mapOf("look_patch.yura.clothes" to "no hat"), fields)
        assertFalse(fields.keys.any { it.startsWith("passport.") })
    }

    @Test fun `lookPatchFields - a removed block clears its stored fields with empty strings`() {
        assertEquals(mapOf("look_patch.anna.appearance" to ""),
            SceneLooks.lookPatchFields(listOf(SceneLooks.LookPatchBlock("", emptyMap())), mapOf("anna" to LookPatch(appearance = "pale"))))
    }

    @Test fun `lookPatchFields - a new hero block sends non-empty fields, an empty block nothing`() {
        assertEquals(mapOf("look_patch.anna.appearance" to "wet hair"),
            SceneLooks.lookPatchFields(listOf(SceneLooks.LookPatchBlock("anna", mapOf("appearance" to " wet hair ")), SceneLooks.LookPatchBlock("yura", mapOf("clothes" to " "))), null))
        assertEquals("a, b", SceneLooks.lookPatchText(listOf("a", "b")))
        assertEquals("", SceneLooks.lookPatchText(null))
    }

    @Test fun `scene model carries looks and look_patch and no passport field any more`() {
        val fields = Scene::class.java.declaredFields.map { it.name }
        assertTrue(fields.contains("looks")); assertTrue(fields.contains("look_patch")); assertFalse(fields.contains("passport"))
        assertTrue(Chapter::class.java.declaredFields.any { it.name == "looks" })
    }
}
