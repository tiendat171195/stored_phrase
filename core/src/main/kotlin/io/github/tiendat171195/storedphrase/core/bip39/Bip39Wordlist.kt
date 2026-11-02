package io.github.tiendat171195.storedphrase.core.bip39

/** The official BIP39 English wordlist (2048 words, sorted, unique in their first four letters). */
object Bip39Wordlist {
    const val SIZE = 2048

    val words: List<String> = Bip39EnglishWords.ALL.split(' ').also {
        check(it.size == SIZE) { "BIP39 wordlist must have $SIZE words" }
    }

    fun word(index: Int): String = words[index]

    /** Index of [word], or -1 when it is not a BIP39 word. */
    fun indexOf(word: String): Int {
        val i = words.binarySearch(word)
        return if (i >= 0) i else -1
    }

    /** Indices of words starting with [prefix], alphabetically, at most [limit]. Empty prefix gives nothing. */
    fun indicesWithPrefix(prefix: String, limit: Int = SIZE): List<Int> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        var i = firstAtOrAfter(prefix)
        val result = ArrayList<Int>(minOf(limit, 16))
        while (i < SIZE && words[i].startsWith(prefix) && result.size < limit) {
            result.add(i)
            i++
        }
        return result
    }

    /** Letters that extend [prefix] towards at least one word. */
    fun nextLetters(prefix: String): Set<Char> {
        val letters = HashSet<Char>()
        var i = if (prefix.isEmpty()) 0 else firstAtOrAfter(prefix)
        while (i < SIZE && words[i].startsWith(prefix)) {
            val w = words[i]
            if (w.length > prefix.length) letters.add(w[prefix.length])
            i++
        }
        return letters
    }

    private fun firstAtOrAfter(prefix: String): Int {
        val i = words.binarySearch(prefix)
        return if (i >= 0) i else -i - 1
    }
}
