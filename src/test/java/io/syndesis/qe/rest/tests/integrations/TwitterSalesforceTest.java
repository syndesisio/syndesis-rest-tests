package io.syndesis.qe.rest.tests.integrations;

import static org.junit.Assert.assertThat;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.infinispan.util.ByteString.emptyString;

import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigList;
import io.fabric8.openshift.api.model.DoneableDeploymentConfig;
import io.fabric8.openshift.client.dsl.DeployableScalableResource;
import io.syndesis.qe.rest.accounts.Account;
import io.syndesis.qe.rest.accounts.AccountsDirectory;
import io.syndesis.qe.rest.dto.salesforce.Contact;
import io.syndesis.qe.rest.endpoints.ConnectionsEndpoint;
import io.syndesis.qe.rest.endpoints.ConnectorsEndpoint;
import io.syndesis.qe.rest.endpoints.IntegrationsEndpoint;
import io.syndesis.qe.rest.endpoints.TestSupport;
import io.syndesis.qe.rest.tests.AbstractSyndesisRestTest;
import io.syndesis.qe.rest.utils.GitHubUtils;
import io.syndesis.qe.rest.utils.SyndesisRestConstants;
import io.syndesis.qe.rest.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.kohsuke.github.GitHub;

import com.force.api.ApiConfig;
import com.force.api.ForceApi;
import com.force.api.QueryResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.syndesis.core.Names;
import io.syndesis.model.connection.Connection;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.integration.Integration;
import io.syndesis.model.integration.SimpleStep;
import io.syndesis.model.integration.Step;
import lombok.extern.slf4j.Slf4j;
import twitter4j.ResponseList;
import twitter4j.Status;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.conf.ConfigurationBuilder;

/**
 * Test for Twitter mention to Salesforce upsert contact integration.
 *
 * @author jknetl
 */
@Slf4j
public class TwitterSalesforceTest extends AbstractSyndesisRestTest {

	public static final String TWITTER_CONNECTION_ID = "fuseqe-twitter";
	public static final String SALESFORCE_CONNECTION_ID = "fuseqe-salesforce";
	public static final String INTEGRATION_NAME = "Twitter to salesforce contact rest test";
	public static final String GITHUB_REPO_NAME = Names.sanitize(INTEGRATION_NAME);
	public static final String SYNDESIS_TALKY_ACCOUNT = "twitter_talky";
	private String mapping;
	private AccountsDirectory accountsDirectory;
	private Connector twitterConnector;
	private Connector salesforceConnector;
	private ConnectionsEndpoint connectionsEndpoint;
	private ConnectorsEndpoint connectorsEndpoint;
	private IntegrationsEndpoint integrationsEndpoint;
	private GitHub gitHub;
	private Twitter twitter;
	private ForceApi salesforce;

	@Before
	public void before() throws IOException, TwitterException {
		accountsDirectory = new AccountsDirectory(Paths.get(SyndesisRestConstants.ACCOUNT_CONFIG_PATH));
		gitHub = GitHub.connect(accountsDirectory.getAccount("github").get().getProperty("login"),
				accountsDirectory.getAccount("github").get().getProperty("token"));
		Account twitterTalky = accountsDirectory.getAccount(SYNDESIS_TALKY_ACCOUNT).get();
		TwitterFactory factory = new TwitterFactory(new ConfigurationBuilder()
				.setOAuthConsumerKey(twitterTalky.getProperty("consumerKey"))
				.setOAuthConsumerSecret(twitterTalky.getProperty("consumerSecret"))
				.setOAuthAccessToken(twitterTalky.getProperty("accessToken"))
				.setOAuthAccessTokenSecret(twitterTalky.getProperty("accessTokenSecret"))
				.build());
		twitter = factory.getInstance();
		// salesforce
		Account salesforceAccount = accountsDirectory.getAccount("salesforce").get();
		salesforce = new ForceApi(new ApiConfig()
				.setClientId(salesforceAccount.getProperty("clientId"))
				.setClientSecret(salesforceAccount.getProperty("clientSecret"))
				.setUsername(salesforceAccount.getProperty("userName"))
				.setPassword(salesforceAccount.getProperty("password"))
				.setForceURL(salesforceAccount.getProperty("loginUrl")));

		cleanup();
	}

	private void cleanup() throws TwitterException {
		TestSupport.resetDB(syndesisToken);
		GitHubUtils.deleteRepositories(gitHub, accountsDirectory.getAccount("github").get().getProperty("login") + "/" + GITHUB_REPO_NAME);
		deleteSalesforceContact(salesforce, accountsDirectory.getAccount("twitter_talky").get().getProperty("screenName"));
		deleteAllTweets(twitter);
	}

	@After
	public void tearDown() throws TwitterException {
		cleanup();
	}

	@Test
	public void testIntegration() throws GeneralSecurityException, IOException, TwitterException {

		mapping = new String(Files.readAllBytes(Paths.get("./target/test-classes/mappings/twitter-salesforce.json")));

		connectorsEndpoint = new ConnectorsEndpoint(syndesisURL, syndesisToken);
		connectionsEndpoint = new ConnectionsEndpoint(syndesisURL, syndesisToken);
		integrationsEndpoint = new IntegrationsEndpoint(syndesisURL, syndesisToken);

		twitterConnector = connectorsEndpoint.get("twitter");
		salesforceConnector = connectorsEndpoint.get("salesforce");
		createConnections();
		createIntegration();
		validateIntegration();
		log.info("Twitter to salesforce integration test finished.");
	}

	private void createConnections() {
		Account twitterAccount = accountsDirectory.getAccount("twitter_listen").get();
		Connection twitterConnection = new Connection.Builder()
				.connector(twitterConnector)
				.connectorId(twitterConnector.getId())
				.id(TWITTER_CONNECTION_ID)
				.name("Fuse QE twitter listen")
				.configuredProperties(Utils.map("accessToken", twitterAccount.getProperty("accessToken"),
						"accessTokenSecret", twitterAccount.getProperty("accessTokenSecret"),
						"consumerKey", twitterAccount.getProperty("consumerKey"),
						"consumerSecret", twitterAccount.getProperty("consumerSecret")))
				.build();

		Account salesforceAccount = accountsDirectory.getAccount("salesforce").get();
		Connection salesforceConnection = new Connection.Builder()
				.connector(salesforceConnector)
				.connectorId(salesforceConnector.getId())
				.id(SALESFORCE_CONNECTION_ID)
				.name("Fuse QE salesforce")
				.configuredProperties(Utils.map("clientId", salesforceAccount.getProperty("clientId"),
						"clientSecret", salesforceAccount.getProperty("clientSecret"),
						"instanceUrl", salesforceAccount.getProperty("instanceUrl"),
						"loginUrl", salesforceAccount.getProperty("loginUrl"),
						"userName", salesforceAccount.getProperty("userName"),
						"password", salesforceAccount.getProperty("password")))
				.build();

		log.info("Creating twitter connection {}", twitterConnection.getName());
		connectionsEndpoint.create(twitterConnection);
		log.info("Creating salesforce connection {}", salesforceConnection.getName());
		connectionsEndpoint.create(salesforceConnection);
	}

	private void createIntegration() {

		Connection twitterConnection = connectionsEndpoint.get(TWITTER_CONNECTION_ID);
		Connection salesforceConnection = connectionsEndpoint.get(SALESFORCE_CONNECTION_ID);

		Step twitterStep = new SimpleStep.Builder()
				.stepKind("endpoint")
				.connection(twitterConnection)
				.action(Utils.findAction(twitterConnector, "twitter-mention"))
				.build();

		Step mapperStep = new SimpleStep.Builder()
				.stepKind("mapper")
				.configuredProperties(Utils.map("atlasmapping", mapping))
				.build();

		Step salesforceStep = new SimpleStep.Builder()
				.stepKind("endpoint")
				.connection(salesforceConnection)
				.action(Utils.findAction(salesforceConnector, "salesforce-upsert-contact"))
				.build();

		Integration integration = new Integration.Builder()
				.steps(Arrays.asList(twitterStep, mapperStep, salesforceStep))
				.name(INTEGRATION_NAME)
				.desiredStatus(Integration.Status.Activated)
				.build();

		log.info("Creating integration {}", integration.getName());
		integration = integrationsEndpoint.create(integration);

		long start = System.currentTimeMillis();
		//wait for activation
		log.info("Waiting until integration becomes active. This may take a while...");
		boolean activated = Utils.waitForActivation(integrationsEndpoint, integration, TimeUnit.MINUTES, 10);
		assertThat(activated, is(true));
		log.info("Integration pod has been started. It took {}s to build the integration.", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start));
	}

	private void validateIntegration() throws TwitterException {
		assertThat(getSalesforceContact(salesforce, accountsDirectory.getAccount(SYNDESIS_TALKY_ACCOUNT).get().getProperty("screenName")).isPresent(), is(false));

		String message = "Have you heard about Syndesis project? It is pretty amazing... @"
				+ accountsDirectory.getAccount("twitter_listen").get().getProperty("screenName");
		log.info("Sending a tweet from {}. Message: {}", accountsDirectory.getAccount(SYNDESIS_TALKY_ACCOUNT).get().getProperty("screenName"), message);
		twitter.updateStatus(message);

		log.info("Waiting until a contact appears in salesforce...");
		long start = System.currentTimeMillis();
		boolean contactCreated = Utils.waitForEvent(contact -> contact.isPresent(),
				() -> getSalesforceContact(salesforce, accountsDirectory.getAccount(SYNDESIS_TALKY_ACCOUNT).get().getProperty("screenName")),
				TimeUnit.MINUTES,
				2,
				TimeUnit.SECONDS,
				5);
		assertThat("Contact has appeard in salesforce", contactCreated, is(true));
		log.info("Contact appeared in salesforce. It took {}s to create contact.", TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - start));

		Contact createdContact = getSalesforceContact(salesforce, accountsDirectory.getAccount(SYNDESIS_TALKY_ACCOUNT).get().getProperty("screenName")).get();
		assertThat(createdContact.getDescription(), equalTo(message));
		assertThat(createdContact.getFirstName(), not(emptyString()));
		assertThat(createdContact.getLastname(), not(emptyString()));
	}

	private void deleteAllTweets(Twitter twitter) throws TwitterException {
		ResponseList<Status> userTimeline = twitter.timelines().getUserTimeline();
		log.info("Deleting all tweets of: " + twitter.getScreenName());

		userTimeline.stream().forEach(s -> {
			try {
				twitter.destroyStatus(s.getId());
			} catch (TwitterException e) {
				log.warn("Cannot destroy status: " + s.getId());
			}
		});
	}

	private void deleteSalesforceContact(ForceApi salesforce, String screenName) {
		Optional<Contact> contact = getSalesforceContact(salesforce, screenName);
		if (contact.isPresent()) {
			String id = String.valueOf(contact.get().getId());
			salesforce.deleteSObject("contact", id);
			log.info("Deleting salesforce contact: {}", contact.get());
		}
	}

	private Optional<Contact> getSalesforceContact(ForceApi salesforce, String twitterName) {
		QueryResult<Contact> queryResult = salesforce.query("SELECT Id,FirstName,LastName,Description,TwitterScreenName__c FROM contact where TwitterScreenName__c = '" + twitterName + "'", Contact.class);
		Optional<Contact> contact = queryResult.getTotalSize() > 0 ? Optional.of(queryResult.getRecords().get(0)) : Optional.empty();
		return contact;
	}
}
