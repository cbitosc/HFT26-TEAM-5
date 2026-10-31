package app.getknit.knit.call

/**
 * The words a call's transcript listens for (C-3.11-9): names the model would otherwise spell as it hears them. The
 * model nudges its decode towards each listed term ("key terms", Moonshine's runtime biasing), which removes about a
 * quarter of the errors on the terms for a quarter of a point at most on everything else — but every term on the list
 * is charged against the words nobody listed, whether it is ever said or not, so the list is short and ranked: the
 * people on the call first, then the threads used lately, then the names the thread with the peer keeps mentioning.
 */
object SttKeytermPolicy {
    /** The most terms a call listens for: the measured cost of a list grows with its length, said or not. */
    const val MAX_TERMS = 40

    /** The most taken from the thread's messages, after the names. */
    const val MAX_MESSAGE_TERMS = 12

    /** How many of the latest threads lend their names. */
    const val RECENT_THREADS = 20

    /** How many of the thread's latest messages are read for names. */
    const val RECENT_MESSAGES = 100

    /** A longer term is cut off here: a name, not a sentence. */
    const val MAX_TERM_CHARS = 40

    /** A word shorter than this is never a term on its own: short fragments fire on anything. */
    const val MIN_WORD_CHARS = 3
}

/**
 * The key terms for a call with [peerName], as Moonshine takes them, most important first and at most
 * [SttKeytermPolicy.MAX_TERMS]: the peer's name and its first word, this phone's own, the names of [threadNames] (the
 * latest threads, DMs and groups, newest first), and the proper nouns of [recentTexts] (the thread with the peer, any
 * order) ranked by how often they come up. Each is cleaned to what the model's tokenizer can spell — letters, digits,
 * and the marks names use — since one term it cannot spell fails the whole list, and none carries the comma the
 * runtime splits on. Duplicates are dropped whatever their case, the first spelling kept. Pure.
 */
fun sttKeyterms(
    peerName: String?,
    myName: String?,
    threadNames: List<String>,
    recentTexts: List<String>,
): List<String> {
    val out = LinkedHashMap<String, String>()

    fun add(term: String?) {
        val clean = term?.let(::cleanTerm) ?: return
        if (clean.length >= SttKeytermPolicy.MIN_WORD_CHARS) out.putIfAbsent(clean.lowercase(), clean)
    }

    fun addName(name: String?) {
        add(name)
        add(name?.let(::cleanTerm)?.substringBefore(' '))
    }
    addName(peerName)
    addName(myName)
    threadNames.take(SttKeytermPolicy.RECENT_THREADS).forEach(::addName)
    properNouns(recentTexts).take(SttKeytermPolicy.MAX_MESSAGE_TERMS).forEach(::add)
    return out.values.take(SttKeytermPolicy.MAX_TERMS)
}

/**
 * The capitalised words of [texts] that are not opening a sentence — the names and places a thread mentions — most
 * frequent first, ties in the order they first came. A word in capitals throughout (an acronym, a shout) and one
 * starting `I'` are left out, as is anything shorter than [SttKeytermPolicy.MIN_WORD_CHARS] or with a digit. Pure.
 */
fun properNouns(texts: List<String>): List<String> {
    val counts = LinkedHashMap<String, Int>()
    for (text in texts) {
        var sentenceStart = true
        for (match in WORD.findAll(text)) {
            val word = match.value
            val opens = sentenceStart
            val next = text.getOrNull(match.range.last + 1)
            sentenceStart = next != null && next in SENTENCE_ENDS
            if (opens || !isProperNoun(word)) continue
            counts.merge(word, 1, Int::plus)
        }
    }
    return counts.entries.sortedByDescending { it.value }.map { it.key }
}

private fun isProperNoun(word: String): Boolean =
    word.length >= SttKeytermPolicy.MIN_WORD_CHARS &&
        word.first().isUpperCase() &&
        word.any { it.isLowerCase() } &&
        !word.startsWith("I'") &&
        word.none { it.isDigit() }

// [term] with anything a name does not need dropped and its spaces collapsed, cut to [SttKeytermPolicy.MAX_TERM_CHARS]
// at a word; null when nothing is left.
private fun cleanTerm(term: String): String? {
    val kept = term.map { if (it.isLetterOrDigit() || it in NAME_MARKS) it else ' ' }.joinToString("")
    val words = kept.split(' ').filter { it.isNotEmpty() }
    val cut = StringBuilder()
    for (word in words) {
        if (cut.length + word.length + 1 > SttKeytermPolicy.MAX_TERM_CHARS) break
        if (cut.isNotEmpty()) cut.append(' ')
        cut.append(word)
    }
    return cut.toString().ifEmpty { null }
}

// A word: letters, with the apostrophes and hyphens inside a name (O'Brien, Jean-Luc), and any digits it carries, so
// a word with one is seen whole and left out.
private val WORD = Regex("""\p{L}[\p{L}\p{M}\p{N}'’-]*""")

// What ends a sentence, so the word after it is capitalised for that reason alone.
private const val SENTENCE_ENDS = ".!?\n"

// The marks a name keeps besides letters and digits; everything else is a space.
private const val NAME_MARKS = "'’-."
