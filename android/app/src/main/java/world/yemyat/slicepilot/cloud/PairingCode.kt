package world.yemyat.slicepilot.cloud

import org.json.JSONObject

/**
 * What the cloud pairs a printer with. Matrix's own model — `PincodeDetail` in
 * `elegoo/common/model/rtm_elegoo/pincode_detail.dart` — holds exactly four values, and its
 * `toJson` names them one by one:
 *
 * ```
 * "serialNo"  "pcode"  "pincode"  "expiresIn"
 * ```
 *
 * So the serial and the pcode are **not** the same thing, and the older one-field version of this
 * screen was wrong twice over: sending the typed PIN as `pcode` answered `pincode invalid`, and
 * sending the serial there answered `pcode not match serialno` — the cloud was comparing the two
 * fields it keeps apart. Every value here therefore comes from the cloud's own answer
 * (`device/pincode/detail`) or from the QR the printer's screen shows, never from a guess about
 * which of them a person meant.
 */
data class PairingValues(
    /** The product code the cloud files the printer under (`pcode`). */
    val pcode: String = "",
    /** The printer's serial number (`serialNo`). */
    val serialNo: String = "",
    /** The short PIN the printer's settings show, and the only thing a person ever types (`pincode`). */
    val pincode: String = "",
    /** How long the PIN stays valid, in minutes, when the cloud says (`expiresIn`). */
    val expiresInMinutes: Int? = null,
) {
    /** `check-pincode` needs a pcode and a pincode; a QR or an API answer supplies both. */
    val isComplete: Boolean
        get() = pcode.isNotBlank() && pincode.isNotBlank()

    /** What the rest of the app calls a printer: its serial, falling back to the product code. */
    val printerId: String
        get() = serialNo.ifBlank { pcode }

    /** Keep whatever we already had where [other] is silent — an answer sometimes omits a field. */
    fun orElse(other: PairingValues): PairingValues =
        PairingValues(
            pcode = pcode.ifBlank { other.pcode },
            serialNo = serialNo.ifBlank { other.serialNo },
            pincode = pincode.ifBlank { other.pincode },
            expiresInMinutes = expiresInMinutes ?: other.expiresInMinutes,
        )
}

/** Reads [PairingValues] out of every shape a pairing code arrives in: typed, pasted, or scanned. */
object PairingCode {
    /** Key names the cloud and firmware use for the PIN itself. */
    private val PIN_KEYS = listOf("pincode", "pin_code", "pin", "code")

    /** Key names for the printer's serial — deliberately not `pcode`, which is its own value. */
    private val SERIAL_KEYS = listOf("serialNo", "serial_no", "serialno", "serial", "sn")

    /** Key names for the product code, which the older QR payload writes in place of the serial. */
    private val PCODE_KEYS = listOf("pcode", "p_code", "productCode", "product_code")

    /** Anything that could be one of the three values: code-safe text and nothing else. */
    private val TOKEN = Regex("^[A-Za-z0-9_-]{4,64}$")

    /** What a PIN looks like when typed: short, uppercase, no dashes. */
    private const val MAX_PIN_LENGTH = 8

    private val PIN_SHAPE = Regex("^[A-Z0-9]{4,$MAX_PIN_LENGTH}$")

    /** The PIN inside a keyed payload (`pincode=…`, `PinCode: …`, a JSON body). */
    fun pincodeFrom(raw: String): String? = pick(raw, PIN_KEYS)

    /** The serial inside a keyed payload (`serialNo=…`) — never the pcode. */
    fun serialFrom(raw: String): String? = pick(raw, SERIAL_KEYS)

    /** The product code inside a keyed payload (`pcode=…`). */
    fun pcodeFrom(raw: String): String? = pick(raw, PCODE_KEYS)

    /** A serial typed by hand — keeps the dashes a serial is written with, rejects prose. */
    fun normalizeSerial(text: String): String? = serialFrom(text) ?: text.trim().takeIf { TOKEN.matches(it) && !it.matches(PIN_SHAPE) }

    /** A product code typed or scanned — the older QR writes the printer id here, hence the same rules. */
    fun normalizePcode(text: String): String? = pcodeFrom(text) ?: text.trim().takeIf { TOKEN.matches(it) }

    /** A PIN typed by hand — uppercased, so a paste's stray space or case is not fatal. */
    fun normalizePincode(text: String): String? = pincodeFrom(text) ?: text.trim().uppercase().takeIf { PIN_SHAPE.matches(it) }

    /**
     * Everything a scanned QR or a pasted payload carries, in whichever of the shapes firmware
     * writes it in: `pcode=…&model=…&pincode=…`, a portal URL's query, or a cloud JSON body.
     * A lone token is sorted by shape — a short one is the PIN, a long or dashed one the serial —
     * because that is the difference between the printer's label and its settings screen.
     */
    fun fromQr(raw: String): PairingValues? {
        val payload = raw.trim()
        if (payload.isEmpty()) return null
        val keyed =
            PairingValues(
                pcode = pcodeFrom(payload).orEmpty(),
                serialNo = serialFrom(payload).orEmpty(),
                pincode = pincodeFrom(payload).orEmpty(),
            )
        if (keyed.pcode.isNotBlank() || keyed.serialNo.isNotBlank() || keyed.pincode.isNotBlank()) return keyed
        if (!TOKEN.matches(payload)) return null
        val upper = payload.uppercase()
        return when {
            payload.contains('-') || payload.length > MAX_PIN_LENGTH -> keyed.copy(serialNo = payload)
            PIN_SHAPE.matches(upper) -> keyed.copy(pincode = upper)
            else -> null
        }
    }

    /** A typed PIN, plus the pcode a person may have typed next to it (the cloud fills the rest). */
    fun typed(
        pincode: String,
        pcode: String = "",
    ): PairingValues? {
        val pin = normalizePincode(pincode) ?: return null
        return PairingValues(pcode = pcode.trim(), pincode = pin)
    }

    /**
     * The cloud's own answer for a PIN (`device/pincode/detail` →
     * `{code, data:{serialNo, pcode, pincode, expiresIn}}`), read field by field the way
     * `PincodeDetail.fromJson` does. Anything the answer leaves out is taken from [fallback],
     * so a check response never erases what the detail lookup already returned.
     */
    fun fromApi(
        json: JSONObject,
        fallback: PairingValues = PairingValues(),
    ): PairingValues {
        val data = json.optJSONObject("data") ?: return fallback
        val expiresIn =
            when (val raw = data.opt("expiresIn")) {
                is Int -> raw
                is Long -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
        return PairingValues(
            pcode = data.optString("pcode", "").trim().ifBlank { fallback.pcode },
            serialNo = data.optString("serialNo", "").trim().ifBlank { fallback.serialNo },
            pincode = data.optString("pincode", "").trim().ifBlank { fallback.pincode },
            expiresInMinutes = expiresIn ?: fallback.expiresInMinutes,
        )
    }

    /**
     * Read one keyed value out of a JSON body or a `key=value` list — a bare token with no key is
     * the callers' job to sort by shape, because only they know which field they are after.
     */
    private fun pick(
        raw: String,
        keys: List<String>,
    ): String? {
        val payload = raw.trim()
        if (payload.isEmpty()) return null
        if (payload.startsWith("{")) {
            val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
            val objects = listOf(root, root.opt("data") as? JSONObject)
            for (obj in objects.filterNotNull()) {
                for (key in keys) {
                    val value = obj.optString(key, "").trim()
                    if (TOKEN.matches(value)) return value
                }
            }
        }
        // `https://np.elegoo.com/device?pcode=AB12&model=X` → its query; a plain list → its pairs.
        val pairs =
            when {
                payload.contains("://") -> payload.substringAfter('?', payload).split('&', ';', ',')
                else -> payload.split('&', ';', ',')
            }
        for (pair in pairs) {
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2) continue
            val key = parts[0].trim('{', '}', '"', '\'').lowercase()
            val value = parts[1].trim().trim('"', '\'')
            if (keys.contains(key) && TOKEN.matches(value)) return value
        }
        return null
    }
}
