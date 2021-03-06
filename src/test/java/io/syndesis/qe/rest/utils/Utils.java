package io.syndesis.qe.rest.utils;

import io.syndesis.qe.rest.endpoints.IntegrationsEndpoint;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.syndesis.model.connection.Action;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.integration.Integration;
import lombok.extern.slf4j.Slf4j;

/**
 * @author jknetl
 */
@Slf4j
public final class Utils {

	private Utils() {
	}

	/**
	 * Finds an Action of a given connector
	 *
	 * @param connector
	 * @param connectorPrefix
	 * @return Action with given prefix or null if no such action can be found.
	 */
	public static Action findAction(Connector connector, String connectorPrefix) {
		Optional<Action> action = connector.getActions()
				.stream()
				.filter(a -> a.getCamelConnectorPrefix().equals(connectorPrefix))
				.findFirst();

		return action.get();
	}

	/**
	 * Waits until a predicate is true or timeout exceeds
	 *
	 * @param predicate predicate
	 * @param supplier supplier of values to test by predicate
	 * @param unit TimeUnit for timeout
	 * @param timeout how long to wait for event
	 * @param sleepUnit TimeUnit of sleep interval between tests
	 * @param sleepTime how long to wait between individual tests (in miliseconds)
	 * @param <T> Type of tested value by a predicate
	 * @return True if predicate become true within a timeout, otherwise returns false.
	 */
	public static <T> boolean waitForEvent(Predicate<T> predicate, Supplier<T> supplier, TimeUnit unit, long timeout, TimeUnit sleepUnit, long sleepTime) {

		long start = System.currentTimeMillis();
		long elapsed = 0;
		while (!predicate.test(supplier.get()) && unit.toMillis(timeout) >= elapsed) {
			try {
				sleepUnit.sleep(sleepTime);
			} catch (InterruptedException e) {
				log.debug("Interupted while sleeping", e);
			} finally {
				elapsed = System.currentTimeMillis() - start;
			}
		}

		return predicate.test(supplier.get());
	}

	/**
	 * Waits until integration is activated or timeout exceeds
	 *
	 * @param e Integration endpoint to obtain current state
	 * @param i integration
	 * @param unit Time unit
	 * @param timeout timeout
	 * @return True if integration is activated within a timeout. False otherwise.
	 */
	public static boolean waitForActivation(IntegrationsEndpoint e, Integration i, TimeUnit unit, long timeout) {

		return waitForEvent(
				integration -> integration.getCurrentStatus().orElse(Integration.Status.Pending) == Integration.Status.Activated,
				() -> getIntegration(e, i).orElse(i),
				unit,
				timeout,
				TimeUnit.SECONDS,
				10
		);
	}

	private static Optional<Integration> getIntegration(IntegrationsEndpoint e, Integration i) {
		return Optional.of(e.get(i.getId().get()));
	}

	/**
	 * Creates map from objects
	 *
	 * @param values key1, value1, key2, value2, ...
	 * @return
	 */
	public static Map<String, String> map(Object... values) {
		HashMap<String, String> rc = new HashMap<String, String>();
		for (int i = 0; i + 1 < values.length; i += 2) {
			rc.put(values[i].toString(), values[i + 1].toString());
		}
		return rc;
	}
}
