package com.example.animastor.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Layer-config write serialization (E2E 02.11: 2–3 overlapping PUTs, a stale
// image=true for 0.36 s). Pure JVM tests — no Android framework calls.
// Web parity: frontends/app/src/features/generation/layerSync.test.ts
// (same fixtures, same assertions).
class LayerConfigSyncTest {

    private val allOn = LayerFlags(audio = true, image = true, video = true, vbook = true)

    @Test
    fun `one action collapses into a single PUT with the final flags`() = runBlocking {
        val bodies = mutableListOf<LayerFlags>()
        val sync = LayerConfigSync(allOn, persist = { bodies += it })

        // Each request carries the complete flag set (the ViewModel merges
        // its signals); one action fires several setters in a row.
        sync.request(allOn.copy(audio = false))
        sync.request(allOn.copy(audio = false, image = false))
        sync.request(allOn.copy(audio = false, image = false, video = false))
        sync.flush()

        assertEquals(1, bodies.size)
        assertEquals(LayerFlags(audio = false, image = false, video = false, vbook = true), bodies[0])
        assertFalse(sync.state().pending)
    }

    @Test
    fun `never two PUTs in flight and the newest flags are sent second`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val bodies = mutableListOf<LayerFlags>()
        var concurrent = 0
        var maxConcurrent = 0
        val sync = LayerConfigSync(allOn, persist = { flags ->
            concurrent += 1
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            bodies += flags
            if (bodies.size == 1) gate.await()
            concurrent -= 1
        })

        sync.request(allOn.copy(image = false))       // click 1
        val firstFlush = launch { sync.flush() }
        while (bodies.isEmpty()) yield()

        sync.request(allOn.copy(image = true))        // click 2 while click 1 is on the wire
        val secondFlush = launch { sync.flush() }
        yield()
        assertEquals(1, bodies.size)                  // serialized

        gate.complete(Unit)
        firstFlush.join()
        secondFlush.join()

        assertEquals(2, bodies.size)
        assertEquals(allOn, bodies[1])                // latest state, not click 1's snapshot
        assertEquals(1, maxConcurrent)
        assertEquals(allOn, sync.state().confirmed)
        assertFalse(sync.state().pending)
    }

    @Test
    fun `stays pending from the click until the server confirms`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<Boolean>()
        val sync = LayerConfigSync(
            allOn,
            persist = { gate.await() },
            onChange = { seen += it.pending }
        )

        sync.request(allOn.copy(video = false))
        assertTrue(sync.state().pending)

        val flush = launch { sync.flush() }
        yield()
        assertTrue(sync.state().inFlight)
        assertTrue(sync.state().pending)

        gate.complete(Unit)
        flush.join()

        assertFalse(sync.state().pending)
        assertTrue(seen.first())
        assertFalse(seen.last())
    }

    @Test
    fun `no PUT when the requested flags already match the confirmed state`() = runBlocking {
        val bodies = mutableListOf<LayerFlags>()
        val sync = LayerConfigSync(allOn, persist = { bodies += it })

        sync.request(allOn)
        sync.flush()

        assertEquals(0, bodies.size)
    }

    @Test
    fun `failed PUT keeps pending and error and the next request retries`() = runBlocking {
        var attempts = 0
        val sync = LayerConfigSync(allOn, persist = {
            attempts += 1
            if (attempts == 1) throw java.io.IOException("network")
        })

        sync.request(allOn.copy(audio = false))
        sync.flush()

        assertTrue(sync.state().pending)
        assertEquals("network", sync.state().lastError)

        sync.request(allOn.copy(audio = false))
        sync.flush()

        assertEquals(2, attempts)
        assertNull(sync.state().lastError)
        assertEquals(false, sync.state().confirmed?.audio)
    }

    @Test
    fun `reset adopts a fresh GET only while idle`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val sync = LayerConfigSync(allOn, persist = { gate.await() })

        val allOff = LayerFlags(audio = false, image = false, video = false, vbook = false)
        assertTrue(sync.reset(allOff))
        assertEquals(allOff, sync.state().desired)
        assertFalse(sync.state().pending)

        sync.request(allOn)
        val flush = launch { sync.flush() }
        yield()
        assertFalse(sync.reset(allOff))               // PUT on the wire — don't clobber
        assertTrue(sync.state().desired.audio)

        gate.complete(Unit)
        flush.join()
    }
}
