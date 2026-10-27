package org.pararead.host

import org.json.JSONObject
import org.junit.Test
import org.pararead.probes.translationProbeRequest
import kotlin.test.*

class ProbePromptsTest {
    @Test fun bracketAndXmlPreserveExactInventoryWhileAllowingWordOrderChanges() {
        for ((style, raw) in listOf("bracket" to "使用[[PR1]]内存处理[[PR0]]页", "xml" to "使用<ph1/>内存处理<ph0/>页")) {
            val request = translationProbeRequest(JSONObject().put("file", "test.gguf").put("markerStyle", style), "__PR0__ pages using __PR1__ memory")
            assertEquals("使用__PR1__内存处理__PR0__页", request.decode(raw))
            assertFailsWith<IllegalArgumentException> { request.decode(raw + raw) }
            assertFailsWith<IllegalArgumentException> { request.decode("没有标记") }
        }
    }
    @Test fun unknownLeadingZeroAndMixedEncodingsAreRejected() {
        val request = translationProbeRequest(JSONObject().put("file", "test.gguf").put("markerStyle", "bracket"), "__PR0__")
        for (raw in listOf("[[PR9]]", "[[PR00]]", "[[PR0]][[PR9]]", "[[PR0]]__PR0__"))
            assertFailsWith<IllegalArgumentException> { request.decode(raw) }
    }
    @Test fun boundedRecoveryChangesOnlyEncodingAndKeepsTheTranslationProfile() {
        val source = JSONObject().put("file", "test.gguf").put("promptProfile", "rwkv-translation").put("alternateMarkerRecovery", true)
        val requests = (0..2).map { translationProbeRequest(source, "Use __PR0__.", it) }
        assertEquals(listOf("ascii", "bracket", "xml"), requests.map { it.markerStyle })
        assertEquals("English: Use [[PR0]].\n\nChinese:", requests[1].prompt.user)
        assertTrue(requests.all { it.prompt.system.isEmpty() })
    }
    @Test fun customTokenizerProfileUsesNfcAndTheDeclaredTargetLabel() {
        val source = JSONObject().put("file", "test.gguf").put("promptProfile", "immersive").put("normalizeNfc", true).put("targetLabel", "chinese")
        val request = translationProbeRequest(source, "Cafe\u0301 __PR0__")
        assertTrue(request.prompt.user.contains("to chinese.")); assertTrue(request.prompt.user.contains("Café __PR0__"))
        assertEquals("You are a professional, authentic machine translation engine.", request.prompt.system)
    }
}
