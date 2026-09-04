package app.rommdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlEntitiesTest {

    @Test
    fun `decodes the escaped quotes RomM actually serves`() {
        assertEquals(
            "the player must control a \"podship\"",
            "the player must control a &quot;podship&quot;".decodeHtmlEntities(),
        )
    }

    @Test
    fun `preserves paragraph breaks`() {
        val summary = "First &quot;line&quot;.\n\nSecond line."
        assertEquals("First \"line\".\n\nSecond line.", summary.decodeHtmlEntities())
    }

    @Test
    fun `decodes numeric references in both bases`() {
        assertEquals("it's", "it&#39;s".decodeHtmlEntities())
        assertEquals("it's", "it&#x27;s".decodeHtmlEntities())
        assertEquals("it's", "it&#X27;s".decodeHtmlEntities())
        assertEquals("😀", "&#x1F600;".decodeHtmlEntities())
    }

    @Test
    fun `does not decode twice`() {
        // "&amp;quot;" is an escaped literal "&quot;", not an escaped quote.
        assertEquals("&quot;", "&amp;quot;".decodeHtmlEntities())
    }

    @Test
    fun `leaves literal ampersands alone`() {
        assertEquals("Tom & Jerry", "Tom & Jerry".decodeHtmlEntities())
        assertEquals("R&D; notes", "R&D; notes".decodeHtmlEntities())
        assertEquals("Q&A", "Q&A".decodeHtmlEntities())
        assertEquals("a & b;", "a & b;".decodeHtmlEntities())
    }

    @Test
    fun `leaves unknown and malformed references alone`() {
        assertEquals("&frobnicate;", "&frobnicate;".decodeHtmlEntities())
        assertEquals("&#;", "&#;".decodeHtmlEntities())
        assertEquals("&#xZZ;", "&#xZZ;".decodeHtmlEntities())
        assertEquals("&#999999999999;", "&#999999999999;".decodeHtmlEntities())
        assertEquals("&", "&".decodeHtmlEntities())
    }

    @Test
    fun `returns the same instance when there is nothing to decode`() {
        val plain = "Super Mario Advance"
        assertSame(plain, plain.decodeHtmlEntities())
    }

    @Test
    fun `decodes typographic and accented names`() {
        assertEquals("Pokémon\u00A0Red", "Pok&eacute;mon&nbsp;Red".decodeHtmlEntities())
        assertEquals("Sonic™", "Sonic&trade;".decodeHtmlEntities())
        assertEquals("it’s — really", "it&rsquo;s &mdash; really".decodeHtmlEntities())
    }
}
