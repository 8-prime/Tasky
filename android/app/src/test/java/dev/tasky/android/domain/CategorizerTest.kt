package dev.tasky.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {
    private fun assertKeys(expected: String?, vararg names: String) {
        for (name in names) assertEquals("\"$name\"", expected, Categorizer.keyFor(name))
    }

    @Test
    fun englishNames() {
        assertKeys("produce", "Apples", "tomatoes", "Cherry tomatoes", "Red onion", "berries", "Bell pepper", "peppers")
        assertKeys("dairy", "Milk", "Oat milk", "chocolate milk", "Greek yogurt", "Eggs", "Cream cheese", "butter")
        assertKeys("snacks", "Milk chocolate", "Salted peanuts", "Licorice")
        assertKeys("breakfast", "Peanut butter", "Honey", "Muesli")
        assertKeys("pasta_rice", "Basmati rice", "Spaghetti", "Noodles")
        assertKeys("condiments", "Olive oil", "Salt", "pepper", "Soy sauce")
        assertKeys("household", "Toilet paper", "Aluminium foil", "Dish soap")
        assertKeys("personal_care", "Toothpaste", "Hand cream", "Sun cream")
        assertKeys("frozen", "Frozen peas", "Ice cream", "Pizza")
        assertKeys("canned", "Canned tomatoes", "Chickpeas", "Tuna")
        assertKeys("drinks", "Orange juice", "Sparkling water", "Red wine")
        assertKeys("coffee_tea", "Green tea", "Coffee beans")
        assertKeys("pets", "Cat food")
    }

    @Test
    fun germanNamesAndCompounds() {
        assertKeys("produce", "Äpfel", "Tomaten", "Kartoffeln", "Bananen", "Rote Bete", "Eisbergsalat", "Frühlingszwiebeln")
        assertKeys("dairy", "Vollmilch", "Hafermilch", "Bio-Vollmilch", "Schlagsahne", "Eier", "Käse", "Kaese", "Kräuterbutter", "Crème fraîche")
        assertKeys("meat_fish", "Rinderhackfleisch", "Hähnchenbrustfilet", "Räucherlachs", "Kochschinken")
        assertKeys("bakery", "Vollkornbrot", "Brötchen", "Laugenbrezel")
        assertKeys("snacks", "Milchschokolade", "Butterkekse", "Schokoriegel", "Gummibärchen")
        assertKeys("breakfast", "Erdnussbutter", "Nuss-Nougat-Creme", "Haferflocken")
        assertKeys("pasta_rice", "Reis", "Milchreis", "Spätzle")
        assertKeys("frozen", "Eis", "Vanilleeis", "TK Erbsen", "Tiefkühlpizza", "Fischstäbchen")
        assertKeys("canned", "Dosentomaten", "Passierte Tomaten", "Tomatenmark", "Thunfisch", "Kichererbsen")
        assertKeys("condiments", "Olivenöl", "Meersalz", "Paprikapulver", "Gemüsebrühe", "Sojasoße")
        assertKeys("drinks", "Mineralwasser", "Apfelsaft", "Multivitaminsaft", "Glühwein", "Apfelschorle")
        assertKeys("coffee_tea", "Kaffee", "Pfefferminztee")
        assertKeys("baking", "Weizenmehl", "Puderzucker", "Backpulver", "Trockenhefe")
        assertKeys("household", "Toilettenpapier", "Spülmittel", "Müllbeutel", "Backpapier")
        assertKeys("personal_care", "Zahnpasta", "Duschgel", "Sonnencreme")
        assertKeys("baby", "Windeln")
        assertKeys("pets", "Katzenfutter")
    }

    @Test
    fun unknownNamesStayUncategorized() {
        assertKeys(null, "", "  ", "Thingamajig", "Geschenk für Oma", "123")
    }

    @Test
    fun everyKeyIsBuiltInAndKeywordsAreUnambiguous() {
        assertEquals(CategoryIndex.BUILTIN_KEYS.toSet(), Categorizer.KEYWORDS.keys)
        val seen = HashMap<String, String>()
        for ((key, words) in Categorizer.KEYWORDS) {
            for (w in words) {
                val n = Categorizer.normalize(w)
                val other = seen.put(n, key)
                assertTrue("\"$w\" is in both $other and $key", other == null || other == key)
            }
        }
    }

    @Test
    fun normalizes() {
        assertEquals("kaese", Categorizer.normalize("Käse"))
        assertEquals("creme fraiche", Categorizer.normalize("Crème fraîche"))
        assertEquals("nuss nougat creme", Categorizer.normalize("Nuss-Nougat-Creme"))
        assertEquals("sosse", Categorizer.normalize("Soße"))
    }
}
