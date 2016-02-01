/**
 * Copyright (c) 2010-2015, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * java-statsd-client. Copyright (C) 2014 youDevise, Ltd.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *  
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *  
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.openhab.persistence.statsd.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.openhab.core.items.Item;
import org.openhab.core.persistence.PersistenceService;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import com.timgroup.statsd.StatsDClientErrorHandler;

/**
 * This is the implementation of the StatsD {@link PersistenceService}.
 * 
 * @author Scott Hraban
 * @since 1.9.0
 */
public class StatsDService implements PersistenceService {
	private static final Logger logger = LoggerFactory.getLogger(StatsDService.class);

	private StatsDClient client;

	private String prefix = "openhab";
	private String host = "localhost";
	private int port = 8125;
	private boolean lowerCase = true;
	private String format = "{name}.{metricType}";
	private List<String> groups;

	public void activate(final BundleContext bundleContext, final Map<String, Object> config) {
		String tmp = (String) config.get("prefix");
		if (StringUtils.isNotBlank(tmp)) {
			prefix = tmp;
		}

		tmp = (String) config.get("host");
		if (StringUtils.isNotBlank(tmp)) {
			host = tmp;
		}

		tmp = (String) config.get("port");
		if (StringUtils.isNotBlank(tmp)) {
			try {
				port = Integer.parseInt(tmp);
			} catch (NumberFormatException e) {
				logger.warn("Unable to use configured port, not a valid number [" + tmp + "]");
			}
		}

		tmp = (String) config.get("lowerCase");
		if (StringUtils.isNotBlank(tmp)) {
			lowerCase = Boolean.parseBoolean(tmp);
		}

		tmp = (String) config.get("format");
		if (StringUtils.isNotBlank(tmp)) {
			format = tmp;
		}

		tmp = (String) config.get("groups");
		if (StringUtils.isNotBlank(tmp)) {
			groups = new ArrayList<String>();
			for (final String str : StringUtils.split(tmp, ",")) {
				if (lowerCase) {
					groups.add(str.trim().toLowerCase());
				} else {
					groups.add(str.trim());
				}
			}
		}

		logger.info("Activating, configuration: prefix={}, host={}, port={}, lowerCase={}, format={}, groups={}", prefix, host, port, lowerCase, format, groups);
	}

	public void deactivate(final int reason) {
		logger.info("Deactivating, closing client");
		synchronized(this) {
			if (client != null) {
				client.stop();
				client = null;
			}
		}
	}

	/**
	 * @{inheritDoc
	 */
	public String getName() {
		return "statsd";
	}

	/**
	 * @{inheritDoc
	 */
	public void store(Item item, String alias) {
		final String value = item.getState().toString();

		String name = StringUtils.isNotBlank(alias) ? alias : item.getName();
		if (lowerCase) name = name.toLowerCase();

		try {
			final String tmp = format.replace("{name}", name).replace("{group}", getGroup(item, name));
			final String counterName = tmp.replace("{metricType}", "count");
			final String gaugeName = tmp.replace("{metricType}", "gauge");

			client().incrementCounter(counterName);
			logger.debug("Sent metric to statsd [{}:1]", counterName);

			try {
				client().recordGaugeValue(gaugeName, Long.parseLong(value));
				logger.debug("Sent metric to statsd [{}:{}]", gaugeName, value);
			} catch (NumberFormatException e1) {
				try {
					client().recordGaugeValue(gaugeName, Double.parseDouble(value));
					logger.debug("Sent metric to statsd [{}:{}]", gaugeName, value);
				} catch (NumberFormatException e2) {
					if (value.equals("ON")) {
						client().recordGaugeValue(gaugeName, 1L);
						logger.debug("Sent metric to statsd [{}:{}]", gaugeName, 1L);
					} else if (value.equals("OFF")) {
						client().recordGaugeValue(gaugeName, 0L);
						logger.debug("Sent metric to statsd [{}:{}]", gaugeName, 0L);
					} else {
						logger.warn("Could not send to statsd for [{}], not a valid number [{}]", name, value);
					}
				}
			}
		} catch (Exception e) {
			logger.error("Could not send to statsd for [" + name + ":" + value + "]", e);
		}
	}

	private String getGroup(final Item item, final String defaultGroup) {
		final List<String> itemGroups = new ArrayList<String>(item.getGroupNames());
		if (lowerCase) {
			for (int i = 0; i < itemGroups.size(); i++) {
				itemGroups.set(i, itemGroups.get(i).toLowerCase());
			}
		}

		for (final String group : groups) {
			if (itemGroups.contains(group)) {
				return group;
			}
		}

		logger.debug("Unable to find a group in the item that matches a statsd configuration group, using default of [{}]", defaultGroup);
		return defaultGroup;
	}

	/**
	 * @{inheritDoc
	 */
	public void store(Item item) {
		store(item, null);
	}

	synchronized private StatsDClient client() {
		if (client != null) return client;

		client = new NonBlockingStatsDClient(prefix, host, port, new ErrorHandler());
		return client;
	}

	private class ErrorHandler implements StatsDClientErrorHandler {

		@Override
		public void handle(Exception e) {
			logger.warn("Handling an error while sending to statsd, reconnecting", e);
			synchronized(StatsDService.this) {
				StatsDClient old = client;
				client = null;
				old.stop();
			}
		}
	}
}
