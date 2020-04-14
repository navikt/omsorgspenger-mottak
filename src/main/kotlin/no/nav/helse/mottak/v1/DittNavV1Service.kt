package no.nav.helse.mottak.v1

import no.nav.helse.SoknadId
import org.slf4j.LoggerFactory

internal class DittNavV1Service(
    private val soknadV1KafkaProducer: SoknadV1KafkaProducer
) {
    private companion object {
        private val logger = LoggerFactory.getLogger(SoknadV1MottakService::class.java)
    }

    fun sendSoknadMottattMeldingTilDittNav(
        dto: ProduceBeskjedDto,
        søkersNorskeIdent: String,
        soknadId: SoknadId
    ): String {

        soknadV1KafkaProducer.produceDittnavMelding(
            dto = dto,
            søkersNorskeIdent = søkersNorskeIdent,
            soknadId = soknadId
        )

        return "Kafkaproducer aktivert."
    }
}