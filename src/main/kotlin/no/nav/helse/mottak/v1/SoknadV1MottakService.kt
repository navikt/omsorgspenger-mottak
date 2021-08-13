package no.nav.helse.mottak.v1

import no.nav.helse.CorrelationId
import no.nav.helse.Metadata
import no.nav.helse.SoknadId
import no.nav.helse.AktoerId
import no.nav.helse.dokument.Dokument
import no.nav.helse.dokument.DokumentGateway
import org.slf4j.LoggerFactory
import java.net.URI

internal class SoknadV1MottakService(
    private val dokumentGateway: DokumentGateway,
    private val soknadV1KafkaProducer: SoknadV1KafkaProducer
) {

    private companion object {
        private val logger = LoggerFactory.getLogger(SoknadV1MottakService::class.java)
    }

    internal suspend fun leggTilProsessering(
        soknadId: SoknadId,
        metadata: Metadata,
        soknad: SoknadV1Incoming
    ): SoknadId {
        val correlationId = CorrelationId(metadata.correlationId)

        logger.info("Lagrer legeerklæringer")
        val legeerklæringUrls = lagreVedleg(
            aktoerId = soknad.søkerAktørId,
            vedlegg = soknad.legeerklæring,
            correlationId = correlationId
        )

        val samværsavtaleUrls = when {
            soknad.samværsavtale.isNotEmpty() -> {
                logger.info("Lagrer samværsvtaler")
                lagreVedleg(
                    aktoerId = soknad.søkerAktørId,
                    vedlegg = soknad.samværsavtale,
                    correlationId = correlationId
                )
            }
            else -> listOf()
        }

        val outgoing = soknad
            .medLegeerklæringUrls(legeerklæringUrls)
            .medSamværsavtaleUrls(samværsavtaleUrls)
            .medSoknadId(soknadId)
            .somOutgoing()

        logger.info("Legger på kø")
        soknadV1KafkaProducer.produserKafkaMelding(
            metadata = metadata,
            søknad = outgoing
        )

        return soknadId
    }

    private suspend fun lagreVedleg(
        aktoerId: AktoerId,
        correlationId: CorrelationId,
        vedlegg: List<Vedlegg>
    ): List<URI> {
        logger.info("Lagrer ${vedlegg.size} vedlegg.")
        return dokumentGateway.lagreDokmenter(
            dokumenter = vedlegg.somDokumenter(),
            correlationId = correlationId,
            aktoerId = aktoerId
        )
    }
}

private fun List<Vedlegg>.somDokumenter() = map {
    Dokument(
        content = it.content,
        contentType = it.contentType,
        title = it.title
    )
}.toSet()