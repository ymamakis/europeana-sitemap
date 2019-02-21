package eu.europeana.sitemap.service.update;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import eu.europeana.features.ObjectStorageClient;
import eu.europeana.sitemap.MockActiveDeployment;
import eu.europeana.sitemap.MockObjectStorage;
import eu.europeana.sitemap.XmlUtils;
import eu.europeana.sitemap.exceptions.MailService;
import eu.europeana.sitemap.exceptions.SiteMapException;
import eu.europeana.sitemap.service.ActiveDeploymentService;
import eu.europeana.sitemap.service.ReadSitemapServiceImpl;
import eu.europeana.sitemap.service.ResubmitService;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


/**
 * Tests the UpdateEntityService
 * @see UpdateEntityService
 *
 * @author Patrick Ehlert
 * Created on 30-01-2019
 */
@RunWith(SpringJUnit4ClassRunner.class)
@TestPropertySource("classpath:sitemap-test.properties")
@SpringBootTest(classes = {UpdateEntityService.class})
@EnableAspectJAutoProxy
public class SitemapUpdateEntityServiceTest {

    private static final String PORTAL_BASE_URL = "https://www-test.eanadev.org/portal";
    private static final String TEST_WSKEY = "testkey";

    @Rule
    public WireMockRule wireMockRule = new WireMockRule(wireMockConfig().dynamicPort().dynamicHttpsPort());

    @MockBean
    private ObjectStorageClient mockStorage;
    @MockBean
    private ActiveDeploymentService mockDeployment;
    @MockBean
    private ReadSitemapServiceImpl mockReadSitemap;
    @MockBean
    private ResubmitService mockSubmit;
    @MockBean
    private MailService mockMail;
    @Autowired
    private UpdateEntityService entityService;


    @Before
    public void init() throws IOException {
        // note that the mocks are not connected to each other, so MockActiveDeployment does not use MockObjectStorage for example
        mockStorage = MockObjectStorage.setup(mockStorage);
        mockDeployment = MockActiveDeployment.setup(mockDeployment);
        setupEntityApiMock();
    }

    private void setupEntityApiMock() throws IOException {
        LogManager.getLogger(SitemapUpdateEntityServiceTest.class).info("Mock API port {}, httpsPort {}",
                wireMockRule.port(), wireMockRule.httpsPort());

        String dummyEntitySearchResult = IOUtils.toString(
                Thread.currentThread().getContextClassLoader().getResourceAsStream("dummy_search_result.json"),"UTF-8");

        // return 401 for all unknown wskeys
        stubFor(get(urlPathMatching("/entity/search"))
                .withQueryParam("wskey", matching(".*"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"action\":\"/entity/search\",\"success\":false,\"error\":\"Empty apiKey!\"}")));
        // return dummy entity search results (if wskey is valid)
        stubFor(get(urlPathMatching("/entity/search"))
                .withQueryParam("wskey", equalTo(TEST_WSKEY))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody(dummyEntitySearchResult)));
    }

    private URL getMockEntityApiUrl() {
        try {
            return new URL("http://localhost:" + wireMockRule.port() +"/entity/search");
        } catch (MalformedURLException mue) {
            throw new RuntimeException("Malformed server URL");
        }
    }

    @Test
    public void testNormalUpdate() throws SiteMapException {
        // Change entity-api endpoint to use our wiremock
        entityService.entityApi = getMockEntityApiUrl();
        entityService.entityApiKey = TEST_WSKEY;

        entityService.update();

        // check index file
        String generatedIndex = new String(mockStorage.getContent("sitemap-entity-blue-index.xml"));
        assertNotNull(generatedIndex);
        String expectedSitemapFile = "/sitemap-entity.xml?from=1&amp;to=20";
        String expected = "<loc>" +PORTAL_BASE_URL + expectedSitemapFile + "</loc>";
        assertTrue("String \"" + expected +"\" not found in index file:\n"+ generatedIndex,
                XmlUtils.harmonizeXml(generatedIndex).contains(expected));

        // check sitemap file
        String generatedSitemap = new String(mockStorage.getContent("sitemap-entity-blue.xml?from=1&to=20"));
        assertNotNull(generatedSitemap);
        String expectedEntity = "http://data.europeana.eu/agent/base/34712";
        expected = "<url><loc>"+expectedEntity+"</loc></url>";
        assertTrue("String \"" + expected +"\" not found in sitemap file:\n"+ generatedSitemap,
                XmlUtils.harmonizeXml(generatedSitemap).contains(expected));

    }



}
