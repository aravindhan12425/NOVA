package com.assistant.nova.core.media

/**
 * Guesses a song's language (and whether it's anime music) from its title, artist, album
 * and folder, so "play a Tamil song" or "play anime songs" work on a mixed library.
 *
 * Downloaded songs rarely have a language tag, but they carry strong hints: the script
 * (தமிழ், हिन्दी, 日本語), words like "(Tamil)" or "Hindi", and the names of film composers,
 * stars and singers. Each hint adds points; the best-scoring language wins.
 * Pure Kotlin so it can be tested on a PC.
 */
object SongTags {
    const val TAMIL = "tamil"
    const val HINDI = "hindi"
    const val TELUGU = "telugu"
    const val MALAYALAM = "malayalam"
    const val KANNADA = "kannada"
    const val PUNJABI = "punjabi"
    const val ENGLISH = "english"
    const val ANIME = "anime"

    /** What the user may say -> tag. */
    val SPOKEN: Map<String, String> = mapOf(
        "tamil" to TAMIL, "tamizh" to TAMIL, "thamizh" to TAMIL, "kollywood" to TAMIL,
        "hindi" to HINDI, "bollywood" to HINDI,
        "telugu" to TELUGU, "tollywood" to TELUGU,
        "malayalam" to MALAYALAM, "kannada" to KANNADA, "punjabi" to PUNJABI,
        "english" to ENGLISH, "anime" to ANIME, "japanese" to ANIME,
    )

    private val INDIAN = listOf(TAMIL, HINDI, TELUGU, MALAYALAM, KANNADA, PUNJABI)

    // Strong: the language named outright
    private val NAMED: Map<String, List<String>> = mapOf(
        TAMIL to listOf("tamil", "tamizh", "thamizh", "kollywood"),
        HINDI to listOf("hindi", "bollywood"),
        TELUGU to listOf("telugu", "tollywood"),
        MALAYALAM to listOf("malayalam", "mollywood"),
        KANNADA to listOf("kannada", "sandalwood"),
        PUNJABI to listOf("punjabi"),
    )

    // Medium: people and films closely tied to one language
    private val PEOPLE: Map<String, List<String>> = mapOf(
        TAMIL to listOf(
            "anirudh", "thalapathy", "ajith", "dhanush", "rajinikanth", "rajini", "kamal haasan", "kamal hassan",
            "suriya", "karthi", "sivakarthikeyan", "vijay sethupathi", "lokesh kanagaraj", "mani ratnam", "atlee",
            "pa ranjith", "ilaiyaraaja", "ilayaraja", "ilaiyaraja", "yuvan", "harris jayaraj", "gv prakash",
            "g v prakash", "d imman", "santhosh narayanan", "sid sriram", "govind vasantha", "ghibran",
            "hiphop tamizha", "chiyaan", "ashok selvan", "mugen rao", "trisha", "ponniyin selvan", "varisu",
            "thunivu", "vivegam", "ngk", "ayogya", "mr local", "jimmyrudh", "anivee", "isaimini", "isaiminida",
            "myisaimini", "starmusiq", "tamilwire", "masstamilan", "kuttyweb", "porkanda", "vandha edam", "beast mode",
        ),
        HINDI to listOf(
            "shah rukh", "shahrukh", "hrithik", "salman khan", "aamir", "ranbir", "ranveer", "deepika",
            "janhvi", "varun", "rajkummar", "tiger shroff", "shraddha", "arijit", "neha kakkar", "honey singh",
            "badshah", "tanishk", "sachin jigar", "mika singh", "amitabh", "kaabil", "roohi", "baaghi",
            "satyameva jayate", "luka chuppi", "yo yo", "kumaar", "chaleya", "zinda banda", "not ramaiya",
            "dilbar", "bhootni", "akhil", "jawan", "saree ke fall",
        ),
        TELUGU to listOf(
            "ala vaikunthapurramuloo", "allu arjun", "mahesh babu", "prabhas", "jr ntr", "ram charan",
            "devi sri prasad", "sudheer babu", "nani", "vijay deverakonda", "armaan malik", "buttabomma",
            "ramuloo", "sittharala", "mangli", "rahul sipligunj", "v songs",
        ),
        MALAYALAM to listOf(
            "nivin pauly", "vineeth sreenivasan", "shaan rahman", "mohanlal", "mammootty", "dulquer", "fahadh",
            "oru adaar love", "kudukku", "isaiminiya", "sathyajith",
        ),
        KANNADA to listOf("yash", "kgf", "puneeth", "sudeep", "ravi basrur"),
        PUNJABI to listOf("shubh", "diljit", "sidhu moose", "ap dhillon", "karan aujla"),
    )

    private val ANIME_HINTS = listOf(
        "anime", "amv", "bleach", "naruto", "one piece", "demon slayer", "kimetsu", "my hero academia",
        "black clover", "solo leveling", "jujutsu", "attack on titan", "blue lock", "asterisk war", "quanzhi fashi",
        "highschool dxd", "reincarnated", "tensei", "slime", "suzume", "radwimps", "lisa", "aimer", "kitani",
        "sawanohiroyuki", "haruka mirai", "gurenge", "chainsaw man", "spy x family", "tokyo ghoul", "death note",
        "inmortal king", "immortal king", "wuthering waves", "genshin",
    )

    /** Tags for one song, e.g. {tamil} or {english, anime}. */
    fun of(title: String, artist: String, album: String, folder: String = ""): Set<String> {
        val raw = "$title | $artist | $album | $folder"
        val text = " " + raw.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        fun has(word: String) = text.contains(" $word ")

        val score = HashMap<String, Int>()
        fun add(tag: String, n: Int) { score[tag] = (score[tag] ?: 0) + n }

        // Scripts
        for (ch in raw) {
            when (ch.code) {
                in 0x0B80..0x0BFF -> { add(TAMIL, 6); break }
                in 0x0900..0x097F -> { add(HINDI, 6); break }
                in 0x0C00..0x0C7F -> { add(TELUGU, 6); break }
                in 0x0D00..0x0D7F -> { add(MALAYALAM, 6); break }
                in 0x0C80..0x0CFF -> { add(KANNADA, 6); break }
                in 0x0A00..0x0A7F -> { add(PUNJABI, 6); break }
            }
        }
        for ((tag, words) in NAMED) if (words.any(::has)) add(tag, 5)
        for ((tag, words) in PEOPLE) for (w in words) if (has(w)) add(tag, 2)

        val tags = HashSet<String>()
        // Highest score wins; a tie goes to the earlier language in INDIAN
        INDIAN.maxByOrNull { score[it] ?: 0 }?.takeIf { (score[it] ?: 0) > 0 }?.let { tags += it }

        val japanese = raw.any { it.code in 0x3040..0x30FF || it.code in 0x4E00..0x9FFF }
        if (japanese || ANIME_HINTS.any(::has)) tags += ANIME
        // No Indian language found: English (most of the rest), or a folder named "English …"
        // (Anime songs are mostly Japanese, so they're English only when they say so: "English cover")
        if (tags.none { it in INDIAN } && (has("english") || (ANIME !in tags && raw.none { it.code > 0x2000 && it.isLetter() }))) {
            tags += ENGLISH
        }
        if (folder.lowercase().contains("english")) {
            tags.removeAll(INDIAN.toSet()); tags += ENGLISH
        }
        return tags
    }

    /**
     * "a tamil song", "some tamil music", "tamil" -> "tamil"; null if the request isn't
     * just a language / kind of music.
     */
    fun requested(query: String): String? {
        val filler = setOf(
            "a", "an", "some", "any", "the", "my", "few", "song", "songs", "music", "musics", "track", "tracks",
            "hits", "only", "mp3", "mp3s", "number", "numbers", "melody", "melodies", "one",
        )
        val words = query.lowercase().replace(Regex("[^a-z ]"), " ").split(' ').filter { it.isNotBlank() && it !in filler }
        return if (words.size == 1) SPOKEN[words[0]] else null
    }
}
