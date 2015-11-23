/**
 * Copyright (c) 2010-2013, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.action.gcaltemp.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openhab.core.scriptengine.action.ActionDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;


/**
 * This class contains the methods that are made available in scripts and rules for GCalTemp.
 * 
 * @author Scott Hraban
 * @since 1.0-SNAPSHOT
 */
public class GCalTemp {

	@ActionDoc(text="Fetch calendar entries, calculate and return the current temperature target",
			returns="An integer value calculated from the calendar entries.")
	public static int getGCalTemperature() {
		if (!GCalTempActionService.isProperlyConfigured) {
			logger.error("GCalTemp action is not yet configured - execution aborted!");
			return 0;
		}

		try {
			return getInstance().getTemperature();
		} catch (Exception e) {
			logger.error("Unable to get temperature", e);
			return 0;
		}
	}

	private static final Logger logger = LoggerFactory.getLogger(GCalTemp.class);

	private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
	private static final String CLIENT_SECRETS = "gcaltemp_client_secrets.json";
	private static final String TARGETS_FILENAME = "gcaltemp_targets.json";
	private static final String CREDS_FILENAME = "gcaltemp_creds";
	private static final List<String> SCOPES = Arrays.asList(CalendarScopes.CALENDAR_READONLY);

	private static GCalTemp INSTANCE;

	private final FileDataStoreFactory dataStoreFactory;
	private final JsonFactory jsonFactory;
	private final HttpTransport httpTransport;

	private String calendarName = "Thermostat";
	private int calendarGranularity = 15;
	private int calendarLookahead = 180;

	protected static GCalTemp getInstance() {
		if (INSTANCE == null) {
			try {
				INSTANCE = new GCalTemp();
			} catch (Exception e) {
				logger.error("Unable to initialize GCalTemp Action.", e);
			}
		}
		return INSTANCE;
	}

	private GCalTemp() throws GeneralSecurityException, IOException {
		jsonFactory = JacksonFactory.getDefaultInstance();
		httpTransport = GoogleNetHttpTransport.newTrustedTransport();
		dataStoreFactory = new FileDataStoreFactory(new File(TEMP_DIR, CREDS_FILENAME));
	}

	protected void configure(String clientId, String clientSecret, String calendarName, String calendarGranularity, String calendarLookahead) throws IOException {
		if (clientId == null || clientId.isEmpty() ||
				clientSecret == null || clientSecret.isEmpty()) {
			throw new IllegalArgumentException("Client ID and Client Secret must be supplied");
		}
		writeClientSecretsFile(clientId, clientSecret);

		if (calendarName != null && !calendarName.isEmpty()) {
			this.calendarName = calendarName;
		}

		if (calendarGranularity != null && !calendarGranularity.isEmpty()) {
			try {
				this.calendarGranularity = Integer.valueOf(calendarGranularity);
			} catch (NumberFormatException e) {
				logger.warn("Invalid value for calendarGranularity: \"{}\"", calendarGranularity);
			}
		}

		if (calendarLookahead != null && !calendarLookahead.isEmpty()) {
			try {
				this.calendarLookahead = Integer.valueOf(calendarLookahead);
			} catch (NumberFormatException e) {
				logger.warn("Invalid value for calendarLookahead: \"{}\"", calendarLookahead);
			}
		}
	}

	private int getTemperature() {
		try {
			readCalendarIntoTargetsFile();
		} catch (IOException e) {
			logger.warn("Unable to read temperatures from calendar", e);
		}

		try {
			Map<String, String> targets = readTargetsFromTargetsFile();
			return Integer.valueOf(targets.get(String.valueOf(getFloorNow().getMillis())));
		} catch (IOException e) {
			throw new RuntimeException("Unable to read targets from file", e);
		}
	}

	private void writeClientSecretsFile(String clientId, String clientSecret) throws IOException {
		Map<String, Object> installed = new HashMap<String, Object>();
		installed.put("client_id", clientId);
		installed.put("client_secret", clientSecret);
		installed.put("auth_uri", "https://accounts.google.com/o/oauth2/auth");
		installed.put("token_uri", "https://accounts.google.com/o/oauth2/token");
		installed.put("auth_provider_x509_cert_url", "https://www.googleapis.com/oauth2/v1/certs");
		installed.put("redirect_uris", Arrays.asList("urn:ietf:wg:oauth:2.0:oob", "http://localhost"));

		Map<String, Object> root = new HashMap<String, Object>();
		root.put("installed", installed);

		OutputStreamWriter writer = null;
		try {
			writer = new OutputStreamWriter(new FileOutputStream(new File(TEMP_DIR, CLIENT_SECRETS)));
			writer.write(jsonFactory.toString(root));
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					// Pass
				}
			}
		}
	}

	private Map<String, String> getTargets(List<Event> events) {
		Map<String, String> tempTargets = new LinkedHashMap<String, String>();
		if (events.isEmpty()) {
			return tempTargets;
		}

		org.joda.time.DateTime target = getFloorNow();
		org.joda.time.DateTime endTarget = target.plusMinutes(calendarLookahead);

		Map<Long, Event> eventTargets = new LinkedHashMap<Long, Event>();
		while (target.isBefore(endTarget)) {
			for (Event event : events) {
				try {
					Integer.valueOf(event.getSummary());
				} catch (NumberFormatException e) {
					continue;
				}

				org.joda.time.DateTime start = getStart(event);
				org.joda.time.DateTime end = getEnd(event);

				if (!target.isBefore(start) && target.isBefore(end)) {
					Event original = eventTargets.put(target.getMillis(), event);
					if (original != null && getCreated(original).isAfter(getCreated(event))) {
						eventTargets.put(target.getMillis(), original);
					}
				}
			}

			target = target.plusMinutes(calendarGranularity);
		}

		for (Map.Entry<Long, Event> eventTarget : eventTargets.entrySet()) {
			tempTargets.put(String.valueOf(eventTarget.getKey()), eventTarget.getValue().getSummary());
		}
		return tempTargets;
	}

	private void readCalendarIntoTargetsFile() throws IOException {
		// Build a new authorized API client service.
		// Note: Do not confuse this class with the
		//   com.google.api.services.calendar.model.Calendar class.
		com.google.api.services.calendar.Calendar service = getCalendarService();

		String thermostatCalendarId = null;
		CalendarList calendarList = service.calendarList().list().execute();
		for (CalendarListEntry calendarListEntry : calendarList.getItems()) {
			if (calendarName.equals(calendarListEntry.getSummary())) {
				thermostatCalendarId = calendarListEntry.getId();
			}
		}

		if (thermostatCalendarId == null) {
			throw new IllegalArgumentException("Unable to find Calendar called \"" + calendarName + "\".");
		}

		// List the next 10 events from the primary calendar.
		DateTime now = new DateTime(System.currentTimeMillis());
		Events events = service.events().list(thermostatCalendarId)
				.setTimeMin(now)
				.setTimeMax(new DateTime(new org.joda.time.DateTime(now.getValue()).plusMinutes(calendarLookahead).getMillis()))
				.setOrderBy("startTime")
				.setSingleEvents(true)
				.execute();

		File targetsFile = new File(TEMP_DIR, TARGETS_FILENAME);

		Map<String, String> targets = getTargets(events.getItems());
		if (!targets.isEmpty()) {
			OutputStreamWriter writer = null;
			try {
				writer = new OutputStreamWriter(new FileOutputStream(targetsFile));
				writer.write(jsonFactory.toPrettyString(targets));
			} finally {
				if (writer != null) {
					try {
						writer.close();
					} catch (IOException e) {
						// Pass
					}
				}
			}
		} else {
			targetsFile.delete();
		}
	}

	private Map<String, String> readTargetsFromTargetsFile() throws IOException {
		InputStreamReader reader = null;
		try {
			reader = new InputStreamReader(new FileInputStream(new File(TEMP_DIR, TARGETS_FILENAME)));
			return jsonFactory.fromReader(reader, HashMap.class);
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					// Pass
				}
			}
		}
	}

	private Credential authorize() throws IOException {
		// Load client secrets.
		InputStream in = new FileInputStream(new File(TEMP_DIR, CLIENT_SECRETS));
		GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, new InputStreamReader(in));

		// Build flow and trigger user authorization request.
		GoogleAuthorizationCodeFlow flow =
				new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientSecrets, SCOPES)
		.setDataStoreFactory(dataStoreFactory)
		.setAccessType("offline")
		.build();

		Credential credential = new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
		return credential;
	}

	private com.google.api.services.calendar.Calendar getCalendarService() throws IOException {
		Credential credential = authorize();
		return new com.google.api.services.calendar.Calendar.Builder(httpTransport, jsonFactory, credential)
		.setApplicationName(GCalTemp.class.getSimpleName())
		.build();
	}

	private org.joda.time.DateTime getFloorNow() {
		org.joda.time.DateTime now = new org.joda.time.DateTime();

		return now.minusMinutes(now.getMinuteOfHour() - ((now.getMinuteOfHour() / calendarGranularity) * calendarGranularity))
				.minusSeconds(now.getSecondOfMinute()).minusMillis(now.getMillisOfSecond());
	}

	private org.joda.time.DateTime getStart(Event event) {
		DateTime s = event.getStart().getDateTime();
		if (s == null) {
			s = event.getStart().getDate();
		}
		return new org.joda.time.DateTime(s.getValue());
	}

	private org.joda.time.DateTime getEnd(Event event) {
		DateTime s = event.getEnd().getDateTime();
		if (s == null) {
			s = event.getEnd().getDate();
		}
		return new org.joda.time.DateTime(s.getValue());
	}

	private org.joda.time.DateTime getCreated(Event event) {
		return new org.joda.time.DateTime(event.getCreated().getValue());
	}
}
