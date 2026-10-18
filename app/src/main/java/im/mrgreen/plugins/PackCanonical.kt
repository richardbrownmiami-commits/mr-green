package im.mrgreen.plugins

import org.json.JSONArray
import org.json.JSONObject

/** Signature format: sorted UTF-16 keys, compact UTF-8, integer numbers, no escaped '/'. */
internal object PackCanonical {
    fun render(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(separator = ",", prefix = "{", postfix = "}") { quote(it) + ":" + render(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") { render(value.get(it)) }
        is String -> quote(value)
        is Number -> { require(value.toDouble().isFinite() && value.toDouble() == value.toLong().toDouble()); value.toLong().toString() }
        JSONObject.NULL -> "null"
        is Boolean -> value.toString()
        else -> error("Unsupported signed JSON value")
    }
    private fun quote(value: String): String = JSONObject.quote(value).replace("\\/", "/")
}
