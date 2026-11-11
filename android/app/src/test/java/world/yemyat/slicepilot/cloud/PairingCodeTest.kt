package world.yemyat.slicepilot.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud wants a pcode and a pincode, and keeps them apart: the single-field version sent the
 * PIN in the pcode slot and got back `pincode invalid`, the two-field version sent the serial there
 * and got back `pcode not match serialno`. So the parser gets tests for every shape the values
 * arrive in, plus the cloud's own answer shape.
 */
class PairingCodeTest {
    private val serial = "203100042-0000-091212406613"
    private val pcode = "CF10108003"
    private val pin = "ZCCFYC"

    @Test
    fun typedPincodeIsUpperedAndKept() {
        assertEquals(pin, PairingCode.normalizePincode("zccfyc"))
        assertEquals(pin, PairingCode.normalizePincode(" $pin "))
        assertNull("prose is not a PIN", PairingCode.normalizePincode("please pair"))
        assertNull("a serial is not a PIN", PairingCode.normalizePincode(serial))
    }

    @Test
    fun typedSerialKeepsItsDashes() {
        assertEquals(serial, PairingCode.normalizeSerial(serial))
        assertEquals(serial, PairingCode.normalizeSerial(" $serial "))
    }

    @Test
    fun pcodeAndSerialAreReadAsSeparateValues() {
        assertEquals(pcode, requireNotNull(PairingCode.pcodeFrom("pcode=$pcode&serialNo=$serial")))
        assertEquals(serial, requireNotNull(PairingCode.serialFrom("pcode=$pcode&serialNo=$serial")))
        assertEquals(pcode, PairingCode.normalizePcode(" $pcode "))
    }

    @Test
    fun qrKeyValueFillsEveryHalfItCarries() {
        val values = requireNotNull(PairingCode.fromQr("pcode=$serial&model=CF10108003&pincode=$pin"))
        assertEquals(serial, values.pcode)
        assertEquals(pin, values.pincode)
        assertTrue("a pcode and a pincode is enough to check", values.isComplete)
        assertEquals(serial, values.printerId)
    }

    @Test
    fun scannedQrWithSerialAndPcodeKeepsBoth() {
        val values = requireNotNull(PairingCode.fromQr("pcode=$pcode&serialNo=$serial&pincode=$pin"))
        assertEquals(pcode, values.pcode)
        assertEquals(serial, values.serialNo)
        assertEquals(pin, values.pincode)
    }

    @Test
    fun qrWithOnlyAPcodeLeavesThePinEmpty() {
        val values = requireNotNull(PairingCode.fromQr("pcode=$pcode&model=CF10108003"))
        assertEquals(pcode, values.pcode)
        assertEquals("", values.pincode)
    }

    @Test
    fun portalUrlIsReadForItsQuery() {
        val values = requireNotNull(PairingCode.fromQr("https://np.elegoo.com/device?pcode=$pcode&model=CF10108003"))
        assertEquals(pcode, values.pcode)
    }

    @Test
    fun jsonEnvelopeIsReadToo() {
        val raw = """{"code":0,"data":{"pcode":"$pcode","serialNo":"$serial","pincode":"$pin"}}"""
        val values = requireNotNull(PairingCode.fromQr(raw))
        assertEquals(pcode, values.pcode)
        assertEquals(serial, values.serialNo)
        assertEquals(pin, values.pincode)
    }

    @Test
    fun loneTokenIsSortedByShape() {
        assertEquals(serial, requireNotNull(PairingCode.fromQr(serial)).serialNo)
        assertEquals(pin, requireNotNull(PairingCode.fromQr(pin)).pincode)
        assertNull("an unparseable payload pairs nothing", PairingCode.fromQr("hello world"))
    }

    @Test
    fun cloudAnswerIsReadFieldByField() {
        val json = JSONObject("""{"code":0,"data":{"pcode":"$pcode","serialNo":"$serial","pincode":"$pin","expiresIn":30}}""")
        val values = PairingCode.fromApi(json)
        assertEquals(pcode, values.pcode)
        assertEquals(serial, values.serialNo)
        assertEquals(pin, values.pincode)
        assertEquals(30, values.expiresInMinutes)
    }

    @Test
    fun cloudAnswerKeepsWhatItOmitsAndNeverInventsASerial() {
        val known = PairingValues(pcode = pcode, serialNo = serial, pincode = pin, expiresInMinutes = 5)
        val partial = PairingCode.fromApi(JSONObject("""{"code":0,"data":{"pincode":"${pin.lowercase()}"}}"""), known)
        assertEquals(pcode, partial.pcode)
        assertEquals(serial, partial.serialNo)
        assertEquals(pin.lowercase(), partial.pincode)
        assertEquals(5, partial.expiresInMinutes)
        assertEquals(
            "an answer with no data keeps the fallback",
            known,
            PairingCode.fromApi(JSONObject("""{"code":0}"""), known),
        )
    }
}
