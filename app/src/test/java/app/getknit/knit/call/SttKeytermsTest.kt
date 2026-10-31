package app.getknit.knit.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names a call's transcript listens for (C-3.11-9): ranked, cleaned to what the model can spell, and capped. */
class SttKeytermsTest {
    @Test
    fun thePeopleOnTheCallComeFirstThenTheThreadsThenTheThreadsNames() {
        val terms =
            sttKeyterms(
                peerName = "Ada Lovelace",
                myName = "Grace",
                threadNames = listOf("Hiking crew", "Ada Lovelace"),
                recentTexts = listOf("Meet Charles at the gate, then Charles again"),
            )
        assertEquals(listOf("Ada Lovelace", "Ada", "Grace", "Hiking crew", "Hiking", "Charles"), terms)
    }

    @Test
    fun aDuplicateKeepsItsFirstSpellingWhateverItsCase() {
        assertEquals(listOf("Ada"), sttKeyterms("Ada", "ada", listOf("ADA"), listOf("hi, Ada")))
    }

    @Test
    fun aTermKeepsOnlyWhatANameNeedsAndNoComma() {
        val terms = sttKeyterms("Jean-Luc O'Brien, 🌻 Jr.", null, emptyList(), emptyList())
        assertEquals(listOf("Jean-Luc O'Brien Jr.", "Jean-Luc"), terms)
        assertTrue(terms.none { ',' in it })
    }

    @Test
    fun aShortOrEmptyNameIsNoTerm() {
        assertEquals(emptyList<String>(), sttKeyterms("Al", "  ", listOf("🌻", ""), emptyList()))
    }

    @Test
    fun aLongNameIsCutAtAWord() {
        val term = sttKeyterms("Aaaaaaaaaa Bbbbbbbbbb Cccccccccc Dddddddddd Eeeeeeeeee", null, emptyList(), emptyList()).first()
        assertEquals("Aaaaaaaaaa Bbbbbbbbbb Cccccccccc", term)
        assertTrue(term.length <= SttKeytermPolicy.MAX_TERM_CHARS)
    }

    @Test
    fun theListIsCapped() {
        val names = (1..100).map { "Name${'a' + it % 26}${'a' + it / 26} Person" }
        val terms = sttKeyterms("Ada", "Grace", names, emptyList())
        assertEquals(SttKeytermPolicy.MAX_TERMS, terms.size)
        assertEquals(listOf("Ada", "Grace"), terms.take(2))
    }

    @Test
    fun aThreadsNamesAreItsCapitalisedWordsInsideASentenceMostFrequentFirst() {
        val nouns =
            properNouns(
                listOf(
                    "Today we meet Brandon. Then Kowalski's place with Brandon",
                    "OK I'm with Mira at the Kowalski's, and Brandon too!",
                    "Room 4B? No, Mira said Building7",
                ),
            )
        assertEquals(listOf("Brandon", "Kowalski's", "Mira"), nouns)
    }

    @Test
    fun aWordOpeningASentenceOrALineIsNotTakenForAName() {
        assertEquals(emptyList<String>(), properNouns(listOf("Sure thing.\nThanks! Great. Nope")))
    }
}
