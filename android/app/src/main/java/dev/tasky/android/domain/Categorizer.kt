package dev.tasky.android.domain

import java.text.Normalizer

/**
 * Guesses the built-in category of an item from its name ("Hafermilch" → `dairy`), in English
 * and German. Only used when nobody picked a category by hand, so a wrong guess costs one tap.
 *
 * Rules, first match wins:
 * 1. A word that changes where the item is kept ("frozen", "TK", "canned") decides.
 * 2. The longest known phrase in the name ("peanut butter", "passierte tomaten").
 * 3. Words from last to first, because the last word usually says what the thing is
 *    ("chocolate milk" is milk, "milk chocolate" is chocolate). A word matches a keyword exactly,
 *    or ends with one, which covers German compounds ("Vollmilch", "Rinderhackfleisch").
 *    Simple plurals are tried too ("tomatoes", "Kartoffeln").
 */
object Categorizer {
    /** Built-in key for [name], or null if nothing matches. */
    fun keyFor(name: String): String? {
        val words = normalize(name).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        modifierKey(words)?.let { return it }
        val text = " " + words.joinToString(" ") + " "
        phrases.firstOrNull { (phrase, _) -> text.contains(" $phrase ") }?.let { return it.second }
        for (word in words.asReversed()) {
            wordKey(word)?.let { return it }
        }
        return null
    }

    /** Lowercase ASCII words: umlauts spelled out (ä → ae, ß → ss), accents dropped, punctuation as spaces. */
    fun normalize(text: String): String {
        val spelled = text.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        val plain = Normalizer.normalize(spelled, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return plain.replace(Regex("[^a-z]+"), " ").trim()
    }

    private fun modifierKey(words: List<String>): String? {
        for (w in words) {
            if (w in FROZEN_WORDS || w.startsWith("tiefkuehl")) return "frozen"
            if (w in CANNED_WORDS || w.startsWith("dosen")) return "canned"
        }
        return null
    }

    private fun wordKey(word: String): String? {
        for (form in forms(word)) {
            exact[form]?.let { return it }
        }
        for (form in forms(word)) {
            suffixes.firstOrNull { (k, _) -> form.length > k.length && form.endsWith(k) }?.let { return it.second }
        }
        return null
    }

    /** The word itself, then plural-stripped variants (English -s/-es/-ies, German -n/-en/-e). */
    private fun forms(word: String): List<String> = buildList {
        add(word)
        if (word.length > 4 && word.endsWith("ies")) add(word.dropLast(3) + "y")
        if (word.length > 3 && word.endsWith("es")) add(word.dropLast(2))
        if (word.length > 3 && word.endsWith("s")) add(word.dropLast(1))
        if (word.length > 4 && word.endsWith("en")) add(word.dropLast(2))
        if (word.length > 3 && word.endsWith("n")) add(word.dropLast(1))
        if (word.length > 3 && word.endsWith("e")) add(word.dropLast(1))
    }.distinct()

    private val FROZEN_WORDS = setOf("frozen", "tk", "tiefgekuehlt", "gefroren")
    private val CANNED_WORDS = setOf("canned", "tinned")

    /** Built-in key → keywords, written naturally; normalized on load. */
    internal val KEYWORDS: Map<String, List<String>> = mapOf(
        "produce" to listOf(
            "fruit", "vegetables", "veggies", "apple", "banana", "orange", "lemon", "lime", "pear", "grape", "grapes",
            "grapefruit", "peach", "nectarine", "plum", "apricot", "cherry", "strawberry", "raspberry", "blueberry",
            "blackberry", "berry", "kiwi", "mango", "pineapple", "melon", "watermelon", "avocado", "pomegranate", "fig",
            "tomato", "cherry tomatoes", "potato", "sweet potato", "onion", "red onion", "shallot", "garlic", "ginger",
            "carrot", "cucumber", "zucchini", "courgette", "eggplant", "aubergine", "peppers", "bell pepper",
            "red pepper", "green pepper", "yellow pepper", "chili", "chilli", "lettuce", "salad", "rocket", "arugula",
            "spinach", "kale", "cabbage", "broccoli", "cauliflower", "celery", "leek", "asparagus", "mushroom",
            "radish", "beetroot", "pumpkin", "squash", "fennel", "herbs", "parsley", "basil", "coriander", "cilantro",
            "mint", "dill", "chives", "spring onion", "spring onions", "scallion", "sprouts", "peas",
            "obst", "gemüse", "apfel", "äpfel", "banane", "zitrone", "limette", "birne", "traube", "weintraube",
            "pfirsich", "nektarine", "pflaume", "zwetschge", "aprikose", "kirsche", "erdbeere", "himbeere",
            "heidelbeere", "blaubeere", "brombeere", "johannisbeere", "beere", "ananas", "melone", "wassermelone",
            "granatapfel", "feige", "tomate", "kirschtomate", "kartoffel", "süßkartoffel", "zwiebel", "schalotte",
            "knoblauch", "ingwer", "karotte", "möhre", "mohrrübe", "gurke", "paprika", "salat", "kopfsalat",
            "eisbergsalat", "feldsalat", "rucola", "spinat", "grünkohl", "kohl", "weißkohl", "rotkohl", "wirsing",
            "brokkoli", "blumenkohl", "sellerie", "lauch", "porree", "spargel", "pilze", "champignon", "champignons",
            "radieschen", "rote bete", "kürbis", "fenchel", "kräuter", "petersilie", "basilikum", "koriander", "minze",
            "schnittlauch", "frühlingszwiebel", "lauchzwiebel", "kohlrabi", "rhabarber", "erbsen",
        ),
        "bakery" to listOf(
            "bread", "loaf", "baguette", "roll", "rolls", "bun", "buns", "bagel", "croissant", "toast", "sourdough",
            "ciabatta", "pita", "brioche", "muffin", "donut", "doughnut", "cake",
            "brot", "brötchen", "semmel", "schrippe", "weckle", "toastbrot", "brezel", "breze", "laugenbrezel",
            "laugenstange", "kuchen", "vollkornbrot", "knäckebrot", "fladenbrot", "croissants", "berliner",
        ),
        "meat_fish" to listOf(
            "meat", "beef", "steak", "pork", "chicken", "chicken breast", "turkey", "lamb", "veal", "mince",
            "minced meat", "ground beef", "bacon", "ham", "sausage", "salami", "prosciutto", "chorizo", "schnitzel",
            "fish", "salmon", "cod", "shrimp", "prawns", "mussels", "trout", "herring", "burger patties",
            "fleisch", "hackfleisch", "hack", "gehacktes", "rind", "rindfleisch", "schwein", "schweinefleisch",
            "hähnchen", "hühnchen", "hähnchenbrust", "huhn", "pute", "putenbrust", "lamm", "kalb", "speck",
            "schinken", "wurst", "würstchen", "bratwurst", "aufschnitt", "leberwurst", "fisch", "lachs", "kabeljau",
            "garnelen", "forelle", "gulasch", "frikadellen", "hering", "matjes", "filet",
        ),
        "dairy" to listOf(
            "milk", "butter", "cheese", "cream", "yogurt", "yoghurt", "quark", "eggs", "egg", "sour cream",
            "cream cheese", "mozzarella", "parmesan", "cheddar", "feta", "gouda", "brie", "camembert", "ricotta",
            "mascarpone", "cottage cheese", "kefir", "buttermilk", "margarine", "creme fraiche", "whipped cream",
            "skyr", "halloumi",
            "milch", "käse", "sahne", "schlagsahne", "joghurt", "jogurt", "eier", "ei", "schmand", "frischkäse",
            "emmentaler", "bergkäse", "hüttenkäse", "buttermilch", "crème fraîche", "saure sahne", "schmelzkäse",
            "reibekäse", "kochsahne",
        ),
        "chilled" to listOf(
            "tofu", "tempeh", "hummus", "ready meal", "fresh pasta", "pudding", "tzatziki", "guacamole", "dip",
            "falafel", "deli",
            "fertiggericht", "nudelsalat", "kartoffelsalat", "feinkost", "frische nudeln", "maultaschen",
            "tortellini", "ravioli",
        ),
        "breakfast" to listOf(
            "cereal", "cereals", "muesli", "granola", "cornflakes", "oats", "oatmeal", "porridge", "jam", "jelly",
            "marmalade", "honey", "peanut butter", "nutella", "spread", "maple syrup",
            "müsli", "haferflocken", "marmelade", "konfitüre", "honig", "erdnussbutter", "nuss nougat creme",
            "aufstrich", "brotaufstrich", "zwieback", "cerealien", "ahornsirup",
        ),
        "coffee_tea" to listOf(
            "coffee", "espresso", "coffee beans", "tea", "green tea", "cocoa", "hot chocolate", "coffee pads",
            "coffee capsules",
            "kaffee", "kaffeebohnen", "kaffeepads", "kapseln", "tee", "kakao", "kräutertee", "früchtetee",
            "schwarztee", "grüntee",
        ),
        "pasta_rice" to listOf(
            "pasta", "spaghetti", "penne", "fusilli", "macaroni", "lasagne", "lasagna", "noodles", "rice", "basmati",
            "couscous", "quinoa", "bulgur", "polenta", "lentils", "gnocchi",
            "nudeln", "makkaroni", "lasagneplatten", "reis", "basmatireis", "linsen", "spätzle", "tagliatelle",
        ),
        "baking" to listOf(
            "flour", "sugar", "brown sugar", "icing sugar", "powdered sugar", "baking powder", "baking soda", "yeast",
            "vanilla sugar", "vanilla extract", "cocoa powder", "chocolate chips", "cornstarch", "starch", "gelatin",
            "sprinkles", "ground almonds",
            "mehl", "zucker", "puderzucker", "rohrzucker", "backpulver", "natron", "hefe", "trockenhefe",
            "vanillezucker", "vanillinzucker", "speisestärke", "stärke", "gelatine", "kakaopulver", "streusel",
            "gemahlene mandeln", "backmischung",
        ),
        "canned" to listOf(
            "canned tomatoes", "chopped tomatoes", "tomato paste", "tomato puree", "passata", "beans",
            "kidney beans", "baked beans", "chickpeas", "sweetcorn", "sweet corn", "corn", "tuna", "soup", "olives",
            "pickles", "gherkins", "sauerkraut",
            "gehackte tomaten", "tomatenmark", "passierte tomaten", "bohnen", "kidneybohnen", "kichererbsen", "mais",
            "thunfisch", "suppe", "oliven", "gewürzgurken", "essiggurken", "konserve", "konserven", "dose",
        ),
        "condiments" to listOf(
            "salt", "pepper", "black pepper", "spices", "spice", "oil", "olive oil", "sunflower oil", "vinegar",
            "balsamic", "ketchup", "mustard", "mayonnaise", "mayo", "soy sauce", "sauce", "pesto", "stock", "broth",
            "bouillon", "curry", "paprika powder", "cinnamon", "oregano", "cumin", "nutmeg", "chili flakes",
            "tabasco", "sriracha", "dressing", "gravy",
            "salz", "pfeffer", "gewürz", "gewürze", "öl", "olivenöl", "sonnenblumenöl", "rapsöl", "essig",
            "balsamico", "senf", "sojasoße", "sojasauce", "soße", "brühe", "gemüsebrühe", "fond", "paprikapulver",
            "zimt", "kreuzkümmel", "muskat", "chiliflocken",
        ),
        "international" to listOf(
            "tortilla", "tortillas", "wraps", "taco", "tacos", "salsa", "coconut milk", "curry paste", "fish sauce",
            "rice noodles", "sushi rice", "nori", "miso", "kimchi", "wasabi", "tahini", "naan",
            "kokosmilch", "currypaste", "fischsoße", "reisnudeln", "sushireis", "mie nudeln", "mienudeln",
            "glasnudeln", "tahin",
        ),
        "snacks" to listOf(
            "chips", "crisps", "chocolate", "candy", "sweets", "gummy bears", "cookies", "biscuits", "crackers",
            "popcorn", "nuts", "peanuts", "cashews", "almonds", "licorice", "liquorice", "nachos", "muesli bar",
            "cereal bar", "chocolate bar", "pretzel sticks",
            "schokolade", "süßigkeiten", "gummibärchen", "fruchtgummi", "kekse", "plätzchen", "salzstangen",
            "nüsse", "erdnüsse", "mandeln", "lakritz", "riegel", "müsliriegel", "bonbons", "kaugummi", "pralinen",
            "waffeln",
        ),
        "drinks" to listOf(
            "water", "sparkling water", "mineral water", "juice", "orange juice", "soda", "cola", "lemonade", "beer",
            "wine", "red wine", "white wine", "prosecco", "champagne", "sparkling wine", "vodka", "gin", "rum",
            "whisky", "whiskey", "tonic", "smoothie", "iced tea", "energy drink",
            "wasser", "mineralwasser", "sprudel", "saft", "orangensaft", "apfelsaft", "schorle", "apfelschorle",
            "limo", "limonade", "bier", "wein", "rotwein", "weißwein", "sekt", "wodka", "eistee", "mate",
        ),
        "frozen" to listOf(
            "ice cream", "ice cubes", "fish fingers", "fries", "french fries", "frozen pizza", "pizza",
            "eis", "speiseeis", "eiscreme", "fischstäbchen", "pommes", "eiswürfel", "kroketten", "rahmspinat",
        ),
        "household" to listOf(
            "toilet paper", "toilet roll", "kitchen roll", "paper towels", "tissues", "napkins", "dish soap",
            "washing up liquid", "dishwasher tabs", "dishwasher tablets", "detergent", "laundry detergent",
            "fabric softener", "cleaner", "sponge", "sponges", "trash bags", "bin bags", "garbage bags",
            "aluminium foil", "aluminum foil", "foil", "cling film", "baking paper", "freezer bags", "batteries",
            "light bulb", "candles", "matches",
            "toilettenpapier", "klopapier", "küchenrolle", "taschentücher", "servietten", "spülmittel",
            "spülmaschinentabs", "waschmittel", "weichspüler", "reiniger", "putzmittel", "schwamm", "schwämme",
            "müllbeutel", "müllsäcke", "alufolie", "frischhaltefolie", "backpapier", "gefrierbeutel", "batterien",
            "glühbirne", "kerzen", "streichhölzer", "entkalker",
        ),
        "personal_care" to listOf(
            "shampoo", "conditioner", "shower gel", "soap", "toothpaste", "toothbrush", "deodorant", "deo", "razor",
            "razors", "sunscreen", "sun cream", "lotion", "body lotion", "hand cream", "face cream", "cotton pads",
            "cotton buds", "tampons", "sanitary pads", "plasters", "band aids", "painkillers", "vitamins", "floss",
            "mouthwash", "makeup", "serum",
            "spülung", "duschgel", "seife", "zahnpasta", "zahncreme", "zahnbürste", "rasierer", "rasierklingen",
            "sonnencreme", "handcreme", "gesichtscreme", "wattepads", "wattestäbchen", "binden", "pflaster",
            "schmerztabletten", "vitamine", "zahnseide", "mundspülung",
        ),
        "baby" to listOf(
            "diapers", "nappies", "baby food", "baby wipes", "wet wipes", "formula",
            "windeln", "babynahrung", "feuchttücher", "babybrei",
        ),
        "pets" to listOf(
            "cat food", "dog food", "cat litter", "pet food", "dog treats",
            "katzenfutter", "hundefutter", "katzenstreu", "futter", "tierfutter", "leckerli",
        ),
    )

    private val exact = HashMap<String, String>()
    private val phrases: List<Pair<String, String>>
    private val suffixes: List<Pair<String, String>>

    init {
        val phraseList = ArrayList<Pair<String, String>>()
        for ((key, words) in KEYWORDS) {
            for (w in words) {
                val n = normalize(w)
                if (' ' in n) phraseList += n to key else exact.putIfAbsent(n, key)
            }
        }
        phrases = phraseList.sortedByDescending { it.first.length }
        // Compound endings need 3+ letters; shorter keywords ("ei") only match whole words.
        suffixes = exact.entries.filter { it.key.length >= 3 }.map { it.key to it.value }.sortedByDescending { it.first.length }
    }
}
