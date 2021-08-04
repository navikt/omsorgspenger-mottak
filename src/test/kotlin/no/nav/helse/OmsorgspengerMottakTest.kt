package no.nav.helse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.typesafe.config.ConfigFactory
import io.ktor.config.ApplicationConfig
import io.ktor.config.HoconApplicationConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.stop
import io.ktor.server.testing.TestApplicationEngine
import io.ktor.server.testing.createTestEnvironment
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import no.nav.common.KafkaEnvironment
import no.nav.helse.dusseldorf.ktor.core.fromResources
import no.nav.helse.dusseldorf.ktor.jackson.dusseldorfConfigured
import no.nav.helse.dusseldorf.testsupport.jws.Azure
import no.nav.helse.dusseldorf.testsupport.wiremock.WireMockBuilder
import no.nav.helse.kafka.Topics
import no.nav.helse.mottak.v1.*
import org.apache.commons.codec.binary.Base64
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.BeforeClass
import org.skyscreamer.jsonassert.JSONAssert
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OmsorgspengerMottakTest {

    private companion object {
        private val logger: Logger = LoggerFactory.getLogger(OmsorgspengerMottakTest::class.java)

        // Se https://github.com/navikt/dusseldorf-ktor#f%C3%B8dselsnummer
        private val gyldigFodselsnummerA = "02119970078"
        private val gyldigFodselsnummerB = "19066672169"
        private val gyldigFodselsnummerC = "20037473937"
        private val dNummerA = "55125314561"

        private val wireMockServer: WireMockServer = WireMockBuilder()
            .withAzureSupport()
            .build()
            .stubK9DokumentHealth()
            .stubLagreDokument()
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerA, "1234561")
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerB, "1234562")
            .stubAktoerRegisterGetAktoerId(gyldigFodselsnummerC, "1234563")
            .stubAktoerRegisterGetAktoerId(dNummerA, "1234564")


        private val kafkaEnvironment = KafkaWrapper.bootstrap()
        private val kafkaTestConsumer = kafkaEnvironment.testConsumer()
        private val objectMapper = jacksonObjectMapper().dusseldorfConfigured()

        private val authorizedAccessToken = Azure.V1_0.generateJwt(clientId = "omsorgspenger-api", audience = "omsorgspenger-mottak")
        private val unAauthorizedAccessToken = Azure.V2_0.generateJwt(clientId = "ikke-authorized-client", audience = "omsorgspenger-mottak", accessAsApplication = false)

        private var engine = newEngine(kafkaEnvironment)

        private fun getConfig(kafkaEnvironment: KafkaEnvironment) : ApplicationConfig {
            val fileConfig = ConfigFactory.load()
            val testConfig = ConfigFactory.parseMap(TestConfiguration.asMap(
                wireMockServer = wireMockServer,
                kafkaEnvironment = kafkaEnvironment,
                omsorgspengerMottakAzureClientId = "omsorgspenger-mottak"
            ))
            val mergedConfig = testConfig.withFallback(fileConfig)
            return HoconApplicationConfig(mergedConfig)
        }

        private fun newEngine(kafkaEnvironment: KafkaEnvironment) = TestApplicationEngine(createTestEnvironment {
            config = getConfig(kafkaEnvironment)
        })

        @BeforeClass
        @JvmStatic
        fun buildUp() {
            logger.info("Building up")
            engine.start(wait = true)
            logger.info("Buildup complete")
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            logger.info("Tearing down")
            wireMockServer.stop()
            engine.stop(5, 60, TimeUnit.SECONDS)
            kafkaEnvironment.tearDown()
            logger.info("Tear down complete")
        }
    }

    @Test
    fun `test isready, isalive, health og metrics`() {
        with(engine) {
            handleRequest(HttpMethod.Get, "/isready") {}.apply {
                assertEquals(HttpStatusCode.OK, response.status())
                handleRequest(HttpMethod.Get, "/isalive") {}.apply {
                    assertEquals(HttpStatusCode.OK, response.status())
                    handleRequest(HttpMethod.Get, "/metrics") {}.apply {
                        assertEquals(HttpStatusCode.OK, response.status())
                        handleRequest(HttpMethod.Get, "/health") {}.apply {
                            assertEquals(HttpStatusCode.OK, response.status())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `Gyldig søknad blir lagt til prosessering`() {
        gyldigSoknadBlirLagtTilProsessering(Azure.V1_0.generateJwt(clientId = "omsorgspenger-api", audience = "omsorgspenger-mottak", roles = setOf("access_as_application")))
        gyldigSoknadBlirLagtTilProsessering(Azure.V2_0.generateJwt(clientId = "omsorgspenger-api", audience = "omsorgspenger-mottak", roles = setOf("access_as_application")))
    }

    private fun gyldigSoknadBlirLagtTilProsessering(accessToken: String) {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        val soknadId = requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.Accepted,
            expectedResponse = null,
            accessToken = accessToken,
            path = "/v1/soknad"
        )

        val sendtTilProsessering = hentSoknadSendtTilProsessering(soknadId)
        verifiserSoknadLagtTilProsessering(
            incomingJsonString = soknad,
            outgoingJsonObject = sendtTilProsessering
        )
    }

    @Test
    fun `Gyldig søknad fra D-nummer blir lagt til prosessering`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = dNummerA
        )

        val soknadId = requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.Accepted,
            expectedResponse = null,
            path = "/v1/soknad"
        )

        val sendtTilProsessering  = hentSoknadSendtTilProsessering(soknadId)
        verifiserSoknadLagtTilProsessering(
            incomingJsonString = soknad,
            outgoingJsonObject = sendtTilProsessering
        )
    }

    @Test
    fun `Request fra ikke autorisert system feiler`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.Forbidden,
            expectedResponse = """
            {
                "type": "/problem-details/unauthorized",
                "title": "unauthorized",
                "status": 403,
                "detail": "Requesten inneholder ikke tilstrekkelige tilganger.",
                "instance": "about:blank"
            }
            """.trimIndent(),
            accessToken = unAauthorizedAccessToken,
            path = "/v1/soknad"
        )
    }

    @Test
    fun `Request uten corelation id feiler`() {
        val soknad = gyldigSoknad(
            fodselsnummerSoker = gyldigFodselsnummerA
        )

        requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.BadRequest,
            expectedResponse = """
                {
                    "type": "/problem-details/invalid-request-parameters",
                    "title": "invalid-request-parameters",
                    "detail": "Requesten inneholder ugyldige paramtere.",
                    "status": 400,
                    "instance": "about:blank",
                    "invalid_parameters" : [
                        {
                            "name" : "X-Correlation-ID",
                            "reason" : "Correlation ID må settes.",
                            "type": "header"
                        }
                    ]
                }
            """.trimIndent(),
            leggTilCorrelationId = false,
            path = "/v1/soknad"
        )
    }

    @Test
    fun `En ugyldig melding gir valideringsfeil`() {
        val soknad = """
        {
            "søker": {
                "aktørId": "ABC",
                "fødselsnummer": $gyldigFodselsnummerA
            },
            legeerklæring: [],
            samværsavtale: []
        }
        """.trimIndent()

        requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.BadRequest,
            expectedResponse = """
                {
                    "type": "/problem-details/invalid-request-parameters",
                    "title": "invalid-request-parameters",
                    "status": 400,
                    "detail": "Requesten inneholder ugyldige paramtere.",
                    "instance": "about:blank",
                    "invalid_parameters": [{
                        "type": "entity",
                        "name": "søker.aktørId",
                        "reason": "Ikke gyldig Aktør ID.",
                        "invalid_value": "ABC"
                    }]
                }
            """.trimIndent(),
            path = "/v1/soknad"
        )
    }

    @Test
    fun `Gyldig søknad med søknadId på request body`() {
        val forventetSøknadId = UUID.randomUUID().toString()
        val soknad = gyldigSoknad(
            fodselsnummerSoker = dNummerA,
            søknadId = forventetSøknadId
        )

        requestAndAssert(
            soknad = soknad,
            expectedCode = HttpStatusCode.Accepted,
            expectedResponse = """{"id":"$forventetSøknadId"}""",
            path = "/v1/soknad"
        )
    }

    // Utils
    private fun verifiserSoknadLagtTilProsessering(
        incomingJsonString: String,
        outgoingJsonObject: JSONObject
    ) {
        val outgoing = SoknadV1Outgoing(outgoingJsonObject)

        val outgoingFromIncoming = SoknadV1Incoming(incomingJsonString)
            .medSoknadId(outgoing.soknadId)
            .medLegeerklæringUrls(outgoing.legeerklæringUrls)
            .medSamværsavtaleUrls(outgoing.samværrsavtaleUrls)
            .somOutgoing()

        JSONAssert.assertEquals(outgoingFromIncoming.jsonObject.toString(), outgoing.jsonObject.toString(), true)
    }

    private fun requestAndAssert(soknad : String,
                                 expectedResponse : String?,
                                 expectedCode : HttpStatusCode,
                                 leggTilCorrelationId : Boolean = true,
                                 leggTilAuthorization : Boolean = true,
                                 accessToken : String = authorizedAccessToken,
                                 path:String) : String? {
        with(engine) {
            handleRequest(HttpMethod.Post, "$path") {
                if (leggTilAuthorization) {
                    addHeader(HttpHeaders.Authorization, "Bearer $accessToken")
                }
                if (leggTilCorrelationId) {
                    addHeader(HttpHeaders.XCorrelationId, "123156")
                }
                addHeader(HttpHeaders.ContentType, "application/json")
                val requestEntity = objectMapper.writeValueAsString(soknad)
                logger.info("Request Entity = $requestEntity")
                setBody(soknad)
            }.apply {
                logger.info("Response Entity = ${response.content}")
                logger.info("Expected Entity = $expectedResponse")
                assertEquals(expectedCode, response.status())
                when {
                    expectedResponse != null -> JSONAssert.assertEquals(expectedResponse, response.content!!, true)
                    HttpStatusCode.Accepted == response.status() -> {
                        val json = JSONObject(response.content!!)
                        assertEquals(1, json.keySet().size)
                        val soknadId = json.getString("id")
                        assertNotNull(soknadId)
                        return soknadId
                    }
                    else -> assertEquals(expectedResponse, response.content)
                }

            }
        }
        return null
    }


    private fun gyldigSoknad(
        fodselsnummerSoker : String,
        søknadId: String? = null
    ) : String =
        """
        {
            "${JsonKeys.søknadId}": ${
                when (søknadId) {
                    null -> null
                    else -> "$søknadId"
                }
            },
            "søker": {
                "fødselsnummer": "$fodselsnummerSoker",
                "aktørId": "123456"
            },
            legeerklæring: [{
                "content": "${Base64.encodeBase64String("iPhone_6.jpg".fromResources().readBytes())}",
                "contentType": "image/jpeg",
                "title": "Et fint bilde"
            }],
            samværsavtale: [{
                "content": "${Base64.encodeBase64String("iPhone_6.jpg".fromResources().readBytes())}",
                "contentType": "image/jpeg",
                "title": "Et fint bilde"
            }],
            "hvilke_som_helst_andre_atributter": {
                "enabled": true,
                "norsk": "Sære Åreknuter"
            }
        }
        """.trimIndent()

    private fun hentSoknadSendtTilProsessering(soknadId: String?) : JSONObject {
        assertNotNull(soknadId)
        return kafkaTestConsumer.hentSoknad(soknadId, topic = Topics.MOTTATT).data
    }


    private fun hentEttersendingSendtTilProsessering(soknadId: String?) : JSONObject {
        assertNotNull(soknadId)
        return kafkaTestConsumer.hentSoknad(soknadId, topic = Topics.MOTTATT_ETTERSEND).data
    }

}
