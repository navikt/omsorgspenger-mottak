package no.nav.helse.mottak.v1

import no.nav.helse.SoknadId
import no.nav.helse.AktoerId
import org.apache.commons.codec.binary.Base64
import org.json.JSONObject
import java.net.URI

private object JsonKeys {
    internal const val legeerklæring = "legeerklæring"
    internal const val samværsavtale = "samværsavtale"
    internal const val søker = "søker"
    internal const val aktørId = "aktørId"
    internal const val søknadId = "søknadId"
    internal const val fødselsnummer = "fødselsnummer"
    internal const val content = "content"
    internal const val contentType = "content_type"
    internal const val title = "title"
}

internal class SoknadV1Incoming(json: String) {
    private val jsonObject = JSONObject(json)
    internal val søkerFødselsnummer: String
    internal val legeerklæring: List<Vedlegg>
    internal val samværsavtale: List<Vedlegg>

    private fun hentLegeerklæring(): List<Vedlegg> = vedleggsFilerTilJson(JsonKeys.legeerklæring).toList()

    private fun hentSamværsavtale(): List<Vedlegg> = vedleggsFilerTilJson(JsonKeys.samværsavtale).toList()

    private fun vedleggsFilerTilJson(jsonKey: String): MutableList<Vedlegg> {
        val vedleggsFiler: MutableList<Vedlegg> = mutableListOf()
        jsonObject.getJSONArray(jsonKey).forEach {
            val vedleggJson = it as JSONObject
            vedleggsFiler.add(
                Vedlegg(
                    content = Base64.decodeBase64(vedleggJson.getString(JsonKeys.content)),
                    contentType = vedleggJson.getString(JsonKeys.contentType),
                    title = vedleggJson.getString(JsonKeys.title)
                )
            )
        }
        return vedleggsFiler
    }

    init {
        søkerFødselsnummer = jsonObject.getJSONObject(JsonKeys.søker).getString(JsonKeys.fødselsnummer)
        legeerklæring = hentLegeerklæring()
        samværsavtale = hentSamværsavtale()
        jsonObject.remove(JsonKeys.legeerklæring)
        jsonObject.remove(JsonKeys.samværsavtale)
    }

    internal val søkerAktørId = AktoerId(jsonObject.getJSONObject(JsonKeys.søker).getString(JsonKeys.aktørId))

    internal fun medLegeerklæringUrls(vedleggUrls: List<URI>): SoknadV1Incoming {
        jsonObject.put(JsonKeys.legeerklæring, vedleggUrls)
        return this
    }

    internal fun medSamværsavtaleUrls(vedleggUrls: List<URI>): SoknadV1Incoming {
        jsonObject.put(JsonKeys.samværsavtale, vedleggUrls)
        return this
    }

    internal fun medSoknadId(soknadId: SoknadId): SoknadV1Incoming {
        jsonObject.put(JsonKeys.søknadId, soknadId.id)
        return this
    }

    internal fun somOutgoing() = SoknadV1Outgoing(jsonObject)

}

internal class SoknadV1Outgoing(internal val jsonObject: JSONObject) {
    internal val soknadId = SoknadId(jsonObject.getString(JsonKeys.søknadId))
    // TODO: Refaktorer slik at dette støtte både legeerklaring vedlegg og samversavtale vedlegg.
    internal val legeerklæringUrls = hentVedleggUrls(JsonKeys.legeerklæring)
    internal val samværrsavtaleUrls = hentVedleggUrls(JsonKeys.samværsavtale)

    private fun hentVedleggUrls(jsonkey: String): List<URI> {
        val vedleggUrls = mutableListOf<URI>()
        jsonObject.getJSONArray(jsonkey).forEach {
            vedleggUrls.add(URI(it as String))
        }
        return vedleggUrls.toList()
    }
}

data class Vedlegg(
    val content: ByteArray,
    val contentType: String,
    val title: String
)