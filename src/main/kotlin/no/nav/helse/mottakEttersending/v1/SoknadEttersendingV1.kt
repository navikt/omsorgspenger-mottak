package no.nav.helse.mottakEttersending.v1

import no.nav.helse.AktoerId
import no.nav.helse.SoknadId
import org.apache.commons.codec.binary.Base64
import org.json.JSONObject
import java.net.URI

private object JsonKeys {
    internal const val søker = "søker"
    internal const val aktørId = "aktørId"
    internal const val søknadId = "søknadId"
    internal const val fødselsnummer = "fødselsnummer"
    internal const val vedleggUrls = "vedleggUrls"
    internal const val vedlegg = "vedlegg"
    internal const val content = "content"
    internal const val contentType = "contentType"
    internal const val title = "title"
}

internal class SoknadEttersendingV1Incoming(json: String) {
    private val jsonObject = JSONObject(json)
    internal val vedlegg: List<Vedlegg>

    private fun hentVedlegg() : List<Vedlegg> {
        val vedlegg = mutableListOf<Vedlegg>()
        jsonObject.getJSONArray(JsonKeys.vedlegg).forEach {
            val vedleggJson = it as JSONObject
            vedlegg.add(
                Vedlegg(
                    content = Base64.decodeBase64(vedleggJson.getString(JsonKeys.content)),
                    contentType = vedleggJson.getString(JsonKeys.contentType),
                    title = vedleggJson.getString(JsonKeys.title)
                )
            )
        }
        return vedlegg.toList()
    }

    init {
        vedlegg = hentVedlegg()
        jsonObject.remove(JsonKeys.vedlegg)
    }

    internal val søkerAktørId = AktoerId(jsonObject.getJSONObject(JsonKeys.søker).getString(
        JsonKeys.aktørId
    ))


    internal fun medSoknadId(soknadId: SoknadId): SoknadEttersendingV1Incoming {
        jsonObject.put(JsonKeys.søknadId, soknadId.id)
        return this
    }

    internal fun medVedleggUrls(vedleggUrls: List<URI>) : SoknadEttersendingV1Incoming {
        jsonObject.put(JsonKeys.vedleggUrls, vedleggUrls)
        return this
    }

    internal fun somOutgoing() =
        SoknadEttersendingV1Outgoing(jsonObject)

}

internal class SoknadEttersendingV1Outgoing(internal val jsonObject: JSONObject) {
    internal val soknadId = SoknadId(jsonObject.getString(JsonKeys.søknadId))
    internal val vedleggUrls = hentVedleggUrls()

    private fun hentVedleggUrls() : List<URI> {
        val vedleggUrls = mutableListOf<URI>()
        jsonObject.getJSONArray(JsonKeys.vedleggUrls).forEach {
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